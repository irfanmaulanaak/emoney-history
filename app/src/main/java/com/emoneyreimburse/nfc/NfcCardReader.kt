package com.emoneyreimburse.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
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
 * NFC Card Reader for Indonesian e-money cards using Mifare Classic.
 *
 * CRITICAL: These cards use proprietary sector keys. Without the correct keys,
 * we cannot authenticate and read the data. This reader attempts a dictionary
 * attack using all known public keys + common Indonesian e-money keys.
 *
 * If none of the keys work, the card likely uses custom/undocumented keys.
 */
class NfcCardReader {

    companion object {
        private const val TAG = "NfcCardReader"

        // Comprehensive key dictionary from Mifare Classic Tool + known Indonesian keys
        private val KEY_DICTIONARY = listOf(
            // Default keys
            hexToBytes("FFFFFFFFFFFF"),
            hexToBytes("000000000000"),
            hexToBytes("A0A1A2A3A4A5"),  // MAD key
            hexToBytes("D3F7D3F7D3F7"),  // NFC Forum
            hexToBytes("B0B1B2B3B4B5"),

            // Common transport/retail keys
            hexToBytes("A0B0C0D0E0F0"),
            hexToBytes("A1B1C1D1E1F1"),
            hexToBytes("B5FF67CBA951"),
            hexToBytes("4D3A99C351DD"),
            hexToBytes("1A982C7E459A"),
            hexToBytes("AABBCCDDEEFF"),

            // Indonesian e-money related keys (guessed/common)
            hexToBytes("4D616E646972"), // "Mandir" ASCII
            hexToBytes("424341466C61"), // "BCAFla" ASCII
            hexToBytes("464C415A5A30"), // "FLAZZ0"
            hexToBytes("454D4F4E4559"), // "EMONEY"
            hexToBytes("4252495A5A49"), // "BRIZZI"
            hexToBytes("544150434153"), // "TAPCAS" (Tapcash)
            hexToBytes("4A414B4C494E"), // "JAKLIN" (Jaklingko)
            hexToBytes("424E4954454C"), // "BNITEL" (BNI)
            hexToBytes("4D4547414341"), // "MEGACA" (Megacash)
            hexToBytes("4B4D54545249"), // "KMTRI" (KMT/KRL)

            // Keys from various e-money/top-up machine vendors
            hexToBytes("123456789ABC"),
            hexToBytes("111111111111"),
            hexToBytes("222222222222"),
            hexToBytes("333333333333"),
            hexToBytes("444444444444"),
            hexToBytes("555555555555"),
            hexToBytes("666666666666"),
            hexToBytes("777777777777"),
            hexToBytes("888888888888"),
            hexToBytes("999999999999"),
            hexToBytes("AAAAAAAAAAAA"),
            hexToBytes("BBBBBBBBBBBB"),
            hexToBytes("CCCCCCCCCCCC"),
            hexToBytes("DDDDDDDDDDDD"),
            hexToBytes("EEEEEEEEEEEE"),

            // Other known keys from public dumps
            hexToBytes("A94133010401"),
            hexToBytes("E3619E2C923F"),
            hexToBytes("878051E9A8F9"),
            hexToBytes("3A3B6C5B7A52"),
            hexToBytes("1243559C6B07"),
            hexToBytes("01FA3F361E3D"),
            hexToBytes("123456ABCDEF"),
            hexToBytes("F1A9732A9C4D"),
            hexToBytes("1860F387A502"),
            hexToBytes("C0C1C2C3C4C5"),
            hexToBytes("D0D1D2D3D4D5"),
            hexToBytes("E0E1E2E3E4E5"),
            hexToBytes("F0F1F2F3F4F5"),
            hexToBytes("A5B4C3D2E1F0"),
            hexToBytes("0123456789AB"),
            hexToBytes("FEDCBA987654"),
            hexToBytes("ABCDEF123456"),
            hexToBytes("654321ABCDEF"),
            hexToBytes("5F5F5F5F5F5F"),
            hexToBytes("100000000000"),
            hexToBytes("200000000000"),
            hexToBytes("300000000000"),
            hexToBytes("400000000000"),
            hexToBytes("500000000000"),

            // Keys often used in access control / transport in Asia
            hexToBytes("AB12CD34EF56"),
            hexToBytes("12AB34CD56EF"),
            hexToBytes("1234ABCD5678"),
            hexToBytes("000102030405"),
            hexToBytes("001122334455"),
            hexToBytes("112233445566"),
            hexToBytes("223344556677"),
            hexToBytes("334455667788"),
            hexToBytes("445566778899"),
            hexToBytes("5566778899AA"),
            hexToBytes("66778899AABB"),
            hexToBytes("778899AABBCC"),
            hexToBytes("8899AABBCCDD"),
            hexToBytes("99AABBCCDDEE"),
            hexToBytes("AABBCCDDEEFF"),
        )

        private fun hexToBytes(hex: String): ByteArray {
            val bytes = ByteArray(hex.length / 2)
            for (i in bytes.indices) {
                bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return bytes
        }
    }

    fun readCard(tag: Tag): CardReadResult {
        Log.i(TAG, "========================================")
        Log.i(TAG, "CARD DETECTED")
        Log.i(TAG, "========================================")
        Log.i(TAG, "UID: ${tag.id.toHex()}")
        Log.i(TAG, "Tech List: ${tag.techList.joinToString()}")

        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            Log.e(TAG, "This is NOT a Mifare Classic card!")
            return CardReadResult.Error(
                "Kartu ini bukan Mifare Classic.\n" +
                "Tech yang terdeteksi: ${tag.techList.joinToString()}\n" +
                "Aplikasi ini hanya support kartu e-money/Flazz model lama (Mifare Classic)."
            )
        }

        return try {
            readMifareClassic(mifare, tag.id.toHex())
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error reading card", e)
            CardReadResult.Error("Error: ${e.message}")
        }
    }

    private fun readMifareClassic(mifare: MifareClassic, uid: String): CardReadResult {
        Log.i(TAG, "Connecting to Mifare Classic...")
        mifare.connect()
        Log.i(TAG, "Connected!")
        Log.i(TAG, "Card Type: ${if (mifare.type == MifareClassic.TYPE_CLASSIC) "Classic" else "Classic 4K"}")
        Log.i(TAG, "Sector Count: ${mifare.sectorCount}")
        Log.i(TAG, "Block Count: ${mifare.blockCount}")
        Log.i(TAG, "Size: ${mifare.size} bytes")

        val allSectorData = mutableMapOf<Int, List<ByteArray>>()
        var foundKeys = 0
        var failedSectors = 0

        try {
            // Attempt to authenticate and read EVERY sector
            for (sector in 0 until mifare.sectorCount) {
                val blockIndex = mifare.sectorToBlock(sector)
                val blockCount = mifare.getBlockCountInSector(sector)
                var authenticated = false
                var workingKey: ByteArray? = null

                // Try every key in the dictionary for Key A
                for (key in KEY_DICTIONARY) {
                    try {
                        if (mifare.authenticateSectorWithKeyA(sector, key)) {
                            authenticated = true
                            workingKey = key
                            foundKeys++
                            Log.i(TAG, "Sector $sector AUTHENTICATED with Key A: ${key.toHex()}")
                            break
                        }
                    } catch (e: Exception) { /* ignore */ }
                }

                // If Key A fails, try Key B
                if (!authenticated) {
                    for (key in KEY_DICTIONARY) {
                        try {
                            if (mifare.authenticateSectorWithKeyB(sector, key)) {
                                authenticated = true
                                workingKey = key
                                foundKeys++
                                Log.i(TAG, "Sector $sector AUTHENTICATED with Key B: ${key.toHex()}")
                                break
                            }
                        } catch (e: Exception) { /* ignore */ }
                    }
                }

                if (!authenticated) {
                    failedSectors++
                    Log.w(TAG, "Sector $sector: AUTHENTICATION FAILED (tried ${KEY_DICTIONARY.size} keys)")
                    continue
                }

                // Read all data blocks in this sector
                val sectorBlocks = mutableListOf<ByteArray>()
                for (blockOffset in 0 until blockCount) {
                    try {
                        val blockData = mifare.readBlock(blockIndex + blockOffset)
                        sectorBlocks.add(blockData)
                        Log.d(TAG, "Sector $sector Block $blockOffset: ${blockData.toHex()}")
                    } catch (e: IOException) {
                        Log.w(TAG, "Sector $sector Block $blockOffset: Read failed", e)
                    }
                }
                allSectorData[sector] = sectorBlocks
            }

            Log.i(TAG, "========================================")
            Log.i(TAG, "READ SUMMARY")
            Log.i(TAG, "========================================")
            Log.i(TAG, "Authenticated Sectors: $foundKeys/${mifare.sectorCount}")
            Log.i(TAG, "Failed Sectors: $failedSectors/${mifare.sectorCount}")

            if (allSectorData.isEmpty()) {
                return CardReadResult.Error(
                    "Tidak bisa membaca kartu.\n\n" +
                    "Semua sector gagal diautentikasi.\n" +
                    "Kartu ini menggunakan keys yang tidak dikenal.\n\n" +
                    "Coba install aplikasi 'Mifare Classic Tool' dari Play Store " +
                    "untuk dump kartu dan analisa keys-nya."
                )
            }

            // Try to parse transactions from the raw data
            val transactions = parseTransactionsFromSectors(allSectorData)

            return CardReadResult.Success(
                CardInfo(
                    cardType = CardType.UNKNOWN,
                    cardNumber = uid,
                    balance = 0,
                    cardName = "Kartu e-money (Mifare Classic)"
                ),
                transactions
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error during read", e)
            return CardReadResult.Error("Error saat membaca: ${e.message}")
        } finally {
            try { mifare.close() } catch (e: Exception) { }
        }
    }

    /**
     * Parse transaction data from the raw sector data.
     * This is HEURISTIC since each card type has its own format.
     */
    private fun parseTransactionsFromSectors(
        sectorData: Map<Int, List<ByteArray>>
    ): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        var txnIndex = 1

        for ((sector, blocks) in sectorData) {
            // Skip sector 0 (manufacturer data)
            if (sector == 0) continue

            for ((blockOffset, block) in blocks.withIndex()) {
                // Skip sector trailers (last block of each sector)
                if (blockOffset == blocks.size - 1) continue

                // Skip empty blocks
                if (block.all { it == 0x00.toByte() } || block.all { it == 0xFF.toByte() }) {
                    continue
                }

                Log.d(TAG, "Analyzing Sector $sector Block $blockOffset: ${block.toHex()}")

                // Heuristic 1: Look for amount in first 2-4 bytes
                // Indonesian parking is usually 2000, 3000, 4000, 5000, 10000, 15000, etc.
                val amount = extractAmount(block)

                if (amount != null && amount >= 1000 && amount <= 1000000) {
                    // Try to find a date/time near the amount
                    val (date, time) = extractDateTime(block)

                    transactions.add(
                        Transaction(
                            id = "TXN-${txnIndex++}",
                            date = date,
                            time = time,
                            location = "Transaksi (${sector}:${blockOffset})",
                            amount = amount,
                            type = TransactionType.PARKING
                        )
                    )
                    Log.i(TAG, "Found transaction: Rp $amount at $date $time")
                }
            }
        }

        return transactions.sortedByDescending { it.date + it.time }
    }

    private fun extractAmount(block: ByteArray): Long? {
        // Try multiple interpretations of the amount field
        return try {
            // Interpretation 1: 2-byte big-endian (most common for small amounts)
            val amt1 = ((block[0].toInt() and 0xFF) shl 8 or (block[1].toInt() and 0xFF)).toLong()
            if (amt1 in 1000..1000000) return amt1

            // Interpretation 2: 2-byte little-endian
            val amt2 = ((block[1].toInt() and 0xFF) shl 8 or (block[0].toInt() and 0xFF)).toLong()
            if (amt2 in 1000..1000000) return amt2

            // Interpretation 3: 3-byte big-endian
            val amt3 = ((block[0].toInt() and 0xFF) shl 16 or
                    (block[1].toInt() and 0xFF) shl 8 or
                    (block[2].toInt() and 0xFF)).toLong()
            if (amt3 in 1000..1000000) return amt3

            // Interpretation 4: 4-byte big-endian (less common for parking)
            val amt4 = ByteBuffer.wrap(block.copyOfRange(0, 4)).int.toLong()
            if (amt4 in 1000..1000000) return amt4

            // Interpretation 5: 4-byte little-endian
            val amt5 = ByteBuffer.wrap(block.copyOfRange(0, 4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
            if (amt5 in 1000..1000000) return amt5

            null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractDateTime(block: ByteArray): Pair<String, String> {
        val defaultDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val defaultTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        return try {
            // Look for date/time in bytes 2-7
            if (block.size >= 8) {
                val year = block[2].toInt() and 0xFF
                val month = block[3].toInt() and 0xFF
                val day = block[4].toInt() and 0xFF
                val hour = block[5].toInt() and 0xFF
                val minute = block[6].toInt() and 0xFF

                val validDate = year in 20..30 && month in 1..12 && day in 1..31
                val validTime = hour in 0..23 && minute in 0..59

                val dateStr = if (validDate) String.format("20%02d-%02d-%02d", year, month, day) else defaultDate
                val timeStr = if (validTime) String.format("%02d:%02d", hour, minute) else defaultTime

                Pair(dateStr, timeStr)
            } else {
                Pair(defaultDate, defaultTime)
            }
        } catch (e: Exception) {
            Pair(defaultDate, defaultTime)
        }
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02X".format(it) }
    }
}

sealed class CardReadResult {
    data class Success(val cardInfo: CardInfo, val transactions: List<Transaction>) : CardReadResult()
    data class Error(val message: String) : CardReadResult()
}
