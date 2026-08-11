package com.towerscope.ar

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.network.DnsLookup
import com.towerscope.ar.ui.SystemBars
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class DnsLookupActivity : AppCompatActivity() {

    private lateinit var queryInput: EditText
    private lateinit var statusLabel: TextView
    private lateinit var resultView: TextView
    private lateinit var lookupButton: MaterialButton
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_dns_lookup)
        SystemBars.apply(findViewById(R.id.dnsRoot))

        queryInput = findViewById(R.id.dnsQueryInput)
        statusLabel = findViewById(R.id.dnsStatus)
        resultView = findViewById(R.id.dnsResultView)
        lookupButton = findViewById(R.id.dnsLookupButton)

        lookupButton.setOnClickListener { runLookup() }
        findViewById<MaterialButton>(R.id.dnsBackButton).setOnClickListener {
            job?.cancel()
            finish()
        }
    }

    override fun onStop() {
        job?.cancel()
        super.onStop()
    }

    private fun runLookup() {
        job?.cancel()
        val query = queryInput.text?.toString().orEmpty()
        lookupButton.isEnabled = false
        statusLabel.text = "Resolving…"
        resultView.text = "…"
        job = lifecycleScope.launch {
            runCatching {
                DnsLookup.lookup(this@DnsLookupActivity, query)
            }.onSuccess { result ->
                statusLabel.text = buildString {
                    append(DnsLookup.formatMs(result.elapsedMs))
                    result.networkNote?.let { append("  ·  ").append(it) }
                }
                resultView.text = buildString {
                    append("Query  ").append(result.query).append('\n')
                    if (result.records.isEmpty()) {
                        append("\nNo records")
                    } else {
                        append('\n')
                        result.records.forEach { rec ->
                            append(
                                String.format(Locale.US, "%-5s  %s\n", rec.type, rec.value)
                            )
                        }
                    }
                    result.reverseName?.let {
                        append("\nReverse  ").append(it)
                    }
                }
            }.onFailure { e ->
                statusLabel.text = "Lookup failed"
                resultView.text = e.message ?: "DNS error"
            }
            lookupButton.isEnabled = true
        }
    }
}
