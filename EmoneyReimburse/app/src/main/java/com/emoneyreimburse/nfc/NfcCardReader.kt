package com.emoneyreimburse.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.util.Log
import com.emoneyreimburse.model.CardInfo
import com.emoneyreimburse.model.CardType
import com.emoneyreimburse.model.Transaction
import com.emoneyreimburse.model.TransactionType
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class NfcCardReader {
    
    companion object {
        private const val TAG = "NfcCardReader"
        
        // Common AIDs for Indonesian e-money
        private val MANDIRI_AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x03, 0x00, 0x00)
        private val EMONEY_AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x03, 0x20, 0x10)
        private val PPSE = "2PAY.SYS.DDF01".toByteArray()
        
        // APDU Commands
        private val SELECT_PPSE = byteArrayOf(
            0x00, 0xA4.toByte(), 0x04, 0x00, 0x0E,
            0x32, 0x50, 0x41, 0x59, 0x2E, 0x53, 0x59, 0x53,
            0x2E, 0x44, 0x44, 0x46, 0x30, 0x31, 0x00
        )
    }
    
    fun readCard(tag: Tag): CardReadResult {
        return try {
            // Try IsoDep first (EMV/ISO 7816 cards)
            val isoDep = IsoDep.get(tag)
            if (isoDep != null) {
                return readIsoDepCard(isoDep)
            }
            
            // Try Mifare Classic (older e-money cards)
            val mifare = MifareClassic.get(tag)
            if (mifare != null) {
                return readMifareClassicCard(mifare)
            }
            
            CardReadResult.Error("Unsupported card type")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading card", e)
            CardReadResult.Error(e.message ?: "Unknown error")
        }
    }
    
    private fun readIsoDepCard(isoDep: IsoDep): CardReadResult {
        isoDep.connect()
        isoDep.timeout = 5000
        
        return try {
            // Try to select PPSE
            val ppseResponse = isoDep.transceive(SELECT_PPSE)
            Log.d(TAG, "PPSE Response: ${ppseResponse.toHex()}")
            
            if (ppseResponse.isSuccess()) {
                // Try to parse AID from PPSE response and select application
                val aid = extractAid(ppseResponse)
                if (aid != null) {
                    val selectAid = buildSelectAid(aid)
                    val aidResponse = isoDep.transceive(selectAid)
                    Log.d(TAG, "AID Response: ${aidResponse.toHex()}")
                    
                    if (aidResponse.isSuccess()) {
                        // Try to get processing options
                        val gpo = buildGpoCommand()
                        val gpoResponse = isoDep.transceive(gpo)
                        Log.d(TAG, "GPO Response: ${gpoResponse.toHex()}")
                        
                        // Try to read transaction log
                        val transactions = readTransactionLog(isoDep)
                        val cardInfo = extractCardInfo(aidResponse, isoDep)
                        
                        CardReadResult.Success(cardInfo, transactions)
                    } else {
                        CardReadResult.Error("Failed to select application")
                    }
                } else {
                    // Try direct AID selection for known cards
                    tryDirectAidSelection(isoDep)
                }
            } else {
                // Try direct AID selection
                tryDirectAidSelection(isoDep)
            }
        } catch (e: IOException) {
            CardReadResult.Error("Communication error: ${e.message}")
        } finally {
            try { isoDep.close() } catch (e: Exception) { }
        }
    }
    
    private fun tryDirectAidSelection(isoDep: IsoDep): CardReadResult {
        val aids = listOf(MANDIRI_AID, EMONEY_AID)
        
        for (aid in aids) {
            try {
                val selectAid = buildSelectAid(aid)
                val response = isoDep.transceive(selectAid)
                if (response.isSuccess()) {
                    val transactions = readTransactionLog(isoDep)
                    val cardInfo = extractCardInfo(response, isoDep)
                    return CardReadResult.Success(cardInfo, transactions)
                }
            } catch (e: Exception) {
                Log.w(TAG, "AID selection failed", e)
            }
        }
        
        return CardReadResult.Error("Could not select card application")
    }
    
    private fun readTransactionLog(isoDep: IsoDep): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        
        try {
            // EMV Transaction Log Format
            // Get Log Format (Tag 9F4F)
            val getLogFormat = byteArrayOf(0x80.toByte(), 0xCA.toByte(), 0x9F.toByte(), 0x4F, 0x00)
            val logFormatResponse = isoDep.transceive(getLogFormat)
            
            if (logFormatResponse.isSuccess()) {
                // Parse log format and read records
                // SFI for log is typically found in FCI during app selection
                // Common SFI for log = 0x0C (12)
                val logSfi = 0x0C
                
                for (record in 1..10) {
                    val readRecord = byteArrayOf(
                        0x00, 0xB2.toByte(),
                        record.toByte(), 
                        ((logSfi shl 3) or 0x04).toByte(),
                        0x00
                    )
                    
                    val recordResponse = isoDep.transceive(readRecord)
                    if (recordResponse.isSuccess() && recordResponse.size > 2) {
                        val transaction = parseTransactionRecord(recordResponse, record)
                        if (transaction != null) {
                            transactions.add(transaction)
                        }
                    } else {
                        break // No more records
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading transaction log", e)
        }
        
        return transactions
    }
    
    private fun parseTransactionRecord(data: ByteArray, recordNum: Int): Transaction? {
        return try {
            // Standard EMV transaction log format:
            // 9A (3) - Transaction Date (YYMMDD)
            // 9F21 (3) - Transaction Time (HHMMSS)
            // 9F02 (6) - Amount
            // 9F4E (var) - Merchant Name
            
            val tlvData = data.copyOfRange(0, data.size - 2) // Remove SW1 SW2
            
            // Simple parsing - in production, use proper TLV parser
            val date = extractDate(tlvData) ?: "Unknown"
            val time = extractTime(tlvData) ?: "Unknown"
            val amount = extractAmount(tlvData) ?: 0L
            val merchant = extractMerchant(tlvData) ?: "Transaction $recordNum"
            
            Transaction(
                id = "TXN$recordNum",
                date = date,
                time = time,
                location = merchant,
                amount = amount,
                type = detectTransactionType(merchant)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing transaction record", e)
            null
        }
    }
    
    private fun readMifareClassicCard(mifare: MifareClassic): CardReadResult {
        mifare.connect()
        
        return try {
            // Try to authenticate and read transaction sectors
            // Indonesian e-money cards often use default keys or specific keys
            val defaultKey = MifareClassic.KEY_DEFAULT
            val transactions = mutableListOf<Transaction>()
            
            // Try reading common sectors for e-money cards
            // Sector 1-4 often contain transaction data
            for (sector in 1..4) {
                try {
                    if (mifare.authenticateSectorWithKeyA(sector, defaultKey)) {
                        val blockIndex = mifare.sectorToBlock(sector)
                        for (blockOffset in 0 until mifare.getBlockCountInSector(sector)) {
                            val data = mifare.readBlock(blockIndex + blockOffset)
                            // Parse transaction data from block
                            val transaction = parseMifareTransaction(data, sector, blockOffset)
                            if (transaction != null) {
                                transactions.add(transaction)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read sector $sector", e)
                }
            }
            
            CardReadResult.Success(
                CardInfo(CardType.UNKNOWN, "Unknown", 0, "Mifare Card"),
                transactions
            )
        } catch (e: Exception) {
            CardReadResult.Error("Mifare read error: ${e.message}")
        } finally {
            try { mifare.close() } catch (e: Exception) { }
        }
    }
    
    private fun parseMifareTransaction(data: ByteArray, sector: Int, block: Int): Transaction? {
        // Simplified parsing - real implementation would decode specific e-money formats
        return try {
            if (data.all { it == 0.toByte() } || data.all { it == 0xFF.toByte() }) {
                return null
            }
            
            // Check if this looks like a transaction record
            // (simplified heuristic)
            val amount = ((data[0].toInt() and 0xFF) shl 24 or
                    (data[1].toInt() and 0xFF) shl 16 or
                    (data[2].toInt() and 0xFF) shl 8 or
                    (data[3].toInt() and 0xFF)).toLong()
            
            if (amount > 0 && amount < 10000000) {
                Transaction(
                    id = "MIF-${sector}-${block}",
                    date = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date()),
                    time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                    location = "Parking/Sector$sector",
                    amount = amount,
                    type = TransactionType.PARKING
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun extractCardInfo(response: ByteArray, isoDep: IsoDep): CardInfo {
        // Extract card number, balance from response
        // This is simplified - real implementation needs proper TLV parsing
        return CardInfo(
            cardType = CardType.UNKNOWN,
            cardNumber = "****",
            balance = 0,
            cardName = "E-Money Card"
        )
    }
    
    private fun extractAid(response: ByteArray): ByteArray? {
        // Parse AID from PPSE response
        // Look for tag 4F (AID)
        return findTlvValue(response, 0x4F)
    }
    
    private fun extractDate(data: ByteArray): String? {
        val dateBytes = findTlvValue(data, 0x9A) ?: return null
        return if (dateBytes.size >= 3) {
            String.format("20%02d-%02d-%02d", dateBytes[0], dateBytes[1], dateBytes[2])
        } else null
    }
    
    private fun extractTime(data: ByteArray): String? {
        val timeBytes = findTlvValue(data, 0x9F21) ?: return null
        return if (timeBytes.size >= 3) {
            String.format("%02d:%02d:%02d", timeBytes[0], timeBytes[1], timeBytes[2])
        } else null
    }
    
    private fun extractAmount(data: ByteArray): Long? {
        val amountBytes = findTlvValue(data, 0x9F02) ?: return null
        return if (amountBytes.size >= 6) {
            var amount = 0L
            for (i in 0 until 6) {
                amount = (amount * 256) + (amountBytes[i].toInt() and 0xFF)
            }
            amount
        } else null
    }
    
    private fun extractMerchant(data: ByteArray): String? {
        return findTlvValue(data, 0x9F4E)?.let { String(it) }
    }
    
    private fun detectTransactionType(merchant: String): TransactionType {
        val lower = merchant.lowercase()
        return when {
            lower.contains("parkir") || lower.contains("parking") -> TransactionType.PARKING
            lower.contains("tol") || lower.contains("toll") -> TransactionType.TOLL
            lower.contains("topup") || lower.contains("isi") -> TransactionType.TOPUP
            else -> TransactionType.UNKNOWN
        }
    }
    
    private fun findTlvValue(data: ByteArray, tag: Int): ByteArray? {
        for (i in data.indices) {
            if ((data[i].toInt() and 0xFF) == tag) {
                val length = data.getOrNull(i + 1)?.toInt() ?: continue
                if (i + 2 + length <= data.size) {
                    return data.copyOfRange(i + 2, i + 2 + length)
                }
            }
        }
        return null
    }
    
    private fun buildSelectAid(aid: ByteArray): ByteArray {
        return byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + 0x00
    }
    
    private fun buildGpoCommand(): ByteArray {
        // Standard GPO with empty PDOL data
        return byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, 0x02, 0x83, 0x00, 0x00)
    }
    
    private fun ByteArray.isSuccess(): Boolean {
        return size >= 2 && this[size - 2] == 0x90.toByte() && this[size - 1] == 0x00.toByte()
    }
    
    private fun ByteArray.toHex(): String {
        return joinToString(" ") { "%02X".format(it) }
    }
}

sealed class CardReadResult {
    data class Success(val cardInfo: CardInfo, val transactions: List<Transaction>) : CardReadResult()
    data class Error(val message: String) : CardReadResult()
}
