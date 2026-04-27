package com.emoneyreimburse.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.emoneyreimburse.model.Transaction
import com.emoneyreimburse.model.TransactionType
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

class OcrTransactionExtractor(private val context: Context) {
    
    companion object {
        private const val TAG = "OcrExtractor"
        
        // Indonesian currency patterns
        private val AMOUNT_PATTERN = Pattern.compile(
            "(Rp[\\s.]?)?([0-9]{1,3}(?:[.,][0-9]{3})+(?:[.,][0-9]{2})?|[0-9]+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Date patterns (Indonesian format)
        private val DATE_PATTERNS = listOf(
            Pattern.compile("(\\d{2})[/-](\\d{2})[/-](\\d{4})"),  // DD/MM/YYYY or DD-MM-YYYY
            Pattern.compile("(\\d{4})[/-](\\d{2})[/-](\\d{2})"),  // YYYY/MM/DD
            Pattern.compile("(\\d{2})[/-](\\d{2})[/-](\\d{2})"),   // DD/MM/YY
        )
        
        // Time pattern
        private val TIME_PATTERN = Pattern.compile("(\\d{2}):(\\d{2})")
        
        // Keywords that indicate parking transactions
        private val PARKING_KEYWORDS = listOf(
            "parkir", "parking", "park", "area parkir", "tempat parkir",
            "basement", "lantai", " gedung ", "mall", "plaza", "center"
        )
        
        // Keywords that indicate toll transactions  
        private val TOLL_KEYWORDS = listOf(
            "tol", "toll", "jalan tol", "gerbang tol", "gt", "gate"
        )
        
        // Keywords that indicate top-up
        private val TOPUP_KEYWORDS = listOf(
            "top up", "topup", "isi ulang", "reload", "pembelian saldo"
        )
    }
    
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    suspend fun extractFromImage(uri: Uri): OcrResult {
        return try {
            val bitmap = loadBitmapFromUri(uri)
            if (bitmap == null) {
                return OcrResult.Error("Gagal memuat gambar")
            }
            
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(image).await()
            
            Log.d(TAG, "OCR Raw Text:\n${visionText.text}")
            
            val transactions = parseTransactionsFromText(visionText.text)
            
            if (transactions.isEmpty()) {
                OcrResult.Error(
                    "Tidak dapat menemukan transaksi dalam gambar.\n\n" +
                    "Tips: Pastikan screenshot menunjukkan:\n" +
                    "• Nominal transaksi (contoh: Rp 5.000)\n" +
                    "• Tanggal dan waktu\n" +
                    "• Lokasi/merchant"
                )
            } else {
                OcrResult.Success(transactions)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "OCR Error", e)
            OcrResult.Error("Error OCR: ${e.message}")
        }
    }
    
    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap", e)
            null
        }
    }
    
    private fun parseTransactionsFromText(text: String): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val lines = text.split("\n")
        
        var currentDate = ""
        var currentTime = ""
        var currentLocation = ""
        var currentAmount = 0L
        
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.isBlank()) continue
            
            // Try to extract date
            val date = extractDate(line)
            if (date != null) {
                currentDate = date
            }
            
            // Try to extract time
            val time = extractTime(line)
            if (time != null) {
                currentTime = time
            }
            
            // Try to extract amount
            val amount = extractAmount(line)
            if (amount != null && amount > 0) {
                currentAmount = amount
                
                // Look for location in surrounding lines
                currentLocation = findLocation(lines, i)
                
                // Determine transaction type
                val type = determineTransactionType(line + " " + currentLocation)
                
                // If we have enough info, create transaction
                if (currentDate.isNotEmpty() && currentAmount > 0) {
                    transactions.add(
                        Transaction(
                            id = "OCR-${System.currentTimeMillis()}-${transactions.size}",
                            date = currentDate,
                            time = currentTime.ifEmpty { "00:00" },
                            location = currentLocation.ifEmpty { "Transaksi" },
                            amount = currentAmount,
                            type = type
                        )
                    )
                    
                    // Reset for next transaction
                    currentAmount = 0
                    currentLocation = ""
                }
            }
        }
        
        return transactions
    }
    
    private fun extractDate(line: String): String? {
        for (pattern in DATE_PATTERNS) {
            val matcher = pattern.matcher(line)
            if (matcher.find()) {
                return try {
                    val part1 = matcher.group(1)!!
                    val part2 = matcher.group(2)!!
                    val part3 = matcher.group(3)!!
                    
                    // Try to determine format
                    if (part3.length == 4) {
                        // DD/MM/YYYY
                        String.format("%s-%s-%s", part3, part2, part1)
                    } else if (part1.length == 4) {
                        // YYYY/MM/DD
                        String.format("%s-%s-%s", part1, part2, part3)
                    } else {
                        // DD/MM/YY
                        val year = if (part3.toInt() > 50) "19$part3" else "20$part3"
                        String.format("%s-%s-%s", year, part2, part1)
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
        return null
    }
    
    private fun extractTime(line: String): String? {
        val matcher = TIME_PATTERN.matcher(line)
        return if (matcher.find()) {
            String.format("%s:%s", matcher.group(1), matcher.group(2))
        } else null
    }
    
    private fun extractAmount(line: String): Long? {
        // Look for currency indicators first
        val lowerLine = line.lowercase()
        
        // Check if line contains currency indicator or looks like an amount
        val hasCurrency = lowerLine.contains("rp") || 
                         lowerLine.contains("rp.") ||
                         lowerLine.matches(Regex(".*\\d+[.,]?\\d*.*"))
        
        if (!hasCurrency) return null
        
        // Extract number
        val numberPattern = Pattern.compile("[0-9]{1,3}(?:[.,][0-9]{3})*|[0-9]+")
        val matcher = numberPattern.matcher(line)
        
        var maxAmount = 0L
        while (matcher.find()) {
            val numStr = matcher.group().replace(".", "").replace(",", "")
            val num = numStr.toLongOrNull() ?: 0
            
            // Filter realistic parking amounts (1,000 - 1,000,000)
            if (num in 1000..1000000 && num > maxAmount) {
                maxAmount = num
            }
        }
        
        return if (maxAmount > 0) maxAmount else null
    }
    
    private fun findLocation(lines: List<String>, currentIndex: Int): String {
        // Check current line and surrounding lines for location info
        val searchRange = 2 // Check 2 lines before and after
        val locationIndicators = listOf("di", "at", "lokasi", "location", "merchant", "tempat")
        
        for (offset in -searchRange..searchRange) {
            val index = currentIndex + offset
            if (index in lines.indices) {
                val line = lines[index].trim()
                
                // Skip lines that are clearly dates/amounts/times
                if (extractDate(line) != null) continue
                if (extractTime(line) != null) continue
                if (extractAmount(line) != null) continue
                
                // Look for location keywords
                for (keyword in locationIndicators) {
                    if (line.lowercase().contains(keyword)) {
                        return line.replace(keyword, "", ignoreCase = true).trim()
                    }
                }
                
                // If line contains parking/toll keywords, use it
                if (PARKING_KEYWORDS.any { line.lowercase().contains(it) } ||
                    TOLL_KEYWORDS.any { line.lowercase().contains(it) }) {
                    return line
                }
            }
        }
        
        return "Parkir"
    }
    
    private fun determineTransactionType(context: String): TransactionType {
        val lower = context.lowercase()
        return when {
            PARKING_KEYWORDS.any { lower.contains(it) } -> TransactionType.PARKING
            TOLL_KEYWORDS.any { lower.contains(it) } -> TransactionType.TOLL
            TOPUP_KEYWORDS.any { lower.contains(it) } -> TransactionType.TOPUP
            else -> TransactionType.UNKNOWN
        }
    }
}

sealed class OcrResult {
    data class Success(val transactions: List<Transaction>) : OcrResult()
    data class Error(val message: String) : OcrResult()
}
