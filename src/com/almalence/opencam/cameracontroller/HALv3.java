/*
	HALv3 for OpenCamera project - interface to camera2 device
    Copyright (C) 2014  Almalence Inc.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/* <!-- +++
 package com.almalence.opencam_plus;
 +++ --> */
// <!-- -+-
package com.almalence.opencam.cameracontroller;

//-+- -->

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.almalence.SwapHeap;
import com.almalence.YuvImage;
import com.almalence.opencam.CameraParameters;
import com.almalence.opencam.MainScreen;
import com.almalence.opencam.PluginManager;
import com.almalence.util.Util;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.SharedPreferences;

import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Camera.Area;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;

import android.media.Image;
import android.media.ImageReader;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.widget.Toast;

//HALv3 camera's objects
@SuppressLint("NewApi")
@TargetApi(21)
public class HALv3
{
	private static final String				TAG				= "HALv3Controller";

	private static HALv3					instance		= null;

	private static Rect						activeRect		= null;
	private static Rect						zoomCropPreview	= null;
	private static Rect						zoomCropCapture	= null;
	private static float					zoomLevel		= 1f;
	private static MeteringRectangle[]		af_regions;
	private static MeteringRectangle[]		ae_regions;
	
	private static int 						totalFrames 		= 0;
	private static int 						currentFrameIndex 	= 0;
	private static int[] 					pauseBetweenShots 	= new int[0];
	private static int[] 					expRequested 		= null;
	
	protected static boolean				resultInHeap 		= false;
	
	private static int						MAX_SUPPORTED_PREVIEW_SIZE = 1920*1088;
	

	public static HALv3 getInstance()
	{
		if (instance == null)
		{
			instance = new HALv3();
		}
		return instance;
	}

	private CameraManager			manager					= null;
	private CameraCharacteristics	camCharacter			= null;

	private CaptureRequest.Builder	previewRequestBuilder	= null;
	private CameraCaptureSession    mCaptureSession         = null;
    
	protected CameraDevice			camDevice				= null;
	

	private static boolean			autoFocusTriggered		= false;
	
	 /**
     * True if the app is currently trying to open camera
     */
    private boolean mOpeningCamera;

	public static void onCreateHALv3()
	{
		// HALv3 code ---------------------------------------------------------
		// get manager for camera devices
		HALv3.getInstance().manager = (CameraManager) MainScreen.getMainContext().getSystemService("camera");

		// get list of camera id's (usually it will contain just {"0", "1"}
		try
		{
			CameraController.getInstance().cameraIdList = HALv3.getInstance().manager.getCameraIdList();
		} catch (CameraAccessException e)
		{
			Log.d("MainScreen", "getCameraIdList failed");
			e.printStackTrace();
		}
	}

	public static void onPauseHALv3()
	{
		Log.e(TAG, "onPause");
		// HALv3 code -----------------------------------------
		// if ((HALv3.getInstance().availListener != null) &&
		// (HALv3.getInstance().manager != null))
		// HALv3.getInstance().manager.removeAvailabilityListener(HALv3.getInstance().availListener);

		if (null != HALv3.getInstance().camDevice && null != HALv3.getInstance().mCaptureSession)
		try
		{
			HALv3.getInstance().mCaptureSession.stopRepeating();
			HALv3.getInstance().mCaptureSession.close();
			HALv3.getInstance().mCaptureSession = null;
		}
		catch (final CameraAccessException e)
		{
			// Doesn't matter, cloising device anyway
			e.printStackTrace();
		}
		finally
		{
			HALv3.getInstance().camDevice.close();
			HALv3.getInstance().camDevice = null;
			PluginManager.getInstance().sendMessage(PluginManager.MSG_CAMERA_STOPED, 0);
		}		
	}

	public static void openCameraHALv3()
	{
		Log.e(TAG, "openCameraHALv3()");
		// HALv3 open camera
		// -----------------------------------------------------------------
		if (HALv3.getCamera2() == null)
		{
			try
			{
//				onCreateHALv3();
				Log.e(TAG, "try to manager.openCamera");
				String cameraId = CameraController.getInstance().cameraIdList[CameraController.CameraIndex];
				HALv3.getInstance().camCharacter = HALv3.getInstance().manager.getCameraCharacteristics(CameraController
						.getInstance().cameraIdList[CameraController.CameraIndex]);
				HALv3.getInstance().manager.openCamera(cameraId, openListener, null);
			} catch (CameraAccessException e)
			{
				Log.e(TAG, "CameraAccessException manager.openCamera failed: " + e.getMessage());
				e.printStackTrace();
				MainScreen.getInstance().finish();
			}
			catch(IllegalArgumentException e)
			{
				Log.e(TAG, "IllegalArgumentException manager.openCamera failed: " + e.getMessage());
				e.printStackTrace();
				MainScreen.getInstance().finish();
			}
			catch(SecurityException e)
			{
				Log.e(TAG, "SecurityException manager.openCamera failed: " + e.getMessage());
				e.printStackTrace();
				MainScreen.getInstance().finish();
			}
		}

//		try
//		{
//			HALv3.getInstance().camCharacter = HALv3.getInstance().manager.getCameraCharacteristics(CameraController
//					.getInstance().cameraIdList[CameraController.CameraIndex]);
//		} catch (CameraAccessException e)
//		{
//			Log.e(TAG, "getCameraCharacteristics failed: " + e.getMessage());
//			e.printStackTrace();
//			MainScreen.getInstance().finish();
//		}

		CameraController.CameraMirrored = (HALv3.getInstance().camCharacter.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT);

		// Add an Availability Listener as Cameras become available or
		// unavailable
		// HALv3.getInstance().availListener = HALv3.getInstance().new
		// cameraAvailableListener();
		// HALv3.getInstance().manager.addAvailabilityListener(HALv3.getInstance().availListener,
		// null);

		CameraController.getInstance().mVideoStabilizationSupported = HALv3.getInstance().camCharacter
				.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) == null ? false : true;

		// check that full hw level is supported
		if (HALv3.getInstance().camCharacter.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) != CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL)
			MainScreen.getMessageHandler().sendEmptyMessage(PluginManager.MSG_NOT_LEVEL_FULL);

		// Get sensor size for zoom and focus/metering areas.
		activeRect = HALv3.getInstance().camCharacter.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		// ^^ HALv3 open camera
		// -----------------------------------------------------------------
	}

	public static void setupImageReadersHALv3(CameraController.Size sz)
	{
		Log.e(TAG, "setupImageReadersHALv3()");

		MainScreen.getPreviewSurfaceHolder().setFixedSize(sz.getWidth(), sz.getHeight());
		MainScreen.setPreviewWidth(sz.getWidth());
		MainScreen.setPreviewHeight(sz.getHeight());
//		MainScreen.getPreviewSurfaceHolder().setFixedSize(1280, 720);
//		MainScreen.setPreviewWidth(1280);
//		MainScreen.setPreviewHeight(720);

		// HALv3 code
		// -------------------------------------------------------------------
		MainScreen.createImageReaders();
		
		final HandlerThread backgroundThread = new HandlerThread("imageReaders");
		backgroundThread.start();
		
		MainScreen.getPreviewYUVImageReader().setOnImageAvailableListener(
				imageAvailableListener,  null);

		MainScreen.getYUVImageReader().setOnImageAvailableListener(imageAvailableListener,
				 						null);

		MainScreen.getJPEGImageReader().setOnImageAvailableListener(imageAvailableListener,
				 						null);
	}

	public static void populateCameraDimensionsHALv3()
	{
		CameraController.ResolutionsMPixList = new ArrayList<Long>();
		CameraController.ResolutionsSizeList = new ArrayList<CameraController.Size>();
		CameraController.ResolutionsIdxesList = new ArrayList<String>();
		CameraController.ResolutionsNamesList = new ArrayList<String>();
		CameraController.FastIdxelist = new ArrayList<Integer>();

		int minMPIX = CameraController.MIN_MPIX_SUPPORTED;
		CameraCharacteristics params = getCameraParameters2();
		StreamConfigurationMap configMap = params.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);		
		final Size[] cs = configMap.getOutputSizes(ImageFormat.YUV_420_888);		

		int iHighestIndex = 0;
		Size sHighest = cs[iHighestIndex];

		int ii = 0;
		for (Size s : cs)
		{
			int currSizeWidth = s.getWidth();
			int currSizeHeight = s.getHeight();
			int highestSizeWidth = sHighest.getWidth();
			int highestSizeHeight = sHighest.getHeight();

			if ((long) currSizeWidth * currSizeHeight > (long) highestSizeWidth * highestSizeHeight)
			{
				sHighest = s;
				iHighestIndex = ii;
			}

			if ((long) currSizeWidth * currSizeHeight < minMPIX)
				continue;

			CameraController.fillResolutionsList(ii, currSizeWidth, currSizeHeight);

			ii++;
		}

		if (CameraController.ResolutionsNamesList.isEmpty())
		{
			Size s = cs[iHighestIndex];

			int currSizeWidth = s.getWidth();
			int currSizeHeight = s.getHeight();

			CameraController.fillResolutionsList(0, currSizeWidth, currSizeHeight);
		}

		return;
	}
	
	public static void populateCameraDimensionsForMultishotsHALv3()
	{
		CameraController.MultishotResolutionsMPixList = new ArrayList<Long>(CameraController.ResolutionsMPixList);
		CameraController.MultishotResolutionsSizeList = new ArrayList<CameraController.Size>(
				CameraController.ResolutionsSizeList);
		CameraController.MultishotResolutionsIdxesList = new ArrayList<String>(CameraController.ResolutionsIdxesList);
		CameraController.MultishotResolutionsNamesList = new ArrayList<String>(CameraController.ResolutionsNamesList);

		List<CameraController.Size> previewSizes = fillPreviewSizeList();
		if (previewSizes != null && previewSizes.size() > 0)
		{
			fillResolutionsListMultishot(CameraController.MultishotResolutionsIdxesList.size(),
					previewSizes.get(0).getWidth(),
					previewSizes.get(0).getHeight());
		}

		if (previewSizes != null && previewSizes.size() > 1)
		{
			fillResolutionsListMultishot(CameraController.MultishotResolutionsIdxesList.size(),
					previewSizes.get(1).getWidth(),
					previewSizes.get(1).getHeight());
		}

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
		String prefIdx = prefs.getString("imageSizePrefSmartMultishotBack", "-1");

		if (prefIdx.equals("-1"))
		{
			int maxFastIdx = -1;
			long maxMpx = 0;
			for (int i = 0; i < CameraController.FastIdxelist.size(); i++)
			{
				for (int j = 0; j < CameraController.MultishotResolutionsMPixList.size(); j++)
				{
					if (CameraController.FastIdxelist.get(i) == Integer
							.parseInt(CameraController.MultishotResolutionsIdxesList.get(j))
							&& CameraController.MultishotResolutionsMPixList.get(j) > maxMpx)
					{
						maxMpx = CameraController.MultishotResolutionsMPixList.get(j);
						maxFastIdx = j;
					}
				}
			}
			if (previewSizes != null && previewSizes.size() > 0 && maxMpx >= CameraController.MPIX_1080)
			{
				SharedPreferences.Editor prefEditor = prefs.edit();
				prefEditor.putString("imageSizePrefSmartMultishotBack", String.valueOf(maxFastIdx));
				prefEditor.commit();
			}
		}

		return;
	}

	protected static void fillResolutionsListMultishot(int ii, int currSizeWidth, int currSizeHeight)
	{
		boolean needAdd = true;
		boolean isFast = true;

		Long lmpix = (long) currSizeWidth * currSizeHeight;
		float mpix = (float) lmpix / 1000000.f;
		float ratio = (float) currSizeWidth / currSizeHeight;

		// find good location in a list
		int loc;
		for (loc = 0; loc < CameraController.MultishotResolutionsMPixList.size(); ++loc)
			if (CameraController.MultishotResolutionsMPixList.get(loc) < lmpix)
				break;

		int ri = 0;
		if (Math.abs(ratio - 4 / 3.f) < 0.1f)
			ri = 1;
		if (Math.abs(ratio - 3 / 2.f) < 0.12f)
			ri = 2;
		if (Math.abs(ratio - 16 / 9.f) < 0.15f)
			ri = 3;
		if (Math.abs(ratio - 1) == 0)
			ri = 4;

		String newName;
		if (isFast)
		{
			newName = String.format("%3.1f Mpix  " + CameraController.RATIO_STRINGS[ri] + " (fast)", mpix);
		} else
		{
			newName = String.format("%3.1f Mpix  " + CameraController.RATIO_STRINGS[ri], mpix);
		}

		for (int i = 0; i < CameraController.MultishotResolutionsNamesList.size(); i++)
		{
			if (newName.equals(CameraController.MultishotResolutionsNamesList.get(i)))
			{
				Long lmpixInArray = (long) (CameraController.MultishotResolutionsSizeList.get(i).getWidth() * CameraController.MultishotResolutionsSizeList
						.get(i).getHeight());
				if (Math.abs(lmpixInArray - lmpix) / lmpix < 0.1)
				{
					needAdd = false;
					break;
				}
			}
		}

		if (needAdd)
		{
			if (isFast)
			{
				CameraController.FastIdxelist.add(ii);
			}
			CameraController.MultishotResolutionsNamesList.add(loc, newName);
			CameraController.MultishotResolutionsIdxesList.add(loc, String.format("%d", ii));
			CameraController.MultishotResolutionsMPixList.add(loc, lmpix);
			CameraController.MultishotResolutionsSizeList.add(loc, CameraController.getInstance().new Size(
					currSizeWidth, currSizeHeight));
		}
	}
	
	
	public static List<CameraController.Size> fillPreviewSizeList()
	{
		List<CameraController.Size> previewSizes = new ArrayList<CameraController.Size>();
		Size[] cs = HALv3.getInstance().camCharacter.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.YUV_420_888);
		for (Size sz : cs)
			if(sz.getWidth()*sz.getHeight() <= MAX_SUPPORTED_PREVIEW_SIZE)
				previewSizes.add(CameraController.getInstance().new Size(sz.getWidth(), sz.getHeight()));
		
		return previewSizes;
	}

	public static void fillPictureSizeList(List<CameraController.Size> pictureSizes)
	{
		StreamConfigurationMap configMap = HALv3.getInstance().camCharacter.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
		Size[] cs = configMap.getOutputSizes(ImageFormat.YUV_420_888);
		for (Size sz : cs)
			pictureSizes.add(CameraController.getInstance().new Size(sz.getWidth(), sz.getHeight()));
	}

	public static CameraDevice getCamera2()
	{
		return HALv3.getInstance().camDevice;
	}

	public static CameraCharacteristics getCameraParameters2()
	{
		if (HALv3.getInstance().camCharacter != null)
			return HALv3.getInstance().camCharacter;

		return null;
	}

	// Camera parameters interface
	public static boolean isZoomSupportedHALv3()
	{
		if (HALv3.getInstance().camCharacter != null)
		{
			float maxzoom = HALv3.getInstance().camCharacter
					.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
			return maxzoom > 0 ? true : false;
		}

		return false;
	}

	public static float getMaxZoomHALv3()
	{
		if (HALv3.getInstance().camCharacter != null)
			return HALv3.getInstance().camCharacter.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) * 10.0f;

		return 0;
	}

	public static void setZoom(float newZoom)
	{
		if (newZoom < 1f)
		{
			zoomLevel = 1f;
			return;
		}
		zoomLevel = newZoom;
		zoomCropPreview = getZoomRect(zoomLevel, activeRect.width(), activeRect.height());
		HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomCropPreview);
		try
		{
			CameraController.iCaptureID = HALv3.getInstance().mCaptureSession.setRepeatingRequest(
					HALv3.getInstance().previewRequestBuilder.build(), captureListener, null);
		} catch (CameraAccessException e)
		{
			e.printStackTrace();
		}
	}

	public static float getZoom()
	{
		return zoomLevel;
	}
	
	public static Rect getZoomRect(float zoom, int imgWidth, int imgHeight)
	{
		int cropWidth = (int) (imgWidth / zoom) + 2 * 64;
		int cropHeight = (int) (imgHeight / zoom) + 2 * 64;
		// ensure crop w,h divisible by 4 (SZ requirement)
		cropWidth -= cropWidth & 3;
		cropHeight -= cropHeight & 3;
		// crop area for standard frame
		int cropWidthStd = cropWidth - 2 * 64;
		int cropHeightStd = cropHeight - 2 * 64;

		return new Rect((imgWidth - cropWidthStd) / 2, (imgHeight - cropHeightStd) / 2, (imgWidth + cropWidthStd) / 2,
				(imgHeight + cropHeightStd) / 2);
	}

	public static boolean isExposureCompensationSupportedHALv3()
	{
		if (HALv3.getInstance().camCharacter != null)
		{
			Range<Integer> expRange = HALv3.getInstance().camCharacter.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
			return expRange.getLower() == expRange.getUpper() ? false : true;
		}

		return false;
	}

	public static int getMinExposureCompensationHALv3()
	{
		if (HALv3.getInstance().camCharacter != null)
		{
			Range<Integer> expRange = HALv3.getInstance().camCharacter.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
			return expRange.getLower();
		}

		return 0;
	}

	public static int getMaxExposureCompensationHALv3()
	{
		if (HALv3.getInstance().camCharacter != null)
		{
			Range<Integer> expRange = HALv3.getInstance().camCharacter.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
			return expRange.getUpper();
		}

		return 0;
	}

	public static float getExposureCompensationStepHALv3()
	{
		if (HALv3.getInstance().camCharacter != null)
			return HALv3.getInstance().camCharacter.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP).floatValue();
		return 0;
	}

	public static void resetExposureCompensationHALv3()
	{
		if (HALv3.getInstance().previewRequestBuilder != null && HALv3.getInstance().camDevice != null)
		{
			HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);
			try
			{
				CameraController.iCaptureID = HALv3.getInstance().mCaptureSession.setRepeatingRequest(
						HALv3.getInstance().previewRequestBuilder.build(), captureListener,
						null);
			} catch (CameraAccessException e)
			{
				e.printStackTrace();
			}
		}
	}

	public static int[] getSupportedSceneModesHALv3()
	{
		if (HALv3.getInstance().camCharacter != null)
		{
			int[] scenes = HALv3.getInstance().camCharacter.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
			if (scenes.length > 0 && scenes[0] != CameraCharacteristics.CONTROL_SCENE_MODE_DISABLED)
				return scenes;
		}

		return new int[0];
	}

	public static int[] getSupportedWhiteBalanceHALv3()
	{
		if (HALv3.getInstance().camCharacter != null)
		{
			int[] wb = HALv3.getInstance().camCharacter.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
			if (wb.length > 0)
				return wb;
		}

		return new int[0];
	}

	public static int[] getSupportedFocusModesHALv3()
	{
		if (HALv3.getInstance().camCharacter != null)
		{
			int[] focus = HALv3.getInstance().camCharacter.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
			if (focus.length > 0)
				return focus;
		}

		return new int[0];
	}

	public static boolean isFlashModeSupportedHALv3()
	{
		if (HALv3.getInstance().camCharacter != null)
		{
			return HALv3.getInstance().camCharacter.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
		}

		return false;
	}

	public static int[] getSupportedISOModesHALv3()
	{
		if (HALv3.getInstance().camCharacter != null)
		{
			Range<Integer> iso = HALv3.getInstance().camCharacter.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
			int max_iso = iso.getUpper();

			int index = 0;
			for (index = 0; index < CameraController.getIsoValuesList().size(); index++)
			{
				if (max_iso <= CameraController.getIsoValuesList().get(index))
				{
					++index;
					break;
				}
			}
			int[] iso_values = new int[index];
			for (int i = 0; i < index; i++)
				iso_values[i] = CameraController.getIsoValuesList().get(i).byteValue();

			if (iso_values.length > 0)
				return iso_values;
		}

		return new int[0];
	}

	public static boolean isISOModeSupportedHALv3()
	{
		if (HALv3.getInstance().camCharacter != null)
		{
			Range<Integer> iso = HALv3.getInstance().camCharacter.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
			if (iso.getLower() == iso.getUpper())
				return false;
			return true;
		}

		return false;
	}

	public static int getMaxNumMeteringAreasHALv3()
	{
		if (HALv3.getInstance().camCharacter != null)
		{
			return HALv3.getInstance().camCharacter.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
		}

		return 0;
	}

	public static int getMaxNumFocusAreasHALv3()
	{
		if (HALv3.getInstance().camCharacter != null)
		{
			return HALv3.getInstance().camCharacter.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
		}

		return 0;
	}

	public static void setCameraSceneModeHALv3(int mode)
	{
		if (HALv3.getInstance().previewRequestBuilder != null && HALv3.getInstance().camDevice != null)
		{
			HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, mode);
			try
			{
				CameraController.iCaptureID = HALv3.getInstance().mCaptureSession.setRepeatingRequest(
						HALv3.getInstance().previewRequestBuilder.build(), captureListener,
						null);
			}
			catch (CameraAccessException e)
			{
				e.printStackTrace();
			}
			catch(IllegalStateException e2)
			{
				e2.printStackTrace();
			}
			
		}

		PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext()).edit()
				.putInt(MainScreen.sSceneModePref, mode).commit();
	}

	public static void setCameraWhiteBalanceHALv3(int mode)
	{
		if (HALv3.getInstance().previewRequestBuilder != null && HALv3.getInstance().camDevice != null)
		{
			HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, mode);
			try
			{
				CameraController.iCaptureID = HALv3.getInstance().mCaptureSession.setRepeatingRequest(
						HALv3.getInstance().previewRequestBuilder.build(), captureListener,
						null);
			}
			catch (CameraAccessException e)
			{
				e.printStackTrace();
			}
			catch(IllegalStateException e2)
			{
				e2.printStackTrace();
			}
		}

		PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext()).edit()
				.putInt(MainScreen.sWBModePref, mode).commit();
	}

	public static void setCameraFocusModeHALv3(int mode)
	{
		Log.e(TAG, "setCameraFocusModeHALv3 start = " + mode);
		if (HALv3.getInstance().previewRequestBuilder != null && HALv3.getInstance().camDevice != null)
		{
			Log.e(TAG, "setCameraFocusModeHALv3 = " + mode);
			HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, mode);
			try
			{
				CameraController.iCaptureID = HALv3.getInstance().mCaptureSession.setRepeatingRequest(
						HALv3.getInstance().previewRequestBuilder.build(), captureListener,
						null);
			}
			catch (CameraAccessException e)
			{
				e.printStackTrace();
			}
			catch(IllegalStateException e2)
			{
				e2.printStackTrace();
			}
		}

		PreferenceManager
				.getDefaultSharedPreferences(MainScreen.getMainContext())
				.edit()
				.putInt(CameraController.isFrontCamera() ? MainScreen.sRearFocusModePref
						: MainScreen.sFrontFocusModePref, mode).commit();
	}

	public static void setCameraFlashModeHALv3(int mode)
	{
		if (HALv3.getInstance().previewRequestBuilder != null && HALv3.getInstance().camDevice != null)
		{
			HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.FLASH_MODE, mode);
			try
			{
				CameraController.iCaptureID = HALv3.getInstance().mCaptureSession.setRepeatingRequest(
						HALv3.getInstance().previewRequestBuilder.build(), captureListener,
						null);
			}
			catch (CameraAccessException e)
			{
				e.printStackTrace();
			}
			catch(IllegalStateException e2)
			{
				e2.printStackTrace();
			}
		}

		PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext()).edit()
				.putInt(MainScreen.sFlashModePref, mode).commit();
	}

	public static void setCameraISOModeHALv3(int mode)
	{
		if (HALv3.getInstance().previewRequestBuilder != null && HALv3.getInstance().camDevice != null)
		{
			if (mode != 1)
				HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, CameraController
						.getIsoModeHALv3().get(mode));
			try
			{
				CameraController.iCaptureID = HALv3.getInstance().mCaptureSession.setRepeatingRequest(
						HALv3.getInstance().previewRequestBuilder.build(), captureListener,
						null);
			}
			catch (CameraAccessException e)
			{
				e.printStackTrace();
			}
			catch(IllegalStateException e2)
			{
				e2.printStackTrace();
			}
		}

		PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext()).edit()
				.putInt(MainScreen.sISOPref, mode).commit();
	}

	public static void setCameraExposureCompensationHALv3(int iEV)
	{
		if (HALv3.getInstance().previewRequestBuilder != null && HALv3.getInstance().camDevice != null && HALv3.getInstance().mCaptureSession != null)
		{
			HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, iEV);
			try
			{
				CameraController.iCaptureID = HALv3.getInstance().mCaptureSession.setRepeatingRequest(
						HALv3.getInstance().previewRequestBuilder.build(), captureListener,
						null);
			}
			catch (CameraAccessException e)
			{
				e.printStackTrace();
			}
			catch(IllegalStateException e2)
			{
				e2.printStackTrace();
			}
		}

		PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext()).edit()
				.putInt(MainScreen.sEvPref, iEV).commit();
	}

	public static void setCameraFocusAreasHALv3(List<Area> focusAreas)
	{
		Rect zoomRect = getZoomRect(zoomLevel, activeRect.width(), activeRect.height());
		if (focusAreas != null)
		{
			af_regions = new MeteringRectangle[focusAreas.size()];
			for (int i = 0; i < focusAreas.size(); i++)
			{
				Rect r = focusAreas.get(i).rect;
				Log.e(TAG, "focusArea: " + r.left + " " + r.top + " " + r.right + " " + r.bottom);

				Matrix matrix = new Matrix();
				matrix.setScale(1, 1);
				matrix.preTranslate(1000.0f, 1000.0f);
				matrix.postScale((zoomRect.width() - 1) / 2000.0f, (zoomRect.height() - 1) / 2000.0f);

				RectF rectF = new RectF(r.left, r.top, r.right, r.bottom);
				matrix.mapRect(rectF);
				Util.rectFToRect(rectF, r);
				Log.e(TAG, "focusArea after matrix: " + r.left + " " + r.top + " " + r.right + " " + r.bottom);

				int currRegion = i;
				af_regions[currRegion] = new MeteringRectangle(r.left, r.top, r.right, r.bottom, 10);
//				af_regions[currRegion] = r.left;
//				af_regions[currRegion + 1] = r.top;
//				af_regions[currRegion + 2] = r.right;
//				af_regions[currRegion + 3] = r.bottom;
//				af_regions[currRegion + 4] = 10;
			}
		} else
		{
			af_regions = new MeteringRectangle[1];
			af_regions[0] = new MeteringRectangle(0, 0, activeRect.width() - 1, activeRect.height() - 1, 10);
//			af_regions = new int[5];
//			af_regions[0] = 0;
//			af_regions[1] = 0;
//			af_regions[2] = activeRect.width() - 1;
//			af_regions[3] = activeRect.height() - 1;
//			af_regions[4] = 0;
		}
		Log.e(TAG, "activeRect: " + activeRect.left + " " + activeRect.top + " " + activeRect.right + " "
				+ activeRect.bottom);
		Log.e(TAG, "zoomRect: " + zoomRect.left + " " + zoomRect.top + " " + zoomRect.right + " " + zoomRect.bottom);

		if (HALv3.getInstance().previewRequestBuilder != null && HALv3.getInstance().camDevice != null)
		{
			HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, af_regions);
			try
			{
				CameraController.iCaptureID = HALv3.getInstance().mCaptureSession.setRepeatingRequest(
						HALv3.getInstance().previewRequestBuilder.build(), captureListener,
						null);
			}
			catch (CameraAccessException e)
			{
				e.printStackTrace();
			}
			catch(IllegalStateException e2)
			{
				e2.printStackTrace();
			}
		}

	}

	public static void setCameraMeteringAreasHALv3(List<Area> meteringAreas)
	{
		Rect zoomRect = getZoomRect(zoomLevel, activeRect.width(), activeRect.height());
		if (meteringAreas != null)
		{
			ae_regions = new MeteringRectangle[meteringAreas.size()];
			for (int i = 0; i < meteringAreas.size(); i++)
			{
				Rect r = meteringAreas.get(i).rect;

				Matrix matrix = new Matrix();
				matrix.setScale(1, 1);
				matrix.preTranslate(1000.0f, 1000.0f);
				matrix.postScale((zoomRect.width() - 1) / 2000.0f, (zoomRect.height() - 1) / 2000.0f);

				RectF rectF = new RectF(r.left, r.top, r.right, r.bottom);
				matrix.mapRect(rectF);
				Util.rectFToRect(rectF, r);

				int currRegion = i;
				ae_regions[currRegion] = new MeteringRectangle(r.left, r.top, r.right, r.bottom, 10);
//				ae_regions[currRegion] = r.left;
//				ae_regions[currRegion + 1] = r.top;
//				ae_regions[currRegion + 2] = r.right;
//				ae_regions[currRegion + 3] = r.bottom;
//				ae_regions[currRegion + 4] = 10;
			}
		} else
		{
			ae_regions = new MeteringRectangle[1];
			ae_regions[0] = new MeteringRectangle(0, 0, activeRect.width() - 1, activeRect.height() - 1, 10);
//			ae_regions = new int[5];
//			ae_regions[0] = 0;
//			ae_regions[1] = 0;
//			ae_regions[2] = activeRect.width() - 1;
//			ae_regions[3] = activeRect.height() - 1;
//			ae_regions[4] = 0;
		}

		if (HALv3.getInstance().previewRequestBuilder != null && HALv3.getInstance().camDevice != null)
		{
			HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, ae_regions);
			try
			{
				CameraController.iCaptureID = HALv3.getInstance().mCaptureSession.setRepeatingRequest(
						HALv3.getInstance().previewRequestBuilder.build(), captureListener,
						null);
			}
			catch (CameraAccessException e)
			{
				e.printStackTrace();
			}
			catch(IllegalStateException e2)
			{
				e2.printStackTrace();
			}
		}
	}

	public static int getPreviewFrameRateHALv3()
	{
		Range<Integer>[] range;
		range = HALv3.getInstance().camCharacter.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
		return range[range.length - 1].getUpper();
	}


	public static int captureImageWithParamsHALv3(final int nFrames, final int format, final int[] pause,
			final int[] evRequested, final boolean resInHeap)
	{
		int requestID = -1;
		final CaptureRequest.Builder stillRequestBuilder;
		try
		{
			stillRequestBuilder = HALv3.getInstance().camDevice
					.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
			
			if (format == CameraController.YUV_RAW)
			{
				stillRequestBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
				stillRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE,
						CaptureRequest.NOISE_REDUCTION_MODE_OFF);
			} else
			{
				stillRequestBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
				stillRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE,
						CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
			}
			stillRequestBuilder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY);
			if ((zoomLevel > 1.0f) && (format != CameraController.YUV_RAW))
			{
				zoomCropCapture = getZoomRect(zoomLevel, activeRect.width(), activeRect.height());
				stillRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomCropCapture);
			}

			// no re-focus needed, already focused in preview, so keeping the
			// same focusing mode for snapshot
			// stillRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
			// CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
			// Google: note: CONTROL_AF_MODE_OFF causes focus to move away from
			// current position
			// stillRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
			// CaptureRequest.CONTROL_AF_MODE_OFF);
			if (format == CameraController.JPEG)
			{
				Log.e("HALv3", "Capture " + nFrames + " JPEGs");
				stillRequestBuilder.addTarget(MainScreen.getJPEGImageReader().getSurface());
			} else
			{
				Log.e("HALv3", "Capture " + nFrames + " YUVs");
				stillRequestBuilder.addTarget(MainScreen.getYUVImageReader().getSurface());
			}

			// Google: throw: "Burst capture implemented yet", when to expect
			// implementation?
			/*
			 * List<CaptureRequest> requests = new ArrayList<CaptureRequest>();
			 * for (int n=0; n<NUM_FRAMES; ++n)
			 * requests.add(stillRequestBuilder.build());
			 * 
			 * camDevice.captureBurst(requests, new captureListener() , null);
			 */

//			HALv3.getInstance().mCaptureSession.stopRepeating();
			// requests for SZ input frames
			resultInHeap = resInHeap;
			if (Array.getLength(pause) > 0)
			{
				totalFrames = nFrames;
				currentFrameIndex = 0;
				pauseBetweenShots = pause;
				expRequested = evRequested;
				captureNextImageWithParams(format, pause[currentFrameIndex], evRequested, currentFrameIndex);
			} else
			{
				pauseBetweenShots = new int[totalFrames];
				if (evRequested != null && evRequested.length >= nFrames)
				{
					 for (int n=0; n<nFrames; ++n)
					 {
						 stillRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evRequested[n]);

						try
						{
							HALv3.getInstance().mCaptureSession.capture(stillRequestBuilder.build(),
									captureListener, null);
						} catch (CameraAccessException e)
						{
							e.printStackTrace();
						}
					 }
				} else
				{
					for (int n = 0; n < nFrames; ++n)
					{
						// if(evRequested != null && evRequested.length > n)
						// {
						// stillRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
						// evRequested[n]);
						// setCameraExposureCompensationHALv3(evRequested[n]);
						// }
						requestID = HALv3.getInstance().mCaptureSession.capture(stillRequestBuilder.build(),
								captureListener, null);
					}
				}
			}

			// requestID =
			// HALv3.getInstance().camDevice.captureBurst(requestList,
			// captureListener , null);
			// requestID =
			// HALv3.getInstance().camDevice.capture(stillRequestBuilder.build(),
			// captureListener , null);
			// Log.e("CameraController", "captureImage 4");
			// One more capture for comparison with a standard frame
			// stillRequestBuilder.set(CaptureRequest.EDGE_MODE,
			// CaptureRequest.EDGE_MODE_HIGH_QUALITY);
			// stillRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE,
			// CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
			// // set crop area for the scaler to have interpolation applied by
			// camera HW
			// stillRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION,
			// zoomCrop);
			// camDevice.capture(stillRequestBuilder.build(), new
			// captureListener() , null);
		} catch (CameraAccessException e)
		{
			Log.e(TAG, "setting up still image capture request failed");
			e.printStackTrace();
			throw new RuntimeException();
		}

		return requestID;
	}
	
	private static int captureNextImageWithParams(final int format, final int pause, final int[] evRequested, final int index)
	{
		int requestID = -1;
		final CaptureRequest.Builder stillRequestBuilder;
		try
		{
			stillRequestBuilder = HALv3.getInstance().camDevice
					.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
			stillRequestBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
			stillRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE,
					CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
			stillRequestBuilder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY);
			if (zoomLevel >= 1.0f)
			{
				zoomCropCapture = getZoomRect(zoomLevel, activeRect.width(), activeRect.height());
				stillRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomCropCapture);
			}

			if (format == CameraController.JPEG)
			{
				stillRequestBuilder.addTarget(MainScreen.getJPEGImageReader().getSurface());
			} else
			{
				stillRequestBuilder.addTarget(MainScreen.getYUVImageReader().getSurface());
			}

			if (pause > 0)
			{
				new CountDownTimer(pause, pause)
				{
					public void onTick(long millisUntilFinished)
					{
						
					}

					public void onFinish()
					{
						// play tick sound
						MainScreen.getGUIManager().showCaptureIndication();
						MainScreen.getInstance().playShutter();
						if (evRequested != null && evRequested.length > index)
							stillRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evRequested[index]);

						try
						{
							HALv3.getInstance().mCaptureSession.capture(stillRequestBuilder.build(),
									captureListener, null);
						} catch (CameraAccessException e)
						{
							e.printStackTrace();
						}
					}
				}.start();

			}
			else
			{
				// play tick sound
				MainScreen.getGUIManager().showCaptureIndication();
				MainScreen.getInstance().playShutter();
				if (evRequested != null && evRequested.length > index)
					stillRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evRequested[index]);

				try
				{
					HALv3.getInstance().mCaptureSession.capture(stillRequestBuilder.build(),
							captureListener, null);
				} catch (CameraAccessException e)
				{
					e.printStackTrace();
				}
			}
		}
		catch (CameraAccessException e)
		{
			Log.e(TAG, "setting up still image capture request failed");
			e.printStackTrace();
			throw new RuntimeException();
		}			
			
			return requestID;
	}

	public static boolean autoFocusHALv3()
	{
		if (HALv3.getInstance().previewRequestBuilder != null && HALv3.getInstance().camDevice != null)
		{
			// if(af_regions != null)
			// HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS,
			// af_regions);
			HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
					CameraCharacteristics.CONTROL_AF_TRIGGER_START);
			try
			{
				Log.e(TAG,
						"autoFocusHALv3. CaptureRequest.CONTROL_AF_TRIGGER, CameraCharacteristics.CONTROL_AF_TRIGGER_START");
				CameraController.iCaptureID = HALv3.getInstance().mCaptureSession.capture(
						HALv3.getInstance().previewRequestBuilder.build(), focusListener,
						null);
			} catch (CameraAccessException e)
			{
				e.printStackTrace();
				return false;
			}

			HALv3.autoFocusTriggered = true;

			return true;
		}

		return false;
	}

	public static void cancelAutoFocusHALv3()
	{
		if (HALv3.getInstance().previewRequestBuilder != null && HALv3.getInstance().camDevice != null)
		{
			HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
					CameraCharacteristics.CONTROL_AF_TRIGGER_CANCEL);
			try
			{
				CameraController.iCaptureID = HALv3.getInstance().mCaptureSession.capture(
						HALv3.getInstance().previewRequestBuilder.build(), captureListener,
						null);
			} catch (CameraAccessException e)
			{
				e.printStackTrace();
			}

			HALv3.autoFocusTriggered = false;
		}
	}

	public void configurePreviewRequest() throws CameraAccessException
	{
		Log.e(TAG, "configurePreviewRequest()");
		HALv3.getInstance().previewRequestBuilder = HALv3.getInstance().camDevice
				.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
		HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
				CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
		HALv3.getInstance().previewRequestBuilder.addTarget(MainScreen.getInstance().getCameraSurface());
		HALv3.getInstance().previewRequestBuilder.addTarget(MainScreen.getInstance().getPreviewYUVSurface());
		HALv3.getInstance().mCaptureSession.setRepeatingRequest(HALv3.getInstance().previewRequestBuilder.build(),
				captureListener, null);
	}

	// HALv3 ------------------------------------------------ camera-related
	// listeners
	@SuppressLint("Override")
	public final static CameraDevice.StateListener openListener = new CameraDevice.StateListener()
	{
		@Override
		public void onDisconnected(CameraDevice arg0)
		{
			Log.e(TAG, "CameraDevice.StateListener.onDisconnected");
			if (HALv3.getInstance().camDevice != null)
			{
				try
				{
					HALv3.getInstance().camDevice.close();
					HALv3.getInstance().camDevice = null;
					HALv3.getInstance().mOpeningCamera = false;
				} catch (Exception e)
				{
					HALv3.getInstance().camDevice = null;
					Log.e(TAG, "close camera device failed: " + e.getMessage());
					e.printStackTrace();
				}
			}
		}

		@Override
		public void onError(CameraDevice arg0, int arg1)
		{
			HALv3.getInstance().mOpeningCamera = false;
			Log.e(TAG, "CameraDevice.StateListener.onError: " + arg1);
		}

		@Override
		public void onOpened(CameraDevice arg0)
		{
			Log.e(TAG, "CameraDevice.StateListener.onOpened");

			HALv3.getInstance().camDevice = arg0;
			
			HALv3.getInstance().mOpeningCamera = false;

			MainScreen.getMessageHandler().sendEmptyMessage(PluginManager.MSG_CAMERA_OPENED);

			// dumpCameraCharacteristics();
		}
	};
	
	
	public final static CameraCaptureSession.StateListener captureSessionStateListener = new CameraCaptureSession.StateListener()
	{
		@Override
		public void onConfigureFailed(final CameraCaptureSession session)
		{
			Log.e(TAG, "CaptureSessionConfigure failed");
			MainScreen.getInstance().finish();
		}

		@Override
		public void onConfigured(final CameraCaptureSession session)
		{
			HALv3.getInstance().mCaptureSession = session;
			
			try
			{
				Log.e(TAG, "configurePreviewRequest()");
				HALv3.getInstance().previewRequestBuilder = HALv3.getInstance().camDevice
						.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
				HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
						CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
				HALv3.getInstance().previewRequestBuilder.addTarget(MainScreen.getInstance().getCameraSurface());
				HALv3.getInstance().previewRequestBuilder.addTarget(MainScreen.getInstance().getPreviewYUVSurface());
				CameraController.iCaptureID = session.setRepeatingRequest(HALv3.getInstance().previewRequestBuilder.build(),
											captureListener, null);
				
				PluginManager.getInstance().sendMessage(PluginManager.MSG_CAMERA_CONFIGURED, 0);
			}
			catch (final Exception e)
			{
				e.printStackTrace();
				Toast.makeText(MainScreen.getInstance(), "Unable to start preview: " + e.getMessage(), Toast.LENGTH_SHORT).show();
				MainScreen.getInstance().finish();
			}
		}
		
	};

	// Note: there other onCaptureXxxx methods in this listener which we do not
	// implement
	public final static CameraCaptureSession.CaptureListener focusListener = new CameraCaptureSession.CaptureListener()
	{
		@Override
		public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result)
		{
			PluginManager.getInstance().onCaptureCompleted(result);
			try
			{
				// HALv3.exposureTime =
				// result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
				// Log.e(TAG, "EXPOSURE TIME = " + HALv3.exposureTime);
				Log.e(TAG, "onFocusCompleted. AF State = " + result.get(CaptureResult.CONTROL_AF_STATE));
				if (result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
						&& HALv3.autoFocusTriggered)
				{
					Log.e(TAG,
							"onFocusCompleted. CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED");
					resetCaptureListener();
					CameraController.getInstance().onAutoFocus(true);
					HALv3.autoFocusTriggered = false;

				} else if (result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
						&& HALv3.autoFocusTriggered)
				{
					Log.e(TAG,
							"onFocusCompleted. CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED");
					resetCaptureListener();
					CameraController.getInstance().onAutoFocus(false);
					HALv3.autoFocusTriggered = false;
				} else if (result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
						&& HALv3.autoFocusTriggered)
				{
					Log.e(TAG,
							"onFocusCompleted. CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN");
					// resetCaptureListener();
					// CameraController.getInstance().onAutoFocus(false);
					// HALv3.autoFocusTriggered = false;
				}
			} catch (Exception e)
			{
				Log.e(TAG, "Exception: " + e.getMessage());
			}

			// if(result.getSequenceId() == iCaptureID)
			// {
			// //Log.e(TAG, "Image metadata received. Capture timestamp = " +
			// result.get(CaptureResult.SENSOR_TIMESTAMP));
			// iPreviewFrameID = result.get(CaptureResult.SENSOR_TIMESTAMP);
			// }

			// Note: result arriving here is just image metadata, not the image
			// itself
			// good place to extract sensor gain and other parameters

			// Note: not sure which units are used for exposure time (ms?)
			// currentExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
			// currentSensitivity =
			// result.get(CaptureResult.SENSOR_SENSITIVITY);

			// dumpCaptureResult(result);
		}

		private void resetCaptureListener()
		{
			if (HALv3.getInstance().previewRequestBuilder != null && HALv3.getInstance().camDevice != null)
			{
				int focusMode = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext()).getInt(
						CameraController.isFrontCamera() ? MainScreen.sRearFocusModePref
								: MainScreen.sFrontFocusModePref, CameraParameters.AF_MODE_AUTO);
				HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, focusMode);
				HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
						CameraCharacteristics.CONTROL_AF_TRIGGER_CANCEL);
				try
				{
					// HALv3.getInstance().camDevice.stopRepeating();
					CameraController.iCaptureID = HALv3.getInstance().mCaptureSession.capture(
							HALv3.getInstance().previewRequestBuilder.build(), null, null);
				} catch (CameraAccessException e)
				{
					e.printStackTrace();
				}
			}
		}
	};

	public final static CameraCaptureSession.CaptureListener captureListener = new CameraCaptureSession.CaptureListener()
	{
		@Override
		public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result)
		{
			PluginManager.getInstance().onCaptureCompleted(result);
			try
			{
				// HALv3.exposureTime =
				// result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
				// Log.e(TAG, "EXPOSURE TIME = " + HALv3.exposureTime);
				if (result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
						&& HALv3.autoFocusTriggered)
				{
					Log.e(TAG,
							"onCaptureCompleted. CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED");
					resetCaptureListener();
					CameraController.getInstance().onAutoFocus(true);
					HALv3.autoFocusTriggered = false;

				} else if (result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
						&& HALv3.autoFocusTriggered)
				{
					Log.e(TAG,
							"onCaptureCompleted. CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED");
					resetCaptureListener();
					CameraController.getInstance().onAutoFocus(false);
					HALv3.autoFocusTriggered = false;
				} else if (result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
						&& HALv3.autoFocusTriggered)
				{
					Log.e(TAG,
							"onCaptureCompleted. CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN");
					// resetCaptureListener();
					// CameraController.getInstance().onAutoFocus(false);
					// HALv3.autoFocusTriggered = false;
				}
			} catch (Exception e)
			{
				Log.e(TAG, "Exception: " + e.getMessage());
			}

			// if(result.getSequenceId() == iCaptureID)
			// {
			// //Log.e(TAG, "Image metadata received. Capture timestamp = " +
			// result.get(CaptureResult.SENSOR_TIMESTAMP));
			// iPreviewFrameID = result.get(CaptureResult.SENSOR_TIMESTAMP);
			// }

			// Note: result arriving here is just image metadata, not the image
			// itself
			// good place to extract sensor gain and other parameters

			// Note: not sure which units are used for exposure time (ms?)
			// currentExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
			// currentSensitivity =
			// result.get(CaptureResult.SENSOR_SENSITIVITY);

			// dumpCaptureResult(result);
		}

		private void resetCaptureListener()
		{
			if (HALv3.getInstance().previewRequestBuilder != null && HALv3.getInstance().camDevice != null)
			{
				int focusMode = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext()).getInt(
						CameraController.isFrontCamera() ? MainScreen.sRearFocusModePref
								: MainScreen.sFrontFocusModePref, CameraParameters.AF_MODE_AUTO);
				HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, focusMode);
				HALv3.getInstance().previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
						CameraCharacteristics.CONTROL_AF_TRIGGER_CANCEL);
				try
				{
					// HALv3.getInstance().camDevice.stopRepeating();
					CameraController.iCaptureID = HALv3.getInstance().mCaptureSession.capture(
							HALv3.getInstance().previewRequestBuilder.build(), null, null);
				} catch (CameraAccessException e)
				{
					e.printStackTrace();
				}
			}
		}
	};

	public final static ImageReader.OnImageAvailableListener imageAvailableListener = new ImageReader.OnImageAvailableListener()
	{
		@Override
		public void onImageAvailable(ImageReader ir)
		{
			// Contrary to what is written in Aptina presentation
			// acquireLatestImage is not working as described
			// Google: Also, not working as described in android docs (should
			// work the same as acquireNextImage in our case, but it is not)
			// Image im = ir.acquireLatestImage();

			Image im = ir.acquireNextImage();
			// if(iPreviewFrameID == im.getTimestamp())
			if (ir.getSurface() == CameraController.mPreviewSurface)
			{
				ByteBuffer Y = im.getPlanes()[0].getBuffer();
				ByteBuffer U = im.getPlanes()[1].getBuffer();
				ByteBuffer V = im.getPlanes()[2].getBuffer();

				if ((!Y.isDirect()) || (!U.isDirect()) || (!V.isDirect()))
				{
					Log.e(TAG, "Oops, YUV ByteBuffers isDirect failed");
					return;
				}

				int imageWidth = im.getWidth();
				int imageHeight = im.getHeight();
				// Note: android documentation guarantee that:
				// - Y pixel stride is always 1
				// - U and V strides are the same
				// So, passing all these parameters is a bit overkill
				
				byte[] data = YuvImage.CreateSingleYUVImage(Y, U, V, im.getPlanes()[0].getPixelStride(),
						im.getPlanes()[0].getRowStride(), im.getPlanes()[1].getPixelStride(),
						im.getPlanes()[1].getRowStride(), im.getPlanes()[2].getPixelStride(),
						im.getPlanes()[2].getRowStride(), imageWidth, imageHeight);
				
				PluginManager.getInstance().onPreviewFrame(data);
			}
			else
			{
				Log.e("HALv3", "onImageAvailable");
				//PluginManager.getInstance().onImageAvailable(im);
//				int frame = CameraController.getImageFrame(im);
//				byte[] frameData = CameraController.getImageFrameData(im);
//				int frame_len = CameraController.getImageLenght(im);
//				boolean isYUV = CameraController.isYUVImage(im);
				
				
				int frame = 0;
				byte[] frameData = new byte[0];
				int frame_len = 0;
				boolean isYUV = false;

				if (im.getFormat() == ImageFormat.YUV_420_888)
				{
					ByteBuffer Y = im.getPlanes()[0].getBuffer();
					ByteBuffer U = im.getPlanes()[1].getBuffer();
					ByteBuffer V = im.getPlanes()[2].getBuffer();

					if ((!Y.isDirect()) || (!U.isDirect()) || (!V.isDirect()))
					{
						Log.e(TAG, "Oops, YUV ByteBuffers isDirect failed");
						return;
					}

					// Note: android documentation guarantee that:
					// - Y pixel stride is always 1
					// - U and V strides are the same
					// So, passing all these parameters is a bit overkill
					int status = YuvImage.CreateYUVImage(Y, U, V, im.getPlanes()[0].getPixelStride(),
							im.getPlanes()[0].getRowStride(), im.getPlanes()[1].getPixelStride(),
							im.getPlanes()[1].getRowStride(), im.getPlanes()[2].getPixelStride(),
							im.getPlanes()[2].getRowStride(), MainScreen.getImageWidth(), MainScreen.getImageHeight(), 0);

					if (status != 0)
						Log.e(TAG, "Error while cropping: " + status);

					if(!resultInHeap)
						frameData = YuvImage.GetByteFrame(0);
					else
						frame = YuvImage.GetFrame(0);
					
					
					frame_len = MainScreen.getImageWidth() * MainScreen.getImageHeight() + MainScreen.getImageWidth()
								* ((MainScreen.getImageHeight() + 1) / 2);
					
					isYUV = true;
					
				} else if (im.getFormat() == ImageFormat.JPEG)
				{
					ByteBuffer jpeg = im.getPlanes()[0].getBuffer();

					frame_len = jpeg.limit();
					frameData = new byte[frame_len];
					jpeg.get(frameData, 0, frame_len);

					if(resultInHeap)
					{
						frame = SwapHeap.SwapToHeap(frameData);
						frameData = null;
					}
				}
				
				PluginManager.getInstance().onImageTaken(frame, frameData, frame_len, isYUV);
				
				if(++currentFrameIndex < totalFrames)
					captureNextImageWithParams(CameraController.frameFormat, pauseBetweenShots[currentFrameIndex], expRequested, currentFrameIndex);
			}

			// Image should be closed after we are done with it
			im.close();
		}
	};
	// ^^ HALv3 code
	// --------------------------------------------------------------
	// camera-related listeners
}
