import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.Image
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Saves a JPEG [Image] into the gallery.
 */
internal class ImageSaver(
    /**
     * The JPEG image
     */
    private val image: Image,
    /**
     * The context to access file operations and content resolver.
     */
    private val context: Context,

    private var pictureCount: MutableLiveData<Int>,

    ) : Runnable {

    override fun run() {


//        CoroutineScope(Dispatchers.IO).launch {
            val bitmap = imageToBitmap(image) // Image to Bitmap
            image.close() // Close the Image

            saveImageToGallery(bitmap) // Save Bitmap to Gallery
//        }
        CoroutineScope(Dispatchers.Main).launch {
            pictureCount.value = pictureCount.value!! + 1
        }
    }

    // Convert Image to Bitmap
    private fun imageToBitmap(image: Image): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val matrix = bitmapRotation(bytes , 1)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun bitmapRotation(byteArray: ByteArray, value: Int) : Matrix {
        val inputStream: InputStream = ByteArrayInputStream(byteArray)

        val exif = ExifInterface(inputStream)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f * value)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f * value)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f * value)
            else -> matrix.postRotate(0f)
        }
        return matrix
    }

    // Save Bitmap to Gallery
    private fun saveImageToGallery(bitmap: Bitmap) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, imageFileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM)
//            }
        }

        val contentResolver: ContentResolver = context.contentResolver
        val uri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            val outputStream: OutputStream? = contentResolver.openOutputStream(it)
            outputStream?.use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
//                context.showToast("Image saved to Gallery!")
            }
        }
    }

    private fun saveImageByteArray(image: Image) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val imageName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
//        val imageName = "MyImage.jpg" // 저장할 이미지 파일 이름

        val imagePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            .toString() + "/" + imageName

        val fos = FileOutputStream(imagePath)
        fos.write(bytes) // ByteArray의 이미지 데이터를 파일에 쓰기

        fos.close()
    }
}
