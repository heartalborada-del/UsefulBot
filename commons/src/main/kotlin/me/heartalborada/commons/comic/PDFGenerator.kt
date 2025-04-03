package me.heartalborada.commons.comic

import org.apache.commons.io.FileUtils
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File


class PDFGenerator {
    companion object {
        fun generatePDF(
            images: List<File>,
            signatureText: String = "Null",
            pdfFile: File = File("output.pdf"),
            tempDir: File? = null
        ) {
            val cache = MemoryUsageSetting.setupMixed(Runtime.getRuntime().maxMemory() / 4)
            if (tempDir != null) cache.tempDir = tempDir

            val doc = PDDocument(cache.streamCache)

            // 设置页面边距（单位: 点，1英寸=72点）
            val marginLeft = 10f
            val marginRight = 10f
            val marginTop = 14f
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

                //TODO NO WEBP SUPPORT
                val image = PDImageXObject.createFromFileByContent(it, doc)

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
                val pdType1Font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

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