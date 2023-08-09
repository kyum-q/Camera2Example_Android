package com.example.camera2ex

import android.content.Context
import android.graphics.*
import android.view.LayoutInflater
import android.widget.Button
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector

class ObjectDetectionModule(
   val context: Context
) {

    private lateinit var detectionResult: List<DetectionResult>
    private lateinit var customObjectDetector: ObjectDetector
    private lateinit var faceDetector: FaceDetector
    private var isGetDetectionResult = false
    var detectionResultIndex = 0
    init {
        setDetecter()
    }

    private fun setDetecter() {
        // High-accuracy landmark detection and face classification
        val highAccuracyOpts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()

        faceDetector = FaceDetection.getClient(highAccuracyOpts)

        // Initialize the detector object
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setMaxResults(5)          // 최대 결과 (모델에서 감지해야 하는 최대 객체 수)
            .setScoreThreshold(0.5f)    // 점수 임계값 (감지된 객체를 반환하는 객체 감지기의 신뢰도)
            .build()
        customObjectDetector = ObjectDetector.createFromFileAndOptions(
            context,
            "lite-model_efficientdet_lite0_detection_metadata_1.tflite",
            options
        )

    }

    /**
     * runObjectDetection(bitmap: Bitmap)
     *      TFLite Object Detection function
     *      사진 속 객체를 감지하고, 감지된 객체에 boundingBox를 표시해 반환한다.
     */
    fun runObjectDetection(bitmap: Bitmap): Bitmap {

        if(!isGetDetectionResult) {
            // Object Detection
            detectionResult = getObjectDetection(bitmap)
        }

        // ObjectDetection 결과(bindingBox) 그리기
        val objectDetectionResult =
            drawDetectionResult(bitmap, detectionResult)

        return objectDetectionResult!!
    }

    /**
     * getObjectDetection(bitmap: Bitmap):
     *         ObjectDetection 결과(bindingBox)를 반환한다.
     */
    private fun getObjectDetection(bitmap: Bitmap): List<DetectionResult> {
        // Step 1: Create TFLite's TensorImage object
        val image = TensorImage.fromBitmap(bitmap)

        // Step 2: Feed given image to the detector
        val results = customObjectDetector.detect(image)

        // Step 3: Parse the detection result and show it
        val resultToDisplay = results.map {
            // Get the top-1 category and craft the display text
            val category = it.categories.first()
            val text = "${category.label}"

            // Create a data object to display the detection result
            DetectionResult(it.boundingBox, text)
        }
        return resultToDisplay
    }

    /**
     * drawDetectionResult(bitmap: Bitmap, detectionResults: List<DetectionResult>
     *      객체 분석 된 boundingBox에 맞춰 이미지 위에 표시한 후 표시된 이미지를 반환한다.
     */
    private fun drawDetectionResult(
        bitmap: Bitmap,
        detectionResults: List<DetectionResult>
    ): Bitmap? {
        val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outputBitmap)
        val pen = Paint()
        pen.textAlign = Paint.Align.LEFT

        detectionResults.forEach {
            // draw bounding box
            pen.color = Color.parseColor("#B8C5BB")
            pen.strokeWidth = 10F
            pen.style = Paint.Style.STROKE
            val box = it.boundingBox
            canvas.drawRoundRect(box, 10F, 10F, pen)
        }
        return outputBitmap
    }

    fun getDetectionResult() : DetectionResult? {

        if(detectionResult.size > detectionResultIndex) {
            isGetDetectionResult = true
            return detectionResult[detectionResultIndex++]
        }
        else {
            resetDetectionResult()
            return null
        }
    }

    fun resetDetectionResult() {
        isGetDetectionResult = false
        detectionResultIndex = 0
    }

    fun getIsDetectionStop() : Boolean {
        return isGetDetectionResult
    }

    fun getIsDetectionSize() : Int {
        return detectionResult.size
    }
}
data class DetectionResult(val boundingBox: RectF, val text: String)
