import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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
    private val context: Context
) : Runnable {

    override fun run() {
        val bitmap = imageToBitmap(image) // Image to Bitmap
        saveImageToGallery(bitmap) // Save Bitmap to Gallery
        image.close() // Close the Image
    }

    // Convert Image to Bitmap
    private fun imageToBitmap(image: Image): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
}
