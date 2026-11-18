package com.retrograb.mobile

import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val tabKey = stringPreferencesKey("tab")

internal fun Source.icon(): ImageVector = when (this) {
    Source.YOUTUBE -> Icons.Rounded.SmartDisplay
    Source.INSTAGRAM -> Icons.Rounded.PhotoCamera
    Source.SPOTIFY -> Icons.Rounded.MusicNote
}

private enum class Page { TABS, SETTINGS, LOGIN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetroGrabScreen(activity: MainActivity) {
    val prefs by activity.settings.data.collectAsState(initial = emptyPreferences())
    val languageQuestion by LanguagePrompt.question.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val selected = Source.entries.firstOrNull { it.name == prefs[tabKey] } ?: Source.SPOTIFY
    val manager = remember(activity) { WorkManager.getInstance(activity) }
    val work = observeWork(manager, prefs[lastWorkKey])
    val tabWork = observeWork(manager, prefs[sourceWorkKey(selected)])
    val running = work?.state?.isFinished == false
    val busy = running || activity.starting.value
    var cancelling by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(Page.TABS) }
    val app = activity.application as RetroGrabApp
    val appUpdates = activity.appUpdates
    val requiresUpdate = appUpdates.requiresUpdate
    var ytDlpVersion by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var historySelected by rememberSaveable { mutableStateOf(false) }
    var youtube by rememberSaveable { mutableStateOf("") }
    var instagram by rememberSaveable { mutableStateOf("") }
    var spotify by rememberSaveable { mutableStateOf("") }
    val session = remember(activity) { InstagramSession(activity) }
    var loggedIn by remember { mutableStateOf(session.loggedIn) }

    fun setLink(source: Source, text: String) {
        when (source) {
            Source.YOUTUBE -> youtube = text
            Source.INSTAGRAM -> instagram = text
            Source.SPOTIFY -> spotify = text
        }
    }

    fun setText(key: String, value: String) {
        scope.launch { activity.settings.edit { it[stringPreferencesKey(key)] = value } }
    }

    fun setFlag(key: String, value: Boolean) {
        scope.launch { activity.settings.edit { it[booleanPreferencesKey(key)] = value } }
    }

    LaunchedEffect(activity, snackbar) {
        activity.messages.collect { snackbar.showSnackbar(it) }
    }

    LaunchedEffect(appUpdates, snackbar) {
        appUpdates.messages.collect { message ->
            val version = message.skippedVersion
            val result = snackbar.showSnackbar(
                message = message.text,
                actionLabel = if (version == null) null else "Undo",
                duration = if (version == null) SnackbarDuration.Short else SnackbarDuration.Long
            )
            if (version != null && result == SnackbarResult.ActionPerformed) appUpdates.undoSkip(version)
        }
    }

    LaunchedEffect(page, work?.state?.isFinished, updating) {
        if (page == Page.SETTINGS && !updating) {
            app.awaitStartupUpdate {}
            ytDlpVersion = currentYtDlpVersion(app)
        }
    }

    var observedActiveWork by remember { mutableStateOf<UUID?>(null) }
    LaunchedEffect(work?.id, work?.state) {
        if (work != null) {
            if (!work.state.isFinished) observedActiveWork = work.id
            if (work.state == WorkInfo.State.FAILED && observedActiveWork == work.id) {
                activity.showMessage(work.outputData.getString("log").orEmpty().lineSequence()
                    .lastOrNull { it.isNotBlank() } ?: "Download failed.")
            }
        }
    }

    val incoming by activity.incoming
    LaunchedEffect(incoming) {
        val url = incoming ?: return@LaunchedEffect
        val source = Source.fromUrl(url) ?: return@LaunchedEffect
        setLink(source, url)
        activity.settings.edit { it[tabKey] = source.name }
        historySelected = false
        page = Page.TABS
        if (activity.incoming.value == url) activity.incoming.value = null
    }

    PredictiveBackHandler(enabled = languageQuestion == null && !requiresUpdate && page != Page.TABS) { events ->
        events.collect {}
        page = Page.TABS
    }

    val theme = prefs[stringPreferencesKey("theme")] ?: when (prefs[booleanPreferencesKey("dark")]) {
        true -> "Dark"
        false -> "Light"
        null -> "System"
    }
    val accentName = prefs[stringPreferencesKey(ACCENT_SETTING)] ?: ACCENTS.first().name
    val savedHue = prefs[stringPreferencesKey(ACCENT_HUE_SETTING)]?.toFloatOrNull() ?: DEFAULT_ACCENT_HUE
    // while dragging the spectrum the colour previews live; it is saved when the finger lifts
    var hueDraft by remember { mutableStateOf<Float?>(null) }
    val accentHue = hueDraft ?: savedHue
    val accent = if (hueDraft != null) customAccent(accentHue) else accentFor(accentName, accentHue)

    RetroGrabTheme(activity, theme, accent) {
      PopBackground(Modifier.fillMaxSize()) {
        val pop = LocalPop.current
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            containerColor = Color.Transparent,
            contentColor = pop.ink,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (page == Page.TABS && !requiresUpdate) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.retrograb_logo),
                            contentDescription = null,
                            modifier = Modifier.size(42.dp)
                        )
                        Text("RetroGrab", style = MaterialTheme.typography.titleLarge, color = pop.ink,
                            modifier = Modifier.weight(1f))
                        PopIconButton(Icons.Rounded.Settings, "Settings") { page = Page.SETTINGS }
                    }
                } else if (!requiresUpdate) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PopIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { page = Page.TABS }
                        Text(if (page == Page.SETTINGS) "Settings" else "Log in to Instagram",
                            style = MaterialTheme.typography.titleLarge, color = pop.ink)
                    }
                }
            },
            bottomBar = {
                if (page == Page.TABS && !requiresUpdate) {
                    Box(Modifier.navigationBarsPadding()) {
                        PopNavBar(
                            selectedIndex = if (historySelected) Source.entries.size else selected.ordinal,
                            itemCount = Source.entries.size + 1
                        ) {
                            Source.entries.forEach { source ->
                                PopNavItem(
                                    selected = !historySelected && source == selected,
                                    onClick = {
                                        historySelected = false
                                        if (source != selected) setText("tab", source.name)
                                    },
                                    icon = source.icon(),
                                    label = source.label
                                )
                            }
                            PopNavItem(
                                selected = historySelected,
                                onClick = { historySelected = true },
                                icon = Icons.Rounded.History,
                                label = "History"
                            )
                        }
                    }
                }
            }
        ) { insets ->
            if (requiresUpdate) return@Scaffold
            val content = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
            when (page) {
                Page.SETTINGS -> SettingsScreen(
                    modifier = content,
                    theme = theme,
                    onThemeChange = { setText("theme", it) },
                    accentName = if (hueDraft != null) ACCENT_CUSTOM else accentName,
                    accentHue = accentHue,
                    onAccentChange = { setText(ACCENT_SETTING, it) },
                    onHuePreview = { hueDraft = it },
                    onHueChosen = { hue ->
                        scope.launch {
                            activity.settings.edit {
                                it[stringPreferencesKey(ACCENT_HUE_SETTING)] = hue.toString()
                                it[stringPreferencesKey(ACCENT_SETTING)] = ACCENT_CUSTOM
                            }
                            hueDraft = null
                        }
                    },
                    version = ytDlpVersion,
                    updateEnabled = !busy && !updating,
                    updating = updating,
                    onUpdate = {
                        updating = true
                        scope.launch {
                            try {
                                val result = updateYtDlpFromSettings(app)
                                val version = currentYtDlpVersion(app)
                                ytDlpVersion = version
                                activity.showMessage("$result Current yt-dlp version: $version")
                            } finally {
                                updating = false
                            }
                        }
                    },
                    checkingAppUpdate = appUpdates.checking,
                    onCheckAppUpdate = { appUpdates.checkForUpdates() },
                    appUpdateNotifications = prefs[appUpdateNotificationsKey] ?: true,
                    changingAppUpdateNotifications = appUpdates.changingNotifications,
                    onAppUpdateNotificationsChange = activity::setUpdateNotifications,
                    spotifyLanguageMode = prefs[stringPreferencesKey(SPOTIFY_LANGUAGE_SETTING)] ?: SPOTIFY_LANGUAGE_ASK,
                    onSpotifyLanguageModeChange = { setText(SPOTIFY_LANGUAGE_SETTING, it) }
                )
                Page.LOGIN -> InstagramWebView(content, activity::showMessage) {
                    runCatching { session.capture() }.onSuccess { captured ->
                        if (captured) {
                            loggedIn = true
                            page = Page.TABS
                        }
                    }.onFailure { activity.showMessage("Could not save the Instagram session.") }
                }
                Page.TABS -> AnimatedContent(
                    targetState = if (historySelected) Source.entries.size else selected.ordinal,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 4 * dir } +
                            fadeIn(tween(220, delayMillis = 60))) togetherWith
                            (slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it / 4 * dir } +
                                fadeOut(tween(160)))
                    },
                    label = "tabs"
                ) { tab ->
                if (tab == Source.entries.size) {
                    HistoryScreen(activity, work, content)
                } else {
                    val selected = Source.entries[tab]
                    val link = when (selected) {
                        Source.YOUTUBE -> youtube
                        Source.INSTAGRAM -> instagram
                        Source.SPOTIFY -> spotify
                    }
                    key(selected) {
                        DownloadScreen(
                            source = selected,
                            prefs = prefs,
                            link = link,
                            onLinkChange = { setLink(selected, it) },
                            onPaste = {
                                val pasted = activity.paste()
                                if (pasted.isNullOrBlank()) {
                                    activity.showMessage("The clipboard has no text.")
                                } else {
                                    setLink(selected, extractLink(pasted) ?: pasted)
                                }
                            },
                            setText = ::setText,
                            setFlag = ::setFlag,
                            loggedIn = loggedIn,
                            onLogin = { page = Page.LOGIN },
                            onLogout = { session.logout { loggedIn = session.loggedIn } },
                            onDownload = { activity.download(selected, link, prefs.options(selected)) },
                            busy = busy,
                            showCancel = running,
                            cancelling = cancelling,
                            onCancel = {
                                cancelling = true
                                scope.launch {
                                    try {
                                        val operation = manager.cancelUniqueWork(DOWNLOAD_WORK_NAME)
                                        withContext(Dispatchers.IO) { operation.result.get() }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        activity.showMessage("Could not cancel the download.")
                                    } finally {
                                        cancelling = false
                                    }
                                }
                            },
                            work = if (selected.name == (prefs[tabKey] ?: Source.SPOTIFY.name)) tabWork else
                                observeWork(manager, prefs[sourceWorkKey(selected)]),
                            modifier = content
                        )
                    }
                }
                }
            }
        }
        AppUpdateDialogs(activity)
        languageQuestion?.let { question ->
            key(question.id) { LanguageQuestionDialog(question) }
        }
      }
    }
}

@Composable
private fun observeWork(manager: WorkManager, id: String?): WorkInfo? = key(id) {
    if (id == null) null else {
        val flow = remember(manager, id) { manager.getWorkInfoByIdFlow(UUID.fromString(id)) }
        flow.collectAsState(initial = null).value
    }
}

@Composable
private fun InstagramWebView(modifier: Modifier, showMessage: (String) -> Unit, onLogin: () -> Unit) {
    val callback by rememberUpdatedState(onLogin)
    var attempt by remember { mutableIntStateOf(0) }
    if (attempt > 2) {
        LaunchedEffect(Unit) { showMessage("Login page crashed. Tap Retry.") }
        Column(
            modifier = modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(onClick = { attempt = 0 }) { Text("Retry") }
        }
        return
    }

    key(attempt) {
        AndroidView(
            factory = { context ->
                val container = FrameLayout(context)
                val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }
                val webView = WebView(context).apply {
                    if (attempt > 0) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(" Version/4.0", "")
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val host = request.url.host.orEmpty()
                            return request.isForMainFrame && (request.url.scheme != "https" ||
                                (host != "instagram.com" && !host.endsWith(".instagram.com")))
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (view.parent !== container || url == null || Source.fromUrl(url) != Source.INSTAGRAM) return
                            val hasSession = cookies.getCookie("https://www.instagram.com/").orEmpty()
                                .split(';').any { cookie ->
                                    cookie.substringBefore('=').trim() == "sessionid" &&
                                        cookie.substringAfter('=', "").isNotBlank()
                                }
                            if (hasSession) callback()
                        }

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            if (view.parent === container) {
                                container.removeView(view)
                                view.destroy()
                                attempt++
                            }
                            return true
                        }
                    }
                }
                container.addView(webView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                webView.loadUrl("https://www.instagram.com/accounts/login/")
                container
            },
            modifier = modifier,
            onReset = null,
            onRelease = { container ->
                (container.getChildAt(0) as? WebView)?.let { webView ->
                    container.removeView(webView)
                    webView.stopLoading()
                    webView.destroy()
                }
            },
            update = {}
        )
    }
}
