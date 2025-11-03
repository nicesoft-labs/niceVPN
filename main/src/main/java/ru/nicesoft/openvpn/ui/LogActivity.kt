package ru.nicesoft.openvpn.ui

import android.os.Bundle
import android.view.MenuItem
import android.widget.AbsListView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import de.blinkt.openvpn.R
import de.blinkt.openvpn.core.LogItem
import de.blinkt.openvpn.core.VpnStatus

class LogActivity : AppCompatActivity(), VpnStatus.LogListener {

    private lateinit var logList: ListView
    private lateinit var logAdapter: LogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.openvpn_log)

        logAdapter = LogAdapter(this)

        logList = findViewById(R.id.log_list)
        val emptyView: TextView = findViewById(R.id.log_empty_view)
        logList.emptyView = emptyView
        logList.adapter = logAdapter
        logList.transcriptMode = AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL

        val initialLogs = VpnStatus.getlogbuffer().toList()
        logAdapter.setItems(initialLogs)
        if (logAdapter.count > 0) {
            logList.post { logList.setSelection(logAdapter.count - 1) }
        }
    }

    override fun onStart() {
        super.onStart()
        VpnStatus.addLogListener(this)
    }

    override fun onStop() {
        VpnStatus.removeLogListener(this)
        super.onStop()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun newLog(logItem: LogItem) {
        runOnUiThread {
            logAdapter.addLogItem(logItem)
            if (logAdapter.count > 0) {
                logList.setSelection(logAdapter.count - 1)
            }
        }
    }
}
