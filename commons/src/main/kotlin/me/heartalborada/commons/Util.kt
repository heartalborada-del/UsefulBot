package me.heartalborada.commons

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.util.*
import javax.imageio.ImageIO
import kotlin.random.Random

class Util {
    companion object {
        private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        fun randomString(length: Int = 10): String {
            val sb = StringBuilder(length)
            for (i in 0..<length) {
                val randomIndex = Random.nextInt(CHARS.length)
                sb.append(CHARS[randomIndex])
            }
            return sb.toString()
        }
        fun resampleImage(img: BufferedImage, scale: Double): BufferedImage {
            val newWidth = (img.width * scale).toInt()
            val newHeight = (img.height * scale).toInt()
            val resized = BufferedImage(newWidth, newHeight, img.type)
            val g2d: Graphics2D = resized.createGraphics()
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2d.drawImage(img, 0, 0, newWidth, newHeight, null)
            g2d.dispose()
            return resized
        }

        fun bufferedImageToBase64(image: BufferedImage, format: String = "png"): String {
            val outputStream = ByteArrayOutputStream()
            ImageIO.write(image, format, outputStream)
            val imageBytes = outputStream.toByteArray()
            outputStream.close()
            return Base64.getEncoder().encodeToString(imageBytes)
        }

        // Function to apply a simple 3x3 Gaussian blur.
        fun gaussianBlur(img: BufferedImage): BufferedImage {
            val matrix = floatArrayOf(
                1/16f, 2/16f, 1/16f,
                2/16f, 4/16f, 2/16f,
                1/16f, 2/16f, 1/16f
            )
            val kernel = Kernel(3, 3, matrix)
            val convolveOp = ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null)
            return convolveOp.filter(img, null)
        }
        fun getFileExtensionFromUrl(url: URL): String? {
            return try {
                val path = url.path
                val lastDotIndex = path.lastIndexOf('.')
                if (lastDotIndex != -1 && lastDotIndex < path.length - 1) {
                    path.substring(lastDotIndex + 1)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}