package me.heartalborada

import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel

class Utils {
    companion object {
        fun gaussianBlur(image: BufferedImage, radius: Int): BufferedImage {
            val size = radius * 2 + 1
            val weight = 1.0f / (size * size)
            val kernelData = FloatArray(size * size) { weight }
            val kernel = Kernel(size, size, kernelData)
            val convolveOp = ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null)

            val blurredImage = BufferedImage(image.width, image.height, image.type)
            val g2d: Graphics2D = blurredImage.createGraphics()
            g2d.drawImage(image, 0, 0, null)
            g2d.dispose()

            return convolveOp.filter(blurredImage, null)
        }
    }
}