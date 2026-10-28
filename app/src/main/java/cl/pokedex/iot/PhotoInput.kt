package cl.pokedex.iot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Reduce la foto antes de enviarla y elimina metadatos (incluida ubicación). */
fun preparePhoto(context: Context, uri: Uri): ByteArray {
    val raw = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 20 * 1024 * 1024) { "La foto supera los 20 MB." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("No se pudo leer la foto.")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Formato de imagen no compatible." }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("No se pudo abrir la imagen.")
    val exif = ExifInterface(ByteArrayInputStream(raw))
    val matrix = Matrix().apply {
        if (exif.isFlipped) postScale(-1f, 1f)
        postRotate(exif.rotationDegrees.toFloat())
    }
    val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    return try {
        ByteArrayOutputStream().use { output -> upright.compress(Bitmap.CompressFormat.JPEG, 85, output); output.toByteArray() }
    } finally {
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
    }
}
