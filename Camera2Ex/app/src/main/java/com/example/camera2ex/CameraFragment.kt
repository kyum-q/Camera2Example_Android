package com.example.camera2ex

import ImageSaver
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import com.example.camera2ex.databinding.FragmentCameraBinding
import java.lang.Long
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.Array
import kotlin.Boolean
import kotlin.Comparator
import kotlin.Float
import kotlin.Int
import kotlin.NullPointerException
import kotlin.RuntimeException
import kotlin.String
import kotlin.Unit
import kotlin.also
import kotlin.apply
import kotlin.arrayOf
import kotlin.let
import kotlin.math.max
import kotlin.toString
import kotlin.with


class CameraFragment : Fragment() {

    private lateinit var binding: FragmentCameraBinding
    private lateinit var objectDetectionModule: ObjectDetectionModule

    /**
     * TextureView를 사용 가능 한 지와 이와 관련된 surface에 관해 호출되는 리스너
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        // TextureView가 surfaceTexture를 사용할 준비가 됨
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        // surfaceTexture의 버퍼크기가 변했음
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        // surfaceTexture가 소멸되려 함
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            return true
        }

        // surfaceTexture가 업데이트 됨
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            // TODO: 객체 인식해야 함
            val newBitmap = binding.texture.bitmap?.let {
                objectDetectionModule.runObjectDetection(it)
            }
            binding.imageView.setImageBitmap(newBitmap)
        }
    }

    /**
     * 카메라 캡처 세션의 상태에 대한 업데이트를 수신하기 위한 콜백
     */
    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@CameraFragment.cameraDevice = cameraDevice
            createCameraPreviewSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@CameraFragment.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {

        }

    }

    /**
     * 카메라 장치에 capture REquest의 진행률을 추천하기 위한 콜백
     */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult,
        ) {
            process(partialResult)
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            process(result)
        }

//        var preState = 0;
//        var preAfState = 0;

        private fun process(result: CaptureResult) {

            when (state) {
                // 프리뷰 상태
                STATE_PREVIEW -> Unit // Do nothing when the camera preview is working normally.
                // 촬영 위해 focus lock한 상태
                STATE_WAITING_LOCK -> capturePicture(result)
                // 촬영 위해 precaputure 대기 상태
                STATE_WAITING_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED
                    ) {
                        state = STATE_WAITING_NON_PRECAPTURE
                    }
                }
                // 촬영을 위한 precapture 완료한 상태
                STATE_WAITING_NON_PRECAPTURE -> {
//                    Log.d("렌즈 초점 결과", "process: STATE_WAITING_NON_PRECAPTURE")
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        state = STATE_PICTURE_TAKEN
                        captureStillPicture(null)
                    }
                }
            }
        }

        // 캡처가 준비됬는지 확인하고 준비됬으면 바로 캡처 함수 호출 | 안됐으면 준비 함수 호출
        private fun capturePicture(result: CaptureResult) {
//            Log.d("렌즈 초점 결과", "capturePicture 1")
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            // CONTROL_AF_STATE 키에 해당되는 것이 없다
            if (afState == null) {
//                Log.d("렌즈 초점 결과", "capturePicture 2")
                // 캡처 함수
                captureStillPicture(null)

                // CONTROL_AF_STATE_FOCUSED_LOCKED, CONTROL_AF_STATE_NOT_FOCUSED_LOCKED 이다
            } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            ) {
                // CONTROL_AE_STATE can be null on some devices
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                    state = STATE_PICTURE_TAKEN
                    // 캡처 함수
                    captureStillPicture(null)
                } else {
                    // 준비 함수
                    runPrecaptureSequence()
                }
            }
        }
    }

    private lateinit var cameraId: String

    private var captureSession: CameraCaptureSession? = null

    private var cameraDevice: CameraDevice? = null

    private lateinit var previewSize: Size

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var imageReader: ImageReader? = null
    private var imageSaver: ImageSaver? = null

    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var previewRequest: CaptureRequest

    private var state = STATE_PREVIEW

    private val cameraOpenCloseLock = Semaphore(1)

    private var flashSupported = false

    private var sensorOrientation = 0

    private var minimumFocusDistance: Float = 0f

    private lateinit var mediaPlayer: MediaPlayer

    public var pictureCount = MutableLiveData<Int>(0)

    /**
     * This a callback object for the [ImageReader]. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private val onImageAvailableListener = ImageReader.OnImageAvailableListener {
        imageSaver = ImageSaver(it.acquireNextImage(), requireContext(), pictureCount)
        backgroundHandler?.post(imageSaver!!)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {

        binding = FragmentCameraBinding.inflate(inflater, container, false)
        objectDetectionModule = ObjectDetectionModule(requireContext())

        mediaPlayer = MediaPlayer.create(context, R.raw.end_sound)

        // preview 이벤트 리스너 등록
        binding.texture.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    setTouchPointDistanceChange(event.x, event.y)
                    return@setOnTouchListener true
                }
                else -> return@setOnTouchListener false
            }
        }

        binding.burstPicture.setOnClickListener {
            PICTURE_SIZE = 3
            lockFocus()
        }

        binding.distancePicture.setOnClickListener {
            PICTURE_SIZE = 10

            Log.d("렌즈 초점 결과", "distanceBurstBtn on Click")
            val distanceUnit = minimumFocusDistance / 10f

            val queue = ArrayDeque<Float>()
            for(i in 1 .. 10) {
                queue.add(distanceUnit*i);
            }

            setFocusDistance(queue)
        }

        binding.picture.setOnClickListener {
            PICTURE_SIZE = 1
            lockFocus()
        }


        pictureCount.observe(viewLifecycleOwner) {
            if(it >= PICTURE_SIZE) {
//                setAutoFocus()
                mediaPlayer.start()
                Toast.makeText(requireContext(), "촬영 완료", Toast.LENGTH_SHORT).show()
                pictureCount.value = 0
            }
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()

        // 메인 스레드를 방해하지 않기 위해 카메라 작업은 새로운 스레드 제작 후 해당 스레드에서 실행
        startBackgroundThread()

        // texture 사용 가능한지 확인
        if (binding.texture.isAvailable) {
            // 카메라 열기
            openCamera(binding.texture.width, binding.texture.height)
        } else {
            binding.texture.surfaceTextureListener = surfaceTextureListener
        }
    }

    /**
     * 메인 스레드를 방해하지 않기 위해 카메라 작업은 새로운 스레드 제작 후 해당 스레드에서 실행
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = backgroundThread?.looper?.let { Handler(it) }
    }

    /**
     * 카메라 열기
     */
    private fun openCamera(width: Int, height: Int) {
        val permission =
            activity?.let { ContextCompat.checkSelfPermission(it, Manifest.permission.CAMERA) }
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return
        }
        setUpCameraOutputs(width, height)
        configureTransform(width, height)

        val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            // Wait for camera to open - 2.5 seconds is sufficient
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }

    }

    /**
     * 카메라 권한 요청
     */
    private fun requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    /**
     * 카메라 설정 및 카메라 관련 멤버 변수 초기화
     */
    private fun setUpCameraOutputs(width: Int, height: Int) {
        // 카메라 매니저 얻기 (사용가능한 카메라 장치들 얻어오기)
        val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            // 카메라 중에 조건에 맞는 거 하나를 찾고 return (첫번째부터 확인 후, 조건이 맞지 않을 continue)
            for (cameraId in manager.cameraIdList) {
                // 카메라 정보 알아내기
                val characteristics = manager.getCameraCharacteristics(cameraId)

                // 렌즈 정보 알아낸 후, 후면 카메라가 아닐 시 continue (이를 통해 처음 켜지는 카메라는 무조건 적으로 후면 카메라)
                val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (cameraDirection != null &&
                    cameraDirection == CameraCharacteristics.LENS_FACING_FRONT
                ) {
                    continue
                }

                // 스트림 구성 맵이 null인 경우 continue =>
                val map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                ) ?: continue

                // 이미지 해상도 및 포맷 설정 -> 가장 높은(좋은) 해상도 선택
                val largest = Collections.max(
                    Arrays.asList(*map.getOutputSizes(ImageFormat.JPEG)), CompareSizesByArea()
                )
                // 선택된 해상도로 ImageReader 설정 -> JPEG으로, width, height는 가장 크게
                //TODO: MaxImages : 최대 저장 개수 알아보기
                imageReader = ImageReader.newInstance(
                    largest.width, largest.height,
                    ImageFormat.JPEG, /*maxImages*/ 10
                ).apply {
                    setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
                }

                // 디바이스에 따른 센서 방향 고려해서 preview 크기 결정
                val displayRotation = activity?.windowManager?.defaultDisplay?.rotation

                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
                val swappedDimensions = areDimensionsSwapped(displayRotation!!)

                val displaySize = Point()
                activity?.windowManager?.defaultDisplay?.getSize(displaySize)
                val rotatedPreviewWidth = if (swappedDimensions) height else width
                val rotatedPreviewHeight = if (swappedDimensions) width else height
                var maxPreviewWidth = if (swappedDimensions) displaySize.y else displaySize.x
                var maxPreviewHeight = if (swappedDimensions) displaySize.x else displaySize.y

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) maxPreviewWidth = MAX_PREVIEW_WIDTH
                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) maxPreviewHeight = MAX_PREVIEW_HEIGHT

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                previewSize = chooseOptimalSize(
                    map.getOutputSizes(SurfaceTexture::class.java),
                    rotatedPreviewWidth, rotatedPreviewHeight,
                    maxPreviewWidth, maxPreviewHeight,
                    largest
                )

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    binding.texture.setAspectRatio(previewSize.width, previewSize.height)
                } else {
                    binding.texture.setAspectRatio(previewSize.height, previewSize.width)
                }

                // Check if the flash is supported.
                flashSupported =
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                this.cameraId = cameraId

                // 렌즈 LENS_INFO_MINIMUM_FOCUS_DISTANCE 값 알아오기 (최대값 = 변수 등록) : 최대 초점 거리
                minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)!!

                return
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: NullPointerException) {
//            ErrorDialog.newInstance(getString(R.string.camera_error))
//                .show(childFragmentManager, FRAGMENT_DIALOG)
        }
    }

    /**
     * 카메라 회전 설정 및 textureView 설정
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        activity ?: return
        val rotation = activity?.windowManager?.defaultDisplay?.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            val scale = max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            with(matrix) {
                setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        binding.texture.setTransform(matrix)
    }

    /**
     * 카메라 프리뷰 생성
     */
    private fun createCameraPreviewSession() {
        try {
            val texture = binding.texture.surfaceTexture

            // We configure the size of default buffer to be the size of camera preview we want.
            texture?.setDefaultBufferSize(previewSize.width, previewSize.height)

            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )
            previewRequestBuilder.addTarget(surface)

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice?.createCaptureSession(Arrays.asList(surface, imageReader?.surface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        // The camera is already closed
                        if (cameraDevice == null) return

                        // When the session is ready, we start displaying the preview.
                        captureSession = cameraCaptureSession
                        try {
                            // Auto focus should be continuous for camera preview.
                            previewRequestBuilder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            // Flash is automatically enabled when necessary.
//                            setAutoFlash(previewRequestBuilder)

                            // Finally, we start displaying the camera preview.
                            previewRequest = previewRequestBuilder.build()
                            captureSession?.setRepeatingRequest(
                                previewRequest,
                                captureCallback, backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, e.toString())
                        }

                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
//                        activity.showToast("Failed")
                    }
                }, null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    /**
     * 사진 촬영 전, 초점 고정시키기 위해 lock 거는 함수
     */
    private fun lockFocus() {
        try {
            if(previewRequestBuilder.get(CaptureRequest.CONTROL_AF_MODE) != CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                Log.d("렌즈 초점 결과", "lockFocus : " + previewRequestBuilder.get(CaptureRequest.CONTROL_AF_MODE).toString() )
            }
                // This is how to tell the camera to lock focus.
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START)
                // Tell #captureCallback to wait for the lock.
                state = STATE_WAITING_LOCK
                captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                    backgroundHandler)


        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    /**
     * 사진 촬영 후, 초점 고정시켰던 lock 푸는 함수
     */
    private fun unlockFocus() {
        try {
            // Reset the auto-focus trigger
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
//            setAutoFlash(previewRequestBuilder)
            captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                backgroundHandler)
            // After this, the camera will go back to the normal state of preview.
            state = STATE_PREVIEW
            captureSession?.setRepeatingRequest(previewRequest, captureCallback,
                backgroundHandler)
        } catch (e: CameraAccessException) {

        }
    }

    /**
     * 실제 사진 촬영을 하는 함수 (이미지 캡처)
     */
    private fun captureStillPicture(value: ArrayDeque<Float>?) {
        Log.d("렌즈 초점 결과", "captureStillPicture")
        try {
            if (activity == null || cameraDevice == null) return
            val rotation = activity?.windowManager?.defaultDisplay?.rotation

            // This is the CaptureRequest.Builder that we use to take a picture.
            val captureBuilder = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            )?.apply {
                addTarget(imageReader?.surface!!)

                // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
                // We have to take that into account and rotate JPEG properly.
                // For devices with orientation of 90, we return our mapping from ORIENTATIONS.
                // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
                set(CaptureRequest.JPEG_ORIENTATION, 90)

                // Use the same AE and AF modes as the preview.

                if(value != null) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                }
                else {
                    set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
            }?.also {
//                setAutoFlash(it)
            }


            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {

                    val distanceResult =  result.get(CaptureResult.LENS_FOCUS_DISTANCE)

//                    Toast.makeText(requireContext(), "렌즈 초점 결과: " +distanceResult.toString(), Toast.LENGTH_SHORT).show()
//                    Log.d("렌즈 초점 결과", distanceResult.toString())
                    if(value != null && distanceResult == 10f) {
                        setAutoFocus()
                    }
                    else {
                        unlockFocus()
                    }

                }
            }

            captureSession?.apply {
                stopRepeating()
                abortCaptures()

                val captureRequestList = mutableListOf<CaptureRequest>()

                // 자동 초섬
                if(value == null) {
                    for (i in 0 until PICTURE_SIZE) {
                        captureBuilder?.let { captureRequestList.add(it.build()) }
                    }
                }

                // 수동 초점 (디스턴스 촬영)
                else {
                    while (value.isNotEmpty()) {
                        captureBuilder?.set(CaptureRequest.LENS_FOCUS_DISTANCE, value.poll())
                        captureBuilder?.let { captureRequestList.add(it.build()) }
                    }
                }

                // Capture the burst of images
                captureBurst(captureRequestList, captureCallback, null)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    private fun getJpegOrientation(
        c: CameraCharacteristics,
        deviceOrientation: Int,
    ): Int {
        var deviceOrientation = deviceOrientation
        if (deviceOrientation == OrientationEventListener.ORIENTATION_UNKNOWN) return 0
        val sensorOrientation =
            c.get(CameraCharacteristics.SENSOR_ORIENTATION)!!

        // Round device orientation to a multiple of 90
        deviceOrientation = (deviceOrientation + 45) / 90 * 90

        // Reverse device orientation for front-facing cameras
        val facingFront =
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        if (facingFront) deviceOrientation = -deviceOrientation

        // Calculate desired JPEG orientation relative to camera orientation to make
        // the image upright relative to the device orientation
        return (sensorOrientation + deviceOrientation + 360) % 360
    }


    /**
     * 사진 촬영 준비하는 함수 (precaputre 대기 위한 설정)
     */
    private fun runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            // Tell #captureCallback to wait for the precapture sequence to be set.
            state = STATE_WAITING_PRECAPTURE
            captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    /**
     * 프리뷰 초점 수동으로 변경하고 초점 설정해서 촬영
     */
    private fun setAutoFocus() {
        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        previewRequest = previewRequestBuilder.build()

        state = STATE_PREVIEW

        captureSession?.setRepeatingRequest(
            previewRequest,
            captureCallback, backgroundHandler
        )
    }

    /**
     * 프리뷰 초점 수동으로 변경하고 초점 설정해서 촬영
     */
    private fun setFocusDistance(value: ArrayDeque<Float>) {

        state = STATE_PICTURE_TAKEN

        var focusDistanceValue = value.peek()
        if (focusDistanceValue > minimumFocusDistance) {
            focusDistanceValue = minimumFocusDistance
        }

        previewRequestBuilder.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_OFF
        )
        previewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistanceValue)

        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {

                // 렌즈 현재 상태 알아낼 수 있음
                val lensState = result.get(CaptureResult.LENS_STATE)

                if (lensState != null) {
                    // 렌즈가 정지된 상태입니다. 초점이 안정되어 있을 가능성이 높습니다.
                    if (lensState == CaptureResult.LENS_STATE_STATIONARY) {
                        var distanceValue = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                        // 내가 지정한 바운더리 안에 있는지 확인
                        if (distanceValue != null && distanceValue > value.peek() - 0.1f
                            && distanceValue < value.peek() + 0.1f
                        ) {
                            Log.d(
                                "렌즈 초점 거리",
                                "렌즈 초점거리 ${result.get(CaptureResult.LENS_FOCUS_DISTANCE)}"
                            )
                            captureStillPicture(value)
                        }
                        // 렌즈가 이동 중입니다. 초점이 아직 맞춰지지 않았을 가능성이 있습니다.
                    } else if (lensState == CaptureResult.LENS_STATE_MOVING) {
                    }
                }
            }
        }

        previewRequest = previewRequestBuilder.build()
        captureSession?.setRepeatingRequest(
            previewRequest,
            captureCallback, backgroundHandler
        )
    }
private fun setTouchPointDistanceChange(x: Float, y: Float) {

    val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    // 카메라 정보 알아내기
    val characteristics = manager.getCameraCharacteristics(cameraId)

    val sensorArraySize: Rect? =
        characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

    // 초점을 주고 싶은 반경 설정
    val halfTouchWidth = 150
    val halfTouchHeight = 150

    val focusAreaTouch = MeteringRectangle(
        max(
            (y * (sensorArraySize!!.width() / binding.texture.height) - halfTouchWidth).toInt(), 0
        ),
        max(
            ((binding.texture.width - x) * (sensorArraySize.height() / binding.texture.width) - halfTouchHeight).toInt(),
            0
        ),

        halfTouchWidth * 2,
        halfTouchHeight * 2,
        MeteringRectangle.METERING_WEIGHT_MAX - 1
    )

    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            //the focus trigger is complete -
            //resume repeating (preview surface will get frames), clear AF trigger
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, null)
            captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, null)

        }
    }

    // 모든 캡처 정보를 삭제
    captureSession?.stopRepeating()

    // 초점 변경을 위한 AF 모드 off
    previewRequestBuilder.set(
        CaptureRequest.CONTROL_AF_TRIGGER,
        CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
    )
    previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
    captureSession?.capture(previewRequestBuilder.build(), captureCallback, backgroundHandler)

    // 터치 위치로 초점 변경
    previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusAreaTouch))

    // AF 모드 다시 설정 - 안하면 초점 변경 안됨
    previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
    previewRequestBuilder.set(
        CaptureRequest.CONTROL_AF_TRIGGER,
        CameraMetadata.CONTROL_AF_TRIGGER_START
    )
    previewRequestBuilder.setTag("FOCUS_TAG"); //we'll capture this later for resuming the preview

    //then we ask for a single request (not repeating!)
    captureSession?.capture(previewRequestBuilder.build(), captureCallback, backgroundHandler);

}


    companion object {

        /**
         * Conversion from screen rotation to JPEG orientation.
         */
        private val ORIENTATIONS = SparseIntArray()
        private val FRAGMENT_DIALOG = "dialog"

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        /**
         * Tag for the [Log].
         */
        private val TAG = "Camera2BasicFragment"

        /**
         * Camera state: Showing camera preview.
         */
        private val STATE_PREVIEW = 0

        /**
         * Camera state: Waiting for the focus to be locked.
         */
        private val STATE_WAITING_LOCK = 1

        /**
         * Camera state: Waiting for the exposure to be precapture state.
         */
        private val STATE_WAITING_PRECAPTURE = 2

        /**
         * Camera state: Waiting for the exposure state to be something other than precapture.
         */
        private val STATE_WAITING_NON_PRECAPTURE = 3

        /**
         * Camera state: Picture was taken.
         */
        private val STATE_PICTURE_TAKEN = 4

        private var PICTURE_SIZE = 1

        /**
         * Max preview width that is guaranteed by Camera2 API
         */
        private val MAX_PREVIEW_WIDTH = 1920

        /**
         * Max preview height that is guaranteed by Camera2 API
         */
        private val MAX_PREVIEW_HEIGHT = 1080
    }

    // 회전에 관한....!
    private fun areDimensionsSwapped(displayRotation: Int): Boolean {
        var swappedDimensions = false
        when (displayRotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                if (sensorOrientation == 90 || sensorOrientation == 270) {
                    swappedDimensions = true
                }
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                if (sensorOrientation == 0 || sensorOrientation == 180) {
                    swappedDimensions = true
                }
            }
            else -> {
                Log.e(TAG, "Display rotation is invalid: $displayRotation")
            }
        }
        return swappedDimensions
    }

    private fun chooseOptimalSize(
        choices: Array<Size>,
        textureViewWidth: Int,
        textureViewHeight: Int,
        maxWidth: Int,
        maxHeight: Int,
        aspectRatio: Size,
    ): Size {

        // Collect the supported resolutions that are at least as big as the preview Surface
        val bigEnough = ArrayList<Size>()
        // Collect the supported resolutions that are smaller than the preview Surface
        val notBigEnough = ArrayList<Size>()
        val w = aspectRatio.width
        val h = aspectRatio.height
        for (option in choices) {
            if (option.width <= maxWidth && option.height <= maxHeight &&
                option.height == option.width * h / w
            ) {
                if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                    bigEnough.add(option)
                } else {
                    notBigEnough.add(option)
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size > 0) {
            return Collections.min(bigEnough, CompareSizesByArea())
        } else if (notBigEnough.size > 0) {
            return Collections.max(notBigEnough, CompareSizesByArea())
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size")
            return choices[0]
        }
    }

    internal class CompareSizesByArea : Comparator<Size> {
        // We cast here to ensure the multiplications won't overflow
        override fun compare(lhs: Size, rhs: Size) =
            Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)

    }

}