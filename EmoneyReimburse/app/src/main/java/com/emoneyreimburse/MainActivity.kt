package com.emoneyreimburse

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
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
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.emoneyreimburse.nfc.CardReadResult
import com.emoneyreimburse.nfc.NfcCardReader
import com.emoneyreimburse.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    
    private var nfcAdapter: NfcAdapter? = null
    private val viewModel: MainViewModel by viewModels()
    private val nfcCardReader = NfcCardReader()
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val uiState = viewModel.uiState.value
                    
                    when (uiState.currentScreen) {
                        Screen.SCAN -> {
                            ScanScreen(
                                isLoading = uiState.isLoading,
                                errorMessage = uiState.errorMessage,
                                onNfcPrompt = { checkNfcAndPrompt() },
                                onDemoClick = { viewModel.loadDemoData() },
                                onClearError = { viewModel.clearError() }
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
        // Enable reader mode for better NFC handling
        nfcAdapter?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }
    
    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }
    
    override fun onTagDiscovered(tag: Tag?) {
        tag?.let {
            runOnUiThread {
                readNfcCard(it)
            }
        }
    }
    
    private fun handleNfcIntent(intent: Intent?) {
        if (intent?.action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent?.action == NfcAdapter.ACTION_TAG_DISCOVERED) {
            
            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            tag?.let {
                readNfcCard(it)
            }
        }
    }
    
    private fun readNfcCard(tag: Tag) {
        viewModel.setLoading(true)
        
        lifecycleScope.launch {
            val result = nfcCardReader.readCard(tag)
            when (result) {
                is CardReadResult.Success -> {
                    viewModel.onCardScanned(result.cardInfo, result.transactions)
                }
                is CardReadResult.Error -> {
                    viewModel.onScanError(result.message)
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
