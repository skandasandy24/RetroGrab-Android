package com.retrograb.mobile

import android.Manifest
import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

val lastWorkKey = stringPreferencesKey("lastWork")
fun sourceWorkKey(source: Source) = stringPreferencesKey("lastWork.${source.name}")
internal const val DOWNLOAD_WORK_NAME = "retrograb-download"

class MainActivity : ComponentActivity() {
    internal val appUpdates by lazy { ViewModelProvider(this)[AppUpdateViewModel::class.java] }
    val incoming = mutableStateOf<String?>(null)
    private val messageQueue = Channel<String>(Channel.BUFFERED)
    val messages = messageQueue.receiveAsFlow()
    val starting = mutableStateOf(false)
    private var pendingDownload: (() -> Unit)? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        submitPending()
    }
    private val updateNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (canInstallUpdates()) {
            lifecycleScope.launch { lifecycle.withResumed { installUpdate() } }
        } else {
            appUpdates.finishInstall(deleteApk = true)
            showMessage("Permission to install the update wasn't granted")
        }
    }
    private val updateInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        appUpdates.finishInstall(deleteApk = result.resultCode != Activity.RESULT_OK)
    }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestNotification() else {
            pendingDownload = null
            starting.value = false
            showMessage("Storage permission is required to save files on this version of Android.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        handleUpdateIntent(intent)
        setContent { RetroGrabScreen(this) }
        if (needsNotificationPermission()) {
            lifecycleScope.launch {
                try {
                    val updater = (application as RetroGrabApp).appUpdater
                    if (updater.claimNotificationPermissionPrompt()) requestUpdateNotificationPermission()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("AppUpdater", "Could not request update notification permission", error)
                }
            }
        }
    }

    fun showMessage(message: String) {
        messageQueue.trySend(message)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
        handleUpdateIntent(intent)
        intent.getStringExtra(EXTRA_LANGUAGE_QUESTION)?.let { questionId ->
            if (questionId == LanguagePrompt.question.value?.id) currentFocus?.clearFocus()
        }
    }

    private fun handleUpdateIntent(intent: Intent) {
        val version = intent.getStringExtra(EXTRA_APP_UPDATE_VERSION) ?: return
        intent.removeExtra(EXTRA_APP_UPDATE_VERSION)
        appUpdates.openNotification(version)
    }

    internal fun setUpdateNotifications(enabled: Boolean) {
        appUpdates.setNotifications(enabled)
        if (enabled) requestUpdateNotificationPermission()
    }

    private fun needsNotificationPermission(): Boolean = Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun requestUpdateNotificationPermission() {
        if (needsNotificationPermission()) {
            updateNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    internal fun confirmUpdate(update: AppUpdate) {
        if (update.apkUrl != null) {
            appUpdates.download(update)
        } else {
            appUpdates.dismissOffer()
            try {
                check(AppUpdater.githubUrl(update.releaseUrl, "/releases/tag/"))
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (error: Exception) {
                Log.w("AppUpdater", "Could not open the update release", error)
                showMessage("Couldn't open the update release")
            }
        }
    }

    private fun canInstallUpdates(): Boolean =
        Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    internal fun continueUpdateInstall() {
        if (canInstallUpdates()) installUpdate() else appUpdates.explainInstall()
    }

    internal fun openInstallPermissionSettings() {
        if (canInstallUpdates()) {
            installUpdate()
            return
        }
        appUpdates.openingInstallSettings()
        try {
            installPermission.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", packageName, null))
            )
        } catch (error: Exception) {
            Log.w("AppUpdater", "Could not open install permission settings", error)
            appUpdates.finishInstall(deleteApk = true)
            showMessage("Couldn't open the settings to allow app installs")
        }
    }

    @Suppress("DEPRECATION")
    private fun installUpdate() {
        try {
            val apk = checkNotNull(appUpdates.pendingFile())
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", apk)
            if (appUpdates.requiresUpdate) {
                appUpdates.awaitingInstallResult()
                updateInstaller.launch(
                    Intent(Intent.ACTION_INSTALL_PACKAGE, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                )
            } else {
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
                appUpdates.finishInstall(deleteApk = false)
            }
        } catch (error: Exception) {
            Log.w("AppUpdater", "Could not start the APK installer", error)
            appUpdates.finishInstall(deleteApk = true)
            showMessage("Couldn't open the app installer")
        }
    }

    private fun handleShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type != ClipDescription.MIMETYPE_TEXT_PLAIN) return
        val link = extractLink(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
        if (link != null) incoming.value = link else showMessage("No supported link found in the shared text.")
    }

    fun paste(): String? = getSystemService(ClipboardManager::class.java)
        .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()

    fun download(source: Source, rawUrl: String, options: Map<String, Any>) {
        if (starting.value) return
        val url = extractLink(rawUrl)
        if (url == null || Source.fromUrl(url) != source || url.length > 2000) {
            showMessage("Enter a valid ${source.label} link.")
            return
        }
        starting.value = true
        pendingDownload = {
            lifecycleScope.launch {
                try {
                    val manager = WorkManager.getInstance(this@MainActivity)
                    val previous = settings.data.first()[lastWorkKey]
                    val previousId = previous?.let(java.util.UUID::fromString)
                    if (previousId != null) {
                        val active = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            manager.getWorkInfoById(previousId).get()?.state?.isFinished == false
                        }
                        if (active) {
                            showMessage("A download is already running.")
                            return@launch
                        }
                    }
                    val work = OneTimeWorkRequestBuilder<DownloadWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setInputData(workDataOf("url" to url, *options.map { it.key to it.value }.toTypedArray()))
                        .build()
                    val operation = manager.enqueueUniqueWork(DOWNLOAD_WORK_NAME, ExistingWorkPolicy.KEEP, work)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { operation.result.get() }
                    settings.edit {
                        it[lastWorkKey] = work.id.toString()
                        it[sourceWorkKey(source)] = work.id.toString()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    showMessage(error.message ?: "Could not schedule the download.")
                } finally {
                    starting.value = false
                }
            }
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else requestNotification()
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else submitPending()
    }

    private fun submitPending() {
        val submit = pendingDownload
        pendingDownload = null
        submit?.invoke()
    }

    fun open(item: HistoryItem) {
        if (item.uri.isBlank()) return
        val uri = Uri.parse(item.uri)
        try {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (_: Exception) {
            showMessage("This file is unavailable or there is no app that can open it.")
        }
    }
}

fun extractLink(text: String): String? = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)
    .findAll(text).map { it.value.trimEnd('.', ',', ')', ']', ';', '\'') }
    .firstOrNull { Source.fromUrl(it) != null }

