package me.heartalborada.commons.comic

import me.heartalborada.commons.Util.Companion.randomString
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import javax.imageio.ImageIO

class PDFGenerator {
    companion object {
        private val logger: Logger = LoggerFactory.getLogger(PDFGenerator::class.java)
        private val pdType1Font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        fun generatePDF(
            images: List<File>,
            signatureText: String = "Null",
            pdfFile: File,
            tempDir: File = File(System.getProperty("java.io.tmpdir")),
            password: String? = null
        ) {
            val cache = MemoryUsageSetting.setupMixed(Runtime.getRuntime().maxMemory() / 4)
            cache.tempDir = tempDir
            if (!cache.tempDir.exists()) cache.tempDir.mkdirs()
            val doc = PDDocument(cache.streamCache)
            if (password != null)
                doc.protect(StandardProtectionPolicy(randomString(10), password, AccessPermission()))
            // 设置页面边距（单位: 点，1英寸=72点）
            val marginLeft = 10f
            val marginRight = 10f
            val marginTop = 12f
            val marginBottom = 10f

            // 签名文本信息
            val signatureTextSize = 8f

            images.forEach {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                val pageWidth = page.mediaBox.width
                val pageHeight = page.mediaBox.height

                val signatureTextY = pageHeight - marginTop / 2

                val availableWidth = pageWidth - marginLeft - marginRight
                val availableHeight = pageHeight - marginTop - marginBottom

                logger.trace("Read Image: {}", it.path)
                val a = ImageIO.read(it)
                val image = JPEGFactory.createFromImage(doc, a)

                val imageWidth = image.width.toFloat()
                val imageHeight = image.height.toFloat()

                val availableRatio = availableWidth / availableHeight
                val imageRatio = imageWidth / imageHeight

                val scale: Float
                val x: Float
                val y: Float

                if (imageRatio > availableRatio) {
                    scale = availableWidth / imageWidth
                    val scaledHeight = imageHeight * scale
                    y = marginBottom + (availableHeight - scaledHeight) / 2
                    x = marginLeft
                } else {
                    scale = availableHeight / imageHeight
                    val scaledWidth = imageWidth * scale
                    x = marginLeft + (availableWidth - scaledWidth) / 2
                    y = marginBottom
                }

                val stream = PDPageContentStream(doc, page)

                stream.beginText()

                stream.setFont(pdType1Font, signatureTextSize)
                val textWidth = pdType1Font.getStringWidth(signatureText) / 1000 * signatureTextSize
                val textX = (pageWidth - textWidth) / 2
                stream.newLineAtOffset(textX, signatureTextY)
                stream.showText(signatureText)
                stream.endText()

                stream.drawImage(
                    image,
                    x,
                    y,
                    imageWidth * scale,
                    imageHeight * scale
                )
                stream.close()
            }
            doc.save(pdfFile)
            doc.close()
        }
    }
}