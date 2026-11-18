package com.retrograb.mobile

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

internal class AppUpdateViewModel(application: Application, private val savedState: SavedStateHandle) :
    AndroidViewModel(application) {
    private val updater = (application as RetroGrabApp).appUpdater
    private val messageQueue = Channel<UpdateMessage>(Channel.BUFFERED)
    val messages = messageQueue.receiveAsFlow()
    var offer by mutableStateOf(
        savedState.get<String>(REQUIRED_UPDATE)?.let(AppUpdate::decode)?.takeIf {
            AppUpdater.compareVersions(it.version, BuildConfig.VERSION_NAME) > 0
        }
    )
        private set
    val requiresUpdate: Boolean get() = offer?.level == UpdateLevel.REQUIRED
    var checking by mutableStateOf(false)
        private set
    var changingNotifications by mutableStateOf(false)
        private set
    var downloadProgress by mutableStateOf<DownloadProgress?>(null)
        private set
    var showInstallExplanation by mutableStateOf(savedState.get<Boolean>(EXPLAIN_INSTALL) == true)
        private set
    var pendingApk by mutableStateOf(savedState.get<String>(PENDING_APK))
        private set
    private var downloadJob: Job? = null
    private var skippingVersion: String? = null
    var starting by mutableStateOf(true)
        private set

    init {
        if (!requiresUpdate && savedState.remove<String>(REQUIRED_UPDATE) != null) {
            finishInstall(deleteApk = false)
        }
        viewModelScope.launch {
            try {
                updater.cleanCache(pendingApk)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("AppUpdater", "Could not clean the update cache", error)
            } finally {
                starting = false
            }
            checkForUpdates(manual = false)
            try {
                updater.syncNotificationWork()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("AppUpdater", "Could not schedule periodic app updates", error)
            }
        }
    }

    fun checkForUpdates(manual: Boolean = true) {
        if (manual && checking) return
        if (manual) checking = true
        viewModelScope.launch {
            try {
                val update = updater.findUpdate(refresh = manual, keepPriorityOnError = !manual)
                if (!manual && (update == null || update.level == UpdateLevel.NORMAL) &&
                    !updater.claimStartupCheck(update?.version)
                ) return@launch
                when {
                    update == null && manual -> message("You're on the latest version (${BuildConfig.VERSION_NAME})")
                    update == null -> Unit
                    !manual && update.level == UpdateLevel.NORMAL &&
                        (update.version == skippingVersion || updater.isSkipped(update.version)) -> Unit
                    else -> showOffer(update)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("AppUpdater", "App update check failed", error)
                if (manual) message("Couldn't check for updates")
            } finally {
                if (manual) checking = false
            }
        }
    }

    fun openNotification(version: String) {
        viewModelScope.launch {
            try {
                val update = updater.notificationUpdate(version) ?: return@launch
                if (update.level != UpdateLevel.NORMAL || update.version != skippingVersion) showOffer(update)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("AppUpdater", "Could not open update notification", error)
                message("Couldn't check for updates")
            }
        }
    }

    private fun showOffer(update: AppUpdate) {
        if (update.level != UpdateLevel.REQUIRED &&
            (requiresUpdate || downloadProgress != null || pendingApk != null)
        ) return
        if (offer?.let {
                val versions = AppUpdater.compareVersions(update.version, it.version)
                versions < 0 || (versions == 0 && update.level < it.level)
            } == true
        ) return
        if (update.level == UpdateLevel.REQUIRED) savedState[REQUIRED_UPDATE] = update.encode()
        offer = update
    }

    fun dismissOffer() {
        if (!requiresUpdate) offer = null
    }

    fun skipOffer() {
        val update = offer ?: return
        if (update.level != UpdateLevel.NORMAL) return
        dismissOffer()
        skippingVersion = update.version
        viewModelScope.launch {
            try {
                updater.skip(update.version)
                messageQueue.trySend(
                    UpdateMessage("Skipped ${update.version}. You can still update from Settings.", update.version)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("AppUpdater", "Could not save skipped update", error)
                message("Couldn't skip this version")
            } finally {
                if (skippingVersion == update.version) skippingVersion = null
            }
        }
    }

    fun undoSkip(version: String) {
        viewModelScope.launch {
            try {
                updater.clearSkip(version)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("AppUpdater", "Could not undo skipped update", error)
                message("Couldn't undo the skip")
            }
        }
    }

    fun setNotifications(enabled: Boolean) {
        if (changingNotifications) return
        changingNotifications = true
        viewModelScope.launch {
            try {
                updater.syncNotificationWork(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("AppUpdater", "Could not change app update notifications", error)
                message("Couldn't change app update notifications")
            } finally {
                changingNotifications = false
            }
        }
    }

    fun download(update: AppUpdate) {
        if (downloadJob?.isCompleted == false) return
        val progress = DownloadProgress()
        downloadProgress = progress
        dismissOffer()
        downloadJob = viewModelScope.launch {
            try {
                updater.cleanCache(pendingApk)
                val apk = updater.download(update) { progress.percent = it }
                savedState[PENDING_APK] = apk.name
                pendingApk = apk.name
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("AppUpdater", "Could not download app update", error)
                downloadProgress = null
                message("Couldn't download the update")
            } finally {
                if (downloadProgress == progress) downloadProgress = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        if (!requiresUpdate) downloadProgress = null
    }

    fun pendingFile(): File? = pendingApk?.let(updater::cachedApk)?.takeIf(File::isFile)

    fun explainInstall() {
        setExplanation(true)
    }

    fun openingInstallSettings() {
        setExplanation(false)
        savedState[WAITING_INSTALL_PERMISSION] = true
    }

    fun shouldStartInstall(): Boolean =
        !starting && pendingApk != null && savedState.get<Boolean>(WAITING_INSTALL_PERMISSION) != true &&
            savedState.get<Boolean>(WAITING_INSTALL_RESULT) != true

    fun awaitingInstallResult() {
        setExplanation(false)
        savedState.remove<Boolean>(WAITING_INSTALL_PERMISSION)
        savedState[WAITING_INSTALL_RESULT] = true
    }

    fun finishInstall(deleteApk: Boolean) {
        if (deleteApk) pendingFile()?.delete()
        savedState.remove<String>(PENDING_APK)
        savedState.remove<Boolean>(WAITING_INSTALL_PERMISSION)
        savedState.remove<Boolean>(WAITING_INSTALL_RESULT)
        pendingApk = null
        setExplanation(false)
    }

    private fun setExplanation(show: Boolean) {
        savedState[EXPLAIN_INSTALL] = show
        showInstallExplanation = show
    }

    private fun message(text: String) {
        messageQueue.trySend(UpdateMessage(text))
    }

    internal data class UpdateMessage(val text: String, val skippedVersion: String? = null)

    internal class DownloadProgress {
        var percent by mutableIntStateOf(0)
    }

    private companion object {
        const val PENDING_APK = "pendingUpdateApk"
        const val EXPLAIN_INSTALL = "explainUpdateInstall"
        const val WAITING_INSTALL_PERMISSION = "waitingForUpdateInstallPermission"
        const val WAITING_INSTALL_RESULT = "waitingForUpdateInstallResult"
        const val REQUIRED_UPDATE = "requiredUpdate"
    }
}
