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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

/**
 * NFC Card Reader for Indonesian e-money cards (Mandiri e-money, BCA Flazz, etc.)
 * 
 * Based on research of open-source projects and EMV standards:
 * - EMV-NFC-Paycard-Enrollment library (devnied)
 * - NFCard project (cachewind)
 * - prepaidcard_reader Flutter plugin (agusibrahim)
 * 
 * These cards typically use:
 * - Mifare Classic (older cards) with proprietary sector layouts
 * - IsoDep/EMV (newer cards) with standard EMV transaction logs
 */
class NfcCardReader {
    
    companion object {
        private const val TAG = "NfcCardReader"
        
        // Common AIDs for payment systems
        private val MASTERCARD_AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x04, 0x10, 0x10)
        private val VISA_AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x03, 0x10, 0x10)
        private val EMV_AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x03, 0x20, 0x10)
        
        // PPSE - Payment System Environment
        private val SELECT_PPSE = byteArrayOf(
            0x00, 0xA4.toByte(), 0x04, 0x00, 0x0E,
            0x32, 0x50, 0x41, 0x59, 0x2E, 0x53, 0x59, 0x53,
            0x2E, 0x44, 0x44, 0x46, 0x30, 0x31, 0x00
        )
        
        // Common Mifare Classic keys used by Indonesian e-money cards
        private val COMMON_KEYS = listOf(
            MifareClassic.KEY_DEFAULT,           // FF FF FF FF FF FF
            byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte()),
            byteArrayOf(0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte()),
            byteArrayOf(0xB0.toByte(), 0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte()),
            byteArrayOf(0x4D.toByte(), 0x61.toByte(), 0x64.toByte(), 0x61.toByte(), 0x6D.toByte(), 0x69.toByte()), // "Madami"
            byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()),
        )
        
        // Mandiri e-money specific keys
        private val MANDIRI_KEYS = listOf(
            byteArrayOf(0x4D.toByte(), 0x61.toByte(), 0x6E.toByte(), 0x64.toByte(), 0x69.toByte(), 0x72.toByte()), // "Mandir"
            MifareClassic.KEY_DEFAULT
        )
        
        // BCA Flazz specific keys
        private val FLAZZ_KEYS = listOf(
            byteArrayOf(0x42.toByte(), 0x43.toByte(), 0x41.toByte(), 0x46.toByte(), 0x6C.toByte(), 0x61.toByte()), // "BCAFla"
            MifareClassic.KEY_DEFAULT
        )
    }
    
    fun readCard(tag: Tag): CardReadResult {
        return try {
            // Try IsoDep first (EMV/ISO 7816 cards - newer e-money)
            val isoDep = IsoDep.get(tag)
            if (isoDep != null) {
                val result = readIsoDepCard(isoDep)
                if (result is CardReadResult.Success && result.transactions.isNotEmpty()) {
                    return result
                }
            }
            
            // Try Mifare Classic (older e-money cards)
            val mifare = MifareClassic.get(tag)
            if (mifare != null) {
                val result = readMifareClassicCard(mifare)
                if (result is CardReadResult.Success && result.transactions.isNotEmpty()) {
                    return result
                }
            }
            
            // If we got here, try to return whatever we got, or error
            if (isoDep != null) {
                readIsoDepCard(isoDep) // Return IsoDep result even if empty
            } else if (mifare != null) {
                readMifareClassicCard(mifare)
            } else {
                CardReadResult.Error("Kartu tidak didukung. Pastikan kartu e-money/Flazz asli.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading card", e)
            CardReadResult.Error("Gagal membaca kartu: ${e.message}")
        }
    }
    
    private fun readIsoDepCard(isoDep: IsoDep): CardReadResult {
        isoDep.connect()
        isoDep.timeout = 5000
        
        return try {
            // Step 1: Select PPSE
            val ppseResponse = isoDep.transceive(SELECT_PPSE)
            Log.d(TAG, "PPSE Response: ${ppseResponse.toHex()}")
            
            var cardType = CardType.UNKNOWN
            var cardName = "Kartu e-money"
            var transactions = emptyList<Transaction>()
            
            if (ppseResponse.isSuccess()) {
                // Step 2: Extract AID and select application
                val aid = extractAid(ppseResponse)
                if (aid != null) {
                    val selectAid = buildSelectAid(aid)
                    val aidResponse = isoDep.transceive(selectAid)
                    Log.d(TAG, "AID Response: ${aidResponse.toHex()}")
                    
                    if (aidResponse.isSuccess()) {
                        // Detect card type from AID
                        when {
                            aid.contentEquals(MASTERCARD_AID) -> {
                                cardType = CardType.MANDIRI_EMONEY
                                cardName = "Mandiri e-money"
                            }
                            aid.contentEquals(VISA_AID) -> {
                                cardType = CardType.BCA_FLAZZ
                                cardName = "BCA Flazz"
                            }
                        }
                        
                        // Step 3: Get Processing Options
                        val gpo = buildGpoCommand(aidResponse)
                        val gpoResponse = isoDep.transceive(gpo)
                        Log.d(TAG, "GPO Response: ${gpoResponse.toHex()}")
                        
                        // Step 4: Read transaction log
                        transactions = readEmvTransactionLog(isoDep)
                        
                        val cardInfo = extractCardInfo(aidResponse, isoDep, cardType, cardName)
                        return CardReadResult.Success(cardInfo, transactions)
                    }
                }
            }
            
            // Try direct AID selection for known cards
            tryDirectAidSelection(isoDep)
        } catch (e: IOException) {
            CardReadResult.Error("Error komunikasi: ${e.message}")
        } finally {
            try { isoDep.close() } catch (e: Exception) { }
        }
    }
    
    private fun tryDirectAidSelection(isoDep: IsoDep): CardReadResult {
        val knownAids = listOf(
            Pair(MASTERCARD_AID, Pair(CardType.MANDIRI_EMONEY, "Mandiri e-money")),
            Pair(VISA_AID, Pair(CardType.BCA_FLAZZ, "BCA Flazz")),
            Pair(EMV_AID, Pair(CardType.UNKNOWN, "Kartu EMV"))
        )
        
        for ((aid, cardInfoPair) in knownAids) {
            try {
                val selectAid = buildSelectAid(aid)
                val response = isoDep.transceive(selectAid)
                if (response.isSuccess()) {
                    val transactions = readEmvTransactionLog(isoDep)
                    val (cardType, cardName) = cardInfoPair
                    val info = extractCardInfo(response, isoDep, cardType, cardName)
                    return CardReadResult.Success(info, transactions)
                }
            } catch (e: Exception) {
                Log.w(TAG, "AID selection failed for ${cardInfoPair.second}", e)
            }
        }
        
        return CardReadResult.Error("Tidak dapat membaca aplikasi kartu. Kartu mungkin tidak mendukung pembacaan log transaksi.")
    }
    
    private fun readEmvTransactionLog(isoDep: IsoDep): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        
        try {
            // EMV Book 3, Annex D - Transaction Log
            // Try to get Log Format first (Tag 9F4F)
            val getLogFormat = byteArrayOf(0x80.toByte(), 0xCA.toByte(), 0x9F.toByte(), 0x4F, 0x00)
            val logFormatResponse = isoDep.transceive(getLogFormat)
            Log.d(TAG, "Log Format Response: ${logFormatResponse.toHex()}")
            
            // Common SFI for transaction log = 0x0C (12)
            val possibleSfis = listOf(0x0C, 0x08, 0x10, 0x18)
            
            for (logSfi in possibleSfis) {
                if (transactions.isNotEmpty()) break
                
                for (record in 1..20) {
                    val readRecord = buildReadRecord(record, logSfi)
                    val recordResponse = isoDep.transceive(readRecord)
                    
                    if (recordResponse.isSuccess() && recordResponse.size > 2) {
                        val transaction = parseEmvTransactionRecord(recordResponse, record)
                        if (transaction != null) {
                            transactions.add(transaction)
                        }
                    } else {
                        break // No more records in this SFI
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading EMV transaction log", e)
        }
        
        return transactions
    }
    
    private fun parseEmvTransactionRecord(data: ByteArray, recordNum: Int): Transaction? {
        return try {
            val tlvData = data.copyOfRange(0, data.size - 2) // Remove SW1 SW2
            
            // Parse standard EMV transaction log fields
            val date = parseEmvDate(tlvData)
            val time = parseEmvTime(tlvData)
            val amount = parseEmvAmount(tlvData)
            val merchant = parseEmvMerchant(tlvData) ?: "Transaksi #$recordNum"
            
            if (amount != null && amount > 0) {
                Transaction(
                    id = "EMV-$recordNum",
                    date = date ?: "Unknown",
                    time = time ?: "Unknown",
                    location = merchant,
                    amount = amount,
                    type = detectTransactionType(merchant)
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EMV record", e)
            null
        }
    }
    
    private fun readMifareClassicCard(mifare: MifareClassic): CardReadResult {
        mifare.connect()
        
        return try {
            val cardType = detectMifareCardType(mifare)
            val transactions = mutableListOf<Transaction>()
            var balance: Long = 0
            var cardId = ""
            
            // Try to read card ID from sector 0
            try {
                if (authenticateSector(mifare, 0, COMMON_KEYS)) {
                    val blockIndex = mifare.sectorToBlock(0)
                    val uidData = mifare.readBlock(blockIndex)
                    cardId = uidData.copyOfRange(0, 4).toHex()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read card ID", e)
            }
            
            // Try to read balance and transactions from known sectors
            // Indonesian e-money cards typically store data in sectors 1-8
            for (sector in 1..8) {
                try {
                    val keys = when (cardType) {
                        CardType.MANDIRI_EMONEY -> MANDIRI_KEYS
                        CardType.BCA_FLAZZ -> FLAZZ_KEYS
                        else -> COMMON_KEYS
                    }
                    
                    if (authenticateSector(mifare, sector, keys)) {
                        val blockIndex = mifare.sectorToBlock(sector)
                        val blockCount = mifare.getBlockCountInSector(sector)
                        
                        for (blockOffset in 0 until blockCount - 1) { // Skip trailer block
                            val data = mifare.readBlock(blockIndex + blockOffset)
                            
                            // Try to parse as transaction
                            val transaction = parseMifareTransaction(data, sector, blockOffset)
                            if (transaction != null) {
                                transactions.add(transaction)
                            }
                            
                            // Try to parse as balance (often in specific blocks)
                            if (sector == 2 && blockOffset == 0) {
                                val parsedBalance = parseMifareBalance(data)
                                if (parsedBalance > 0) balance = parsedBalance
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read sector $sector", e)
                }
            }
            
            CardReadResult.Success(
                CardInfo(
                    cardType = cardType,
                    cardNumber = if (cardId.isNotEmpty()) "****$cardId" else "****",
                    balance = balance,
                    cardName = when (cardType) {
                        CardType.MANDIRI_EMONEY -> "Mandiri e-money"
                        CardType.BCA_FLAZZ -> "BCA Flazz"
                        else -> "Kartu Mifare"
                    }
                ),
                transactions.sortedByDescending { it.date + it.time }
            )
        } catch (e: Exception) {
            CardReadResult.Error("Error membaca kartu Mifare: ${e.message}")
        } finally {
            try { mifare.close() } catch (e: Exception) { }
        }
    }
    
    private fun authenticateSector(mifare: MifareClassic, sector: Int, keys: List<ByteArray>): Boolean {
        for (key in keys) {
            try {
                if (mifare.authenticateSectorWithKeyA(sector, key)) {
                    return true
                }
                if (mifare.authenticateSectorWithKeyB(sector, key)) {
                    return true
                }
            } catch (e: Exception) {
                // Try next key
            }
        }
        return false
    }
    
    private fun detectMifareCardType(mifare: MifareClassic): CardType {
        // Try to detect card type by attempting authentication with specific keys
        return try {
            for (sector in 1..4) {
                if (authenticateSector(mifare, sector, MANDIRI_KEYS)) {
                    return CardType.MANDIRI_EMONEY
                }
                if (authenticateSector(mifare, sector, FLAZZ_KEYS)) {
                    return CardType.BCA_FLAZZ
                }
            }
            CardType.UNKNOWN
        } catch (e: Exception) {
            CardType.UNKNOWN
        }
    }
    
    private fun parseMifareTransaction(data: ByteArray, sector: Int, block: Int): Transaction? {
        return try {
            // Skip empty or factory blocks
            if (data.all { it == 0x00.toByte() } || data.all { it == 0xFF.toByte() }) {
                return null
            }
            
            // Heuristic: Indonesian e-money transactions often have:
            // - Amount in specific bytes (usually 2-4 bytes)
            // - Date/time encoded in remaining bytes
            
            // Try multiple parsing strategies
            
            // Strategy 1: Direct amount in first 4 bytes (big endian)
            val amount1 = ByteBuffer.wrap(data.copyOfRange(0, 4)).int.toLong()
            if (amount1 in 1000..10000000) {
                return createTransaction(data, amount1, sector, block)
            }
            
            // Strategy 2: Amount in bytes 4-8
            val amount2 = ByteBuffer.wrap(data.copyOfRange(4, 8)).int.toLong()
            if (amount2 in 1000..10000000) {
                return createTransaction(data, amount2, sector, block)
            }
            
            // Strategy 3: Little endian amount
            val amount3 = ByteBuffer.wrap(data.copyOfRange(0, 4))
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong()
            if (amount3 in 1000..10000000) {
                return createTransaction(data, amount3, sector, block)
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun createTransaction(data: ByteArray, amount: Long, sector: Int, block: Int): Transaction {
        // Try to extract date from remaining bytes
        val dateStr = try {
            val day = data.getOrNull(8)?.toInt()?.and(0xFF) ?: 0
            val month = data.getOrNull(9)?.toInt()?.and(0xFF) ?: 0
            val year = data.getOrNull(10)?.toInt()?.and(0xFF) ?: 0
            if (day in 1..31 && month in 1..12 && year in 0..99) {
                String.format("20%02d-%02d-%02d", year, month, day)
            } else {
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            }
        } catch (e: Exception) {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        }
        
        val timeStr = try {
            val hour = data.getOrNull(11)?.toInt()?.and(0xFF) ?: 0
            val minute = data.getOrNull(12)?.toInt()?.and(0xFF) ?: 0
            if (hour in 0..23 && minute in 0..59) {
                String.format("%02d:%02d", hour, minute)
            } else {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            }
        } catch (e: Exception) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        }
        
        return Transaction(
            id = "MIF-${sector}-${block}",
            date = dateStr,
            time = timeStr,
            location = "Parkir (${sector}:${block})",
            amount = amount,
            type = TransactionType.PARKING
        )
    }
    
    private fun parseMifareBalance(data: ByteArray): Long {
        return try {
            val balance = ByteBuffer.wrap(data.copyOfRange(0, 4)).int.toLong()
            if (balance in 0..100000000) balance else 0
        } catch (e: Exception) {
            0
        }
    }
    
    private fun extractCardInfo(response: ByteArray, isoDep: IsoDep, cardType: CardType, cardName: String): CardInfo {
        var cardNumber = "****"
        var balance = 0L
        
        try {
            // Try to read PAN (Tag 5A)
            val pan = findTlvValue(response, 0x5A)
            if (pan != null) {
                cardNumber = pan.toHex().let { 
                    if (it.length >= 8) "****${it.takeLast(4)}" else "****"
                }
            }
            
            // Try to read balance if available
            // Some cards support GET DATA for balance
            val getBalance = byteArrayOf(0x80.toByte(), 0xCA.toByte(), 0x9F.toByte(), 0x79, 0x00)
            val balanceResponse = isoDep.transceive(getBalance)
            if (balanceResponse.isSuccess()) {
                val balanceData = balanceResponse.copyOfRange(0, balanceResponse.size - 2)
                if (balanceData.size >= 4) {
                    balance = ByteBuffer.wrap(balanceData).int.toLong()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract card info", e)
        }
        
        return CardInfo(cardType, cardNumber, balance, cardName)
    }
    
    private fun extractAid(response: ByteArray): ByteArray? {
        return findTlvValue(response, 0x4F)
    }
    
    private fun parseEmvDate(data: ByteArray): String? {
        val dateBytes = findTlvValue(data, 0x9A) ?: return null
        return if (dateBytes.size >= 3) {
            String.format("20%02d-%02d-%02d", dateBytes[0], dateBytes[1], dateBytes[2])
        } else null
    }
    
    private fun parseEmvTime(data: ByteArray): String? {
        val timeBytes = findTlvValue(data, 0x9F21) ?: return null
        return if (timeBytes.size >= 3) {
            String.format("%02d:%02d:%02d", timeBytes[0], timeBytes[1], timeBytes[2])
        } else null
    }
    
    private fun parseEmvAmount(data: ByteArray): Long? {
        val amountBytes = findTlvValue(data, 0x9F02) ?: return null
        return if (amountBytes.size >= 6) {
            var amount = 0L
            for (i in 0 until 6) {
                amount = (amount * 256) + (amountBytes[i].toInt() and 0xFF)
            }
            amount
        } else null
    }
    
    private fun parseEmvMerchant(data: ByteArray): String? {
        return findTlvValue(data, 0x9F4E)?.let { 
            String(it).trim().replace(Regex("[^ -~]"), "")
        }
    }
    
    private fun detectTransactionType(merchant: String): TransactionType {
        val lower = merchant.lowercase()
        return when {
            lower.contains("parkir") || lower.contains("parking") -> TransactionType.PARKING
            lower.contains("tol") || lower.contains("toll") || lower.contains("jalan") -> TransactionType.TOLL
            lower.contains("topup") || lower.contains("isi") || lower.contains("top up") -> TransactionType.TOPUP
            else -> TransactionType.UNKNOWN
        }
    }
    
    private fun findTlvValue(data: ByteArray, tag: Int): ByteArray? {
        var i = 0
        while (i < data.size - 1) {
            val currentTag = data[i].toInt() and 0xFF
            if (currentTag == tag) {
                val length = data.getOrNull(i + 1)?.toInt() ?: return null
                if (i + 2 + length <= data.size) {
                    return data.copyOfRange(i + 2, i + 2 + length)
                }
            }
            i++
        }
        return null
    }
    
    private fun buildSelectAid(aid: ByteArray): ByteArray {
        return byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + 0x00
    }
    
    private fun buildGpoCommand(fciResponse: ByteArray): ByteArray {
        // Parse PDOL from FCI and build GPO
        // For simplicity, use empty PDOL response
        return byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x83.toByte(), 0x00.toByte(), 0x00.toByte())
    }
    
    private fun buildReadRecord(record: Int, sfi: Int): ByteArray {
        return byteArrayOf(
            0x00.toByte(), 0xB2.toByte(),
            record.toByte(),
            ((sfi shl 3) or 0x04).toByte(),
            0x00.toByte()
        )
    }
    
    private fun ByteArray.isSuccess(): Boolean {
        return size >= 2 && this[size - 2] == 0x90.toByte() && this[size - 1] == 0x00.toByte()
    }
    
    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02X".format(it) }
    }
}

sealed class CardReadResult {
    data class Success(val cardInfo: CardInfo, val transactions: List<Transaction>) : CardReadResult()
    data class Error(val message: String) : CardReadResult()
}
