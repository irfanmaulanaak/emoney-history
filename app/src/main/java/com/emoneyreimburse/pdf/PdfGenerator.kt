package com.emoneyreimburse.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.emoneyreimburse.model.Transaction
import com.itextpdf.text.BaseColor
import com.itextpdf.text.Document
import com.itextpdf.text.Element
import com.itextpdf.text.Font
import com.itextpdf.text.Image
import com.itextpdf.text.Paragraph
import com.itextpdf.text.pdf.PdfPCell
import com.itextpdf.text.pdf.PdfPTable
import com.itextpdf.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class PdfGenerator(private val context: Context) {
    
    companion object {
        private const val PDF_DIR = "pdfs"
    }
    
    fun generatePdf(
        userName: String,
        transactions: List<Transaction>,
        fileName: String = "Reimburse_${System.currentTimeMillis()}.pdf"
    ): Result<File> {
        return try {
            val pdfDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), PDF_DIR)
            if (!pdfDir.exists()) {
                pdfDir.mkdirs()
            }
            
            val pdfFile = File(pdfDir, fileName)
            val document = Document()
            PdfWriter.getInstance(document, FileOutputStream(pdfFile))
            document.open()
            
            // Title
            val titleFont = Font(Font.FontFamily.HELVETICA, 18f, Font.BOLD, BaseColor(21, 101, 192))
            val title = Paragraph("Laporan Reimburse Parkir", titleFont)
            title.alignment = Element.ALIGN_CENTER
            document.add(title)
            document.add(Paragraph(" "))
            
            // User Info
            val infoFont = Font(Font.FontFamily.HELVETICA, 12f, Font.NORMAL)
            val boldFont = Font(Font.FontFamily.HELVETICA, 12f, Font.BOLD)
            
            document.add(Paragraph("Nama: $userName", boldFont))
            document.add(Paragraph("Tanggal Generate: ${getCurrentDate()}", infoFont))
            document.add(Paragraph("Jumlah Transaksi: ${transactions.size}", infoFont))
            document.add(Paragraph(" "))
            
            // Transaction Table
            val table = PdfPTable(4)
            table.widthPercentage = 100f
            table.setWidths(floatArrayOf(3f, 3f, 3f, 2f))
            
            // Header
            val headerColor = BaseColor(21, 101, 192)
            val headerFont = Font(Font.FontFamily.HELVETICA, 11f, Font.BOLD, BaseColor.WHITE)
            
            table.addCell(createHeaderCell("Tanggal & Waktu", headerFont, headerColor))
            table.addCell(createHeaderCell("Lokasi", headerFont, headerColor))
            table.addCell(createHeaderCell("Jenis", headerFont, headerColor))
            table.addCell(createHeaderCell("Nominal", headerFont, headerColor))
            
            // Data rows
            val rowFont = Font(Font.FontFamily.HELVETICA, 10f, Font.NORMAL)
            var totalAmount = 0L
            
            transactions.forEach { transaction ->
                table.addCell(createDataCell(transaction.formattedDateTime(), rowFont))
                table.addCell(createDataCell(transaction.location, rowFont))
                table.addCell(createDataCell(transaction.type.name, rowFont))
                table.addCell(createDataCell(transaction.formattedAmount(), rowFont, Element.ALIGN_RIGHT))
                totalAmount += transaction.amount
            }
            
            document.add(table)
            document.add(Paragraph(" "))
            
            // Total
            val totalFont = Font(Font.FontFamily.HELVETICA, 14f, Font.BOLD, BaseColor(21, 101, 192))
            val totalParagraph = Paragraph("Total Reimburse: Rp ${formatCurrency(totalAmount)}", totalFont)
            totalParagraph.alignment = Element.ALIGN_RIGHT
            document.add(totalParagraph)
            document.add(Paragraph(" "))
            
            // Transaction Cards (Screenshot style)
            val cardTitleFont = Font(Font.FontFamily.HELVETICA, 14f, Font.BOLD, BaseColor(33, 33, 33))
            document.add(Paragraph("Bukti Transaksi:", cardTitleFont))
            document.add(Paragraph(" "))
            
            transactions.forEachIndexed { index, transaction ->
                val cardBitmap = createTransactionCardBitmap(transaction, index + 1)
                val cardImage = bitmapToPdfImage(cardBitmap)
                cardImage.alignment = Element.ALIGN_CENTER
                document.add(cardImage)
                document.add(Paragraph(" "))
            }
            
            // Footer
            val footerFont = Font(Font.FontFamily.HELVETICA, 9f, Font.ITALIC, BaseColor(117, 117, 117))
            val footer = Paragraph("Dokumen ini digenerate otomatis oleh aplikasi Emoney Reimburse", footerFont)
            footer.alignment = Element.ALIGN_CENTER
            document.add(footer)
            
            document.close()
            
            Result.success(pdfFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun createTransactionCardBitmap(transaction: Transaction, number: Int): Bitmap {
        val width = 800
        val height = 300
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Background
        val paint = Paint()
        paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        
        // Card background
        val cardPaint = Paint()
        cardPaint.color = Color.parseColor("#F5F5F5")
        val rect = RectF(20f, 20f, width - 20f, height - 20f)
        canvas.drawRoundRect(rect, 20f, 20f, cardPaint)
        
        // Header bar
        val headerPaint = Paint()
        headerPaint.color = Color.parseColor("#1565C0")
        val headerRect = RectF(20f, 20f, width - 20f, 80f)
        canvas.drawRoundRect(headerRect, 20f, 20f, headerPaint)
        // Redraw bottom corners to make it square at bottom
        canvas.drawRect(20f, 50f, width - 20f, 80f, headerPaint)
        
        // Header text
        val textPaint = Paint()
        textPaint.color = Color.WHITE
        textPaint.textSize = 36f
        textPaint.isFakeBoldText = true
        canvas.drawText("Transaksi #$number", 50f, 65f, textPaint)
        
        // Amount
        val amountPaint = Paint()
        amountPaint.color = Color.parseColor("#2E7D32")
        amountPaint.textSize = 48f
        amountPaint.isFakeBoldText = true
        canvas.drawText(transaction.formattedAmount(), 50f, 160f, amountPaint)
        
        // Details
        val detailPaint = Paint()
        detailPaint.color = Color.parseColor("#212121")
        detailPaint.textSize = 28f
        canvas.drawText(transaction.location, 50f, 210f, detailPaint)
        
        val datePaint = Paint()
        datePaint.color = Color.parseColor("#757575")
        datePaint.textSize = 24f
        canvas.drawText(transaction.formattedDateTime(), 50f, 250f, datePaint)
        
        return bitmap
    }
    
    private fun bitmapToPdfImage(bitmap: Bitmap): Image {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val image = Image.getInstance(stream.toByteArray())
        image.scaleToFit(500f, 200f)
        return image
    }
    
    private fun createHeaderCell(text: String, font: Font, backgroundColor: BaseColor): PdfPCell {
        val cell = PdfPCell(Paragraph(text, font))
        cell.backgroundColor = backgroundColor
        cell.horizontalAlignment = Element.ALIGN_CENTER
        cell.verticalAlignment = Element.ALIGN_MIDDLE
        cell.setPadding(8f)
        return cell
    }
    
    private fun createDataCell(text: String, font: Font, alignment: Int = Element.ALIGN_LEFT): PdfPCell {
        val cell = PdfPCell(Paragraph(text, font))
        cell.horizontalAlignment = alignment
        cell.verticalAlignment = Element.ALIGN_MIDDLE
        cell.setPadding(6f)
        return cell
    }
    
    private fun getCurrentDate(): String {
        return SimpleDateFormat("dd MMMM yyyy HH:mm", Locale("id", "ID")).format(Date())
    }
    
    private fun formatCurrency(amount: Long): String {
        return NumberFormat.getNumberInstance(Locale("id", "ID")).format(amount)
    }
    
    fun sharePdf(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Laporan Reimburse Parkir")
            putExtra(Intent.EXTRA_TEXT, "Berikut laporan reimburse parkir saya.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        val chooser = Intent.createChooser(intent, "Bagikan PDF")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
