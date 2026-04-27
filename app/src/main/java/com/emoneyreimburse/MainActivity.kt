package com.emoneyreimburse

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.emoneyreimburse.nfc.CardReadResult
import com.emoneyreimburse.nfc.NfcCardReader
import com.emoneyreimburse.ui.*
import com.emoneyreimburse.ui.theme.EmoneyReimburseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private val viewModel: MainViewModel by viewModels()
    private val nfcCardReader = NfcCardReader()
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        Log.d(TAG, "NFC Adapter: $nfcAdapter")
        
        // Create PendingIntent for NFC foreground dispatch
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_MUTABLE
        )
        
        setContent {
            EmoneyReimburseTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val uiState by viewModel.uiState.collectAsState()
                    
                    when (uiState.currentScreen) {
                        Screen.SCAN -> {
                            ScanScreen(
                                isLoading = uiState.isLoading,
                                errorMessage = uiState.errorMessage,
                                onNfcPrompt = { 
                                    Log.d(TAG, "onNfcPrompt clicked")
                                    checkNfcAndPrompt() 
                                },
                                onDemoClick = { 
                                    Log.d(TAG, "onDemoClick clicked")
                                    viewModel.loadDemoData() 
                                },
                                onManualClick = { 
                                    Log.d(TAG, "onManualClick clicked - navigating to MANUAL screen")
                                    Toast.makeText(this@MainActivity, "Tombol diklik!", Toast.LENGTH_SHORT).show()
                                    viewModel.onManualInput() 
                                },
                                onClearError = { viewModel.clearError() }
                            )
                        }
                        Screen.MANUAL -> {
                            Log.d(TAG, "Rendering MANUAL screen")
                            ManualInputScreen(
                                manualTransactions = uiState.transactions,
                                onAddTransaction = { viewModel.addManualTransaction(it) },
                                onDeleteTransaction = { viewModel.deleteManualTransaction(it) },
                                onNext = { viewModel.onManualNextToSelect() },
                                onBack = { viewModel.onBackToScan() }
                            )
                        }
                        Screen.SELECT -> {
                            SelectScreen(
                                cardInfo = uiState.cardInfo,
                                transactions = uiState.transactions,
                                onTransactionToggle = { viewModel.toggleTransactionSelection(it) },
                                onNext = { viewModel.onNextToInput() },
                                onBack = { viewModel.onBackToScan() }
                            )
                        }
                        Screen.INPUT -> {
                            InputScreen(
                                userName = uiState.userName,
                                selectedCount = uiState.transactions.count { it.isSelected },
                                totalAmount = uiState.transactions.filter { it.isSelected }.sumOf { it.amount },
                                onNameChange = { viewModel.onNameChanged(it) },
                                onGenerate = { viewModel.onGeneratePdf() },
                                onBack = { viewModel.onBackToSelect() }
                            )
                        }
                        Screen.PREVIEW -> {
                            PreviewScreen(
                                userName = uiState.userName,
                                transactions = uiState.transactions.filter { it.isSelected },
                                onBack = { viewModel.onBackToInput() },
                                onDone = { viewModel.onBackToScan() }
                            )
                        }
                    }
                }
            }
        }
        
        // Handle NFC intent when app is launched by NFC tag
        handleNfcIntent(intent)
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - enabling foreground dispatch")
        // Use foreground dispatch instead of reader mode for better UI compatibility
        nfcAdapter?.enableForegroundDispatch(
            this, pendingIntent, null,
            arrayOf(
                arrayOf(MifareClassic::class.java.name),
                arrayOf(android.nfc.tech.NfcA::class.java.name)
            )
        )
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause - disabling foreground dispatch")
        nfcAdapter?.disableForegroundDispatch(this)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent: ${intent?.action}")
        handleNfcIntent(intent)
    }
    
    private fun handleNfcIntent(intent: Intent?) {
        if (intent?.action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent?.action == NfcAdapter.ACTION_TAG_DISCOVERED) {
            
            Log.d(TAG, "NFC Intent received: ${intent.action}")
            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            tag?.let {
                Log.d(TAG, "Tag from intent: ${it.id.toHex()}")
                Toast.makeText(this, "Kartu terdeteksi! Membaca...", Toast.LENGTH_SHORT).show()
                readNfcCard(it)
            }
        }
    }
    
    private fun readNfcCard(tag: Tag) {
        Log.d(TAG, "readNfcCard called")
        viewModel.setLoading(true)
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = nfcCardReader.readCard(tag)
                withContext(Dispatchers.Main) {
                    when (result) {
                        is CardReadResult.Success -> {
                            Log.d(TAG, "Card read success: ${result.transactions.size} transactions")
                            if (result.transactions.isEmpty()) {
                                viewModel.onScanError("Kartu terbaca tapi tidak ada transaksi ditemukan. Coba kartu lain.")
                            } else {
                                viewModel.onCardScanned(result.cardInfo, result.transactions)
                            }
                        }
                        is CardReadResult.Error -> {
                            Log.e(TAG, "Card read error: ${result.message}")
                            viewModel.onScanError(result.message)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error", e)
                withContext(Dispatchers.Main) {
                    viewModel.onScanError("Error tidak terduga: ${e.message}")
                }
            }
        }
    }
    
    private fun checkNfcAndPrompt() {
        when {
            nfcAdapter == null -> {
                Toast.makeText(this, "NFC tidak tersedia di perangkat ini", Toast.LENGTH_LONG).show()
            }
            nfcAdapter?.isEnabled == false -> {
                Toast.makeText(this, "Silakan aktifkan NFC", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            }
            else -> {
                Toast.makeText(this, "Tempelkan kartu ke belakang ponsel", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun ByteArray.toHex(): String {
    return joinToString("") { "%02X".format(it) }
}
