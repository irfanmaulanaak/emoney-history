package com.emoneyreimburse.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emoneyreimburse.model.CardInfo
import com.emoneyreimburse.model.CardType
import com.emoneyreimburse.model.Transaction
import com.emoneyreimburse.model.TransactionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState
    
    fun onCardScanned(cardInfo: CardInfo, transactions: List<Transaction>) {
        _uiState.update { currentState ->
            currentState.copy(
                cardInfo = cardInfo,
                transactions = transactions,
                currentScreen = Screen.SELECT,
                isLoading = false,
                errorMessage = null
            )
        }
    }
    
    fun onScanError(message: String) {
        _uiState.update { currentState ->
            currentState.copy(
                isLoading = false,
                errorMessage = message
            )
        }
    }
    
    fun setLoading(loading: Boolean) {
        _uiState.update { currentState ->
            currentState.copy(isLoading = loading)
        }
    }
    
    fun toggleTransactionSelection(transactionId: String) {
        _uiState.update { currentState ->
            val updatedTransactions = currentState.transactions.map { transaction ->
                if (transaction.id == transactionId) {
                    transaction.copy(isSelected = !transaction.isSelected)
                } else {
                    transaction
                }
            }
            currentState.copy(transactions = updatedTransactions)
        }
    }
    
    fun onNextToInput() {
        _uiState.update { currentState ->
            currentState.copy(currentScreen = Screen.INPUT)
        }
    }
    
    fun onNameChanged(name: String) {
        _uiState.update { currentState ->
            currentState.copy(userName = name)
        }
    }
    
    fun onGeneratePdf() {
        _uiState.update { currentState ->
            currentState.copy(currentScreen = Screen.PREVIEW)
        }
    }
    
    fun onBackToScan() {
        _uiState.update { currentState ->
            currentState.copy(
                currentScreen = Screen.SCAN,
                transactions = emptyList(),
                cardInfo = null,
                userName = "",
                errorMessage = null
            )
        }
    }
    
    fun onManualInput() {
        android.util.Log.d("MainViewModel", "onManualInput() called - changing screen to MANUAL")
        _uiState.update { currentState ->
            currentState.copy(
                currentScreen = Screen.MANUAL,
                cardInfo = CardInfo(
                    cardType = CardType.UNKNOWN,
                    cardNumber = "Manual Input",
                    balance = 0,
                    cardName = "Input Manual"
                ),
                errorMessage = null
            )
        }
    }
    
    fun addManualTransaction(transaction: Transaction) {
        _uiState.update { currentState ->
            currentState.copy(
                transactions = currentState.transactions + transaction
            )
        }
    }
    
    fun deleteManualTransaction(transactionId: String) {
        _uiState.update { currentState ->
            currentState.copy(
                transactions = currentState.transactions.filter { it.id != transactionId }
            )
        }
    }
    
    fun onManualNextToSelect() {
        _uiState.update { currentState ->
            currentState.copy(currentScreen = Screen.SELECT)
        }
    }
    
    fun onBackToSelect() {
        _uiState.update { currentState ->
            currentState.copy(currentScreen = Screen.SELECT)
        }
    }
    
    fun onBackToInput() {
        _uiState.update { currentState ->
            currentState.copy(currentScreen = Screen.INPUT)
        }
    }
    
    fun clearError() {
        _uiState.update { currentState ->
            currentState.copy(errorMessage = null)
        }
    }
    
    fun loadDemoData() {
        viewModelScope.launch {
            setLoading(true)
            // Simulate network delay
            kotlinx.coroutines.delay(1000)
            
            val demoTransactions = listOf(
                Transaction(
                    id = "DEMO1",
                    date = "2024-04-22",
                    time = "08:30",
                    location = "Parkir Gedung A",
                    amount = 5000,
                    type = TransactionType.PARKING
                ),
                Transaction(
                    id = "DEMO2",
                    date = "2024-04-22",
                    time = "17:45",
                    location = "Parkir Gedung A",
                    amount = 5000,
                    type = TransactionType.PARKING
                ),
                Transaction(
                    id = "DEMO3",
                    date = "2024-04-23",
                    time = "08:15",
                    location = "Parkir Mall B",
                    amount = 4000,
                    type = TransactionType.PARKING
                ),
                Transaction(
                    id = "DEMO4",
                    date = "2024-04-23",
                    time = "12:30",
                    location = "Restoran C",
                    amount = 45000,
                    type = TransactionType.PURCHASE
                ),
                Transaction(
                    id = "DEMO5",
                    date = "2024-04-24",
                    time = "09:00",
                    location = "Parkir Gedung A",
                    amount = 5000,
                    type = TransactionType.PARKING
                ),
                Transaction(
                    id = "DEMO6",
                    date = "2024-04-24",
                    time = "18:20",
                    location = "Parkir Gedung A",
                    amount = 5000,
                    type = TransactionType.PARKING
                ),
                Transaction(
                    id = "DEMO7",
                    date = "2024-04-25",
                    time = "08:45",
                    location = "Parkir Mall B",
                    amount = 4000,
                    type = TransactionType.PARKING
                ),
                Transaction(
                    id = "DEMO8",
                    date = "2024-04-25",
                    time = "17:30",
                    location = "Parkir Mall B",
                    amount = 4000,
                    type = TransactionType.PARKING
                ),
                Transaction(
                    id = "DEMO9",
                    date = "2024-04-26",
                    time = "09:15",
                    location = "Tol Jakarta",
                    amount = 11500,
                    type = TransactionType.TOLL
                ),
                Transaction(
                    id = "DEMO10",
                    date = "2024-04-26",
                    time = "19:00",
                    location = "Parkir Gedung A",
                    amount = 5000,
                    type = TransactionType.PARKING
                )
            )
            
            onCardScanned(
                CardInfo(
                    cardType = CardType.MANDIRI_EMONEY,
                    cardNumber = "6032 **** **** 1234",
                    balance = 125000,
                    cardName = "Mandiri e-money"
                ),
                demoTransactions
            )
        }
    }
}

data class UiState(
    val currentScreen: Screen = Screen.SCAN,
    val cardInfo: CardInfo? = null,
    val transactions: List<Transaction> = emptyList(),
    val userName: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

enum class Screen {
    SCAN, MANUAL, SELECT, INPUT, PREVIEW
}
