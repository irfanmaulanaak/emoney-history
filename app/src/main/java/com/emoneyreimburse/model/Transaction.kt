package com.emoneyreimburse.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Transaction(
    val id: String,
    val date: String,
    val time: String,
    val location: String,
    val amount: Long,
    val type: TransactionType,
    val isSelected: Boolean = false
) : Parcelable {
    fun formattedAmount(): String {
        return "Rp %,d".format(amount)
    }
    
    fun formattedDateTime(): String {
        return "$date $time"
    }
}

enum class TransactionType {
    PARKING,
    TOLL,
    TOPUP,
    PURCHASE,
    TRANSFER,
    UNKNOWN
}

enum class CardType {
    MANDIRI_EMONEY,
    BCA_FLAZZ,
    BNI_TAPCASH,
    BRI_BRIZZI,
    UNKNOWN
}

@Parcelize
data class CardInfo(
    val cardType: CardType,
    val cardNumber: String,
    val balance: Long,
    val cardName: String
) : Parcelable
