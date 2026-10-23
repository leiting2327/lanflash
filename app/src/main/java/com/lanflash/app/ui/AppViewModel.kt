package com.lanflash.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lanflash.app.license.LicenseInfo
import com.lanflash.app.license.LicenseManager
import com.lanflash.app.net.PairInfo
import com.lanflash.app.net.Receiver
import com.lanflash.app.net.SendProgress
import com.lanflash.app.net.Sender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val receiver = Receiver(app)
    val sender = Sender(app)
    val license = LicenseManager(app)

    var licenseInfo by mutableStateOf<LicenseInfo?>(license.cached())
        private set
    var licenseError by mutableStateOf<String?>(null)
    var activating by mutableStateOf(false)
        private set

    var sendProgress by mutableStateOf(SendProgress())
    var sending by mutableStateOf(false)
        private set
    var sendDone by mutableStateOf(false)
    var sendError by mutableStateOf<String?>(null)

    fun startReceive(): PairInfo? = try {
        receiver.start()
    } catch (t: Throwable) {
        null
    }

    fun stopReceive() {
        receiver.stop(keepState = true)
    }

    fun send(pair: PairInfo, uris: List<Uri>) {
        if (sending) return
        sending = true
        sendDone = false
        sendError = null
        sendProgress = SendProgress()
        viewModelScope.launch {
            val result = sender.send(pair, uris) { p -> sendProgress = p }
            sending = false
            if (result.isSuccess) {
                sendDone = true
            } else {
                sendError = result.exceptionOrNull()?.message ?: "发送失败"
            }
        }
    }

    fun resetSend() {
        sending = false
        sendDone = false
        sendError = null
        sendProgress = SendProgress()
    }

    fun activate(code: String) {
        if (activating) return
        activating = true
        licenseError = null
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { license.activate(code) }
                licenseInfo = info
                licenseError = null
            } catch (t: Throwable) {
                licenseError = t.message ?: "激活失败"
            } finally {
                activating = false
            }
        }
    }

    fun clearLicense() {
        license.clear()
        licenseInfo = null
    }

    override fun onCleared() {
        receiver.stop()
        super.onCleared()
    }
}
