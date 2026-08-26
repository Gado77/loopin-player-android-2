package com.loopin.player2

import android.app.Activity
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.loopin.player2.core.model.DeviceRuntimeState
import com.loopin.player2.core.model.LogLevel
import com.loopin.player2.core.model.RuntimePhase
import com.loopin.player2.core.playback.LoopingPlaybackEngine
import com.loopin.player2.core.playback.Media3ItemPlayer
import com.loopin.player2.core.playback.PlaybackEngine
import com.loopin.player2.core.playback.PlaybackSnapshot
import com.loopin.player2.core.playback.PlaybackState
import com.loopin.player2.core.playback.PlaybackSurface
import com.loopin.player2.core.playback.WeatherItemPlayer
import com.loopin.player2.core.playback.WeatherBackgroundMediaResolver
import com.loopin.player2.core.playback.WeatherBackgroundCatalog
import com.loopin.player2.core.operations.PairingState
import com.loopin.player2.core.operations.OperationalLogEvent
import com.loopin.player2.core.content.ContentLogEvent
import com.loopin.player2.core.content.ContentPresentation
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private val container by lazy { (application as LoopinApplication).container }
    private val kioskController by lazy { KioskController(this, container.logger) }
    private var stateSubscription: AutoCloseable? = null
    private var playbackSubscription: AutoCloseable? = null
    private var playbackEngine: PlaybackEngine? = null
    private lateinit var playbackSurface: PlaybackSurface
    private lateinit var identityPanel: View
    private lateinit var statusView: TextView
    private lateinit var pairingCodeView: TextView
    private lateinit var pairingQrView: ImageView
    private lateinit var pairingHintView: TextView
    private var pairingCoordinator: PairingCoordinator? = null
    private var diagnosticView: TextView? = null
    private var latestPlayback = PlaybackSnapshot()
    private var diagnosticsVisible = false
    private var lastLoggedItemId: String? = null
    private var lastLoggedPlaybackState: PlaybackState? = null
    private var dynamicContent: DynamicContentController? = null
    private val weatherProvider by lazy { createLocalWeatherRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createPlaybackScreen())
        stateSubscription = container.stateManager.subscribe(::renderDeviceState)
        dynamicContent?.start()
        if (!isPaired()) startPairing()
        container.logger.log(
            LogLevel.INFO,
            TAG,
            "Activity created; boot=${intent.getBooleanExtra(EXTRA_LAUNCHED_FROM_BOOT, false)}",
        )
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT > 23 && isPaired()) startPlayback()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT <= 23 && isPaired()) startPlayback()
        kioskController.enter()
    }

    override fun onPause() {
        if (Build.VERSION.SDK_INT <= 23) releasePlayback()
        super.onPause()
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT > 23) releasePlayback()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) kioskController.reapplySystemUi()
    }

    override fun onDestroy() {
        releasePlayback()
        pairingCoordinator?.close()
        pairingCoordinator = null
        dynamicContent?.close()
        dynamicContent = null
        stateSubscription?.close()
        stateSubscription = null
        container.logger.log(LogLevel.DEBUG, TAG, "Activity destroyed; finishing=$isFinishing")
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() = kioskController.reapplySystemUi()

    @SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_GUIDE -> {
            toggleDiagnostics(); true
        }
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_APP_SWITCH -> true
        else -> super.onKeyDown(keyCode, event)
    }

    private fun startPlayback() {
        if (playbackEngine != null) return
        fun weatherResource(resourceId: Int) = "android.resource://$packageName/$resourceId"
        val fallbackWeatherVideo = weatherResource(R.raw.weather_ceu_limpo)
        val weatherVideos = WeatherBackgroundCatalog(
            media = mapOf(
                com.loopin.player2.core.content.WeatherBackground.CLEAR_DAY to weatherResource(R.raw.weather_ceu_limpo),
                com.loopin.player2.core.content.WeatherBackground.CLEAR_NIGHT to weatherResource(R.raw.weather_noite_normal),
                com.loopin.player2.core.content.WeatherBackground.CLOUDY_DAY to weatherResource(R.raw.weather_ceu_nublado),
                com.loopin.player2.core.content.WeatherBackground.CLOUDY_NIGHT to weatherResource(R.raw.weather_noite_normal),
                com.loopin.player2.core.content.WeatherBackground.RAIN_DAY to weatherResource(R.raw.weather_dia_chuva),
                com.loopin.player2.core.content.WeatherBackground.RAIN_NIGHT to weatherResource(R.raw.weather_chuva_noite),
                com.loopin.player2.core.content.WeatherBackground.STORM to weatherResource(R.raw.weather_dia_chuva),
            ),
            fallback = fallbackWeatherVideo,
        )
        val itemPlayer = ScheduledItemPlayer(
            Media3ItemPlayer(this, playbackSurface),
            WeatherItemPlayer(this, playbackSurface, requireNotNull(dynamicContent), weatherProvider,
                WeatherBackgroundMediaResolver(weatherVideos::resolve),
                { container.stateManager.snapshot().networkAvailable }, container.logger),
        )
        val engine = LoopingPlaybackEngine(itemPlayer, container.logger)
        playbackEngine = engine
        playbackSubscription = engine.subscribe(::renderPlaybackState)
        container.playlistRepository.loadActivePlaylistAsync { playlist ->
            runOnUiThread {
                if (playbackEngine === engine) {
                    engine.load(playlist)
                    engine.start()
                }
            }
        }
    }

    private fun releasePlayback() {
        playbackSubscription?.close()
        playbackSubscription = null
        playbackEngine?.release()
        playbackEngine = null
        playbackSurface.clearAnimation()
    }

    private fun createPlaybackScreen(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val contentCanvas = ContentCanvasLayout(this, ContentPresentation())
        playbackSurface = PlaybackSurface(this)
        contentCanvas.addView(playbackSurface, FrameLayout.LayoutParams(-1, -1))
        dynamicContent = DynamicContentController(this, container.logger)
        contentCanvas.addView(dynamicContent!!.view, FrameLayout.LayoutParams(-1, -1))
        root.addView(contentCanvas, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        identityPanel = createIdentityPanel()
        root.addView(identityPanel, FrameLayout.LayoutParams(-1, -1))
        diagnosticView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(235, 11, 13, 16))
            textSize = 18f
            setPadding(32, 24, 32, 24)
            visibility = View.GONE
        }
        root.addView(diagnosticView, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun createIdentityPanel(): View {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((32 * density).toInt(), (24 * density).toInt(), (32 * density).toInt(), (24 * density).toInt())
            setBackgroundColor(Color.rgb(11, 13, 16))
            addView(TextView(context).apply {
                text = getString(R.string.app_name)
                textSize = 24f
                setTextColor(Color.rgb(245, 247, 250))
                gravity = Gravity.CENTER
            })
            addView(TextView(context).also {
                pairingCodeView = it
                it.text = if (isPaired()) container.config.identity.friendlyCode else "— — — — — —"
                it.textSize = 36f
                it.setTextColor(Color.rgb(245, 247, 250))
                it.gravity = Gravity.CENTER
                it.letterSpacing = 0.18f
                it.setPadding(0, (14 * density).toInt(), 0, 0)
            })
            addView(ImageView(context).also {
                pairingQrView = it
                it.setBackgroundColor(Color.WHITE)
                it.setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                it.visibility = if (isPaired()) View.GONE else View.INVISIBLE
            }, LinearLayout.LayoutParams((190 * density).toInt(), (190 * density).toInt()).apply {
                topMargin = (14 * density).toInt()
            })
            addView(TextView(context).also {
                statusView = it
                it.textSize = 14f
                it.setTextColor(Color.rgb(167, 176, 190))
                it.gravity = Gravity.CENTER
                it.setPadding(0, (12 * density).toInt(), 0, 0)
            })
            addView(TextView(context).also {
                pairingHintView = it
                it.text = if (!isPaired()) getString(R.string.awaiting_configuration) else ""
                it.textSize = 13f
                it.setTextColor(Color.rgb(167, 176, 190))
                it.gravity = Gravity.CENTER
                it.setPadding(0, (8 * density).toInt(), 0, 0)
            })
        }
    }

    private fun startPairing() {
        pairingCoordinator?.close()
        pairingCoordinator = PairingCoordinator(
            BuildConfig.PAIRING_ENDPOINT,
            container.config.identity,
            DeviceCredentialStore(this),
            container.pairingManager,
            container.logger,
            onDisplay = { state -> runOnUiThread { renderPairing(state) } },
            onPaired = { runOnUiThread {
                pairingQrView.visibility = View.GONE
                pairingHintView.text = getString(R.string.pairing_complete)
                identityPanel.visibility = View.GONE
                startPlayback()
            } },
        ).also(PairingCoordinator::start)
    }

    private fun renderPairing(state: PairingDisplayState) {
        if (isPaired()) return
        identityPanel.visibility = View.VISIBLE
        pairingCodeView.text = state.code?.chunked(3)?.joinToString(" ") ?: "— — — — — —"
        val payload = state.qrPayload
        if (payload != null) {
            pairingQrView.setImageBitmap(pairingQrBitmap(payload, 360))
            pairingQrView.visibility = View.VISIBLE
            pairingHintView.text = getString(R.string.pairing_expires, state.secondsRemaining)
        } else {
            pairingQrView.visibility = View.INVISIBLE
            pairingHintView.text = if (state.waitingForNetwork) getString(R.string.pairing_waiting_network)
            else state.error ?: getString(R.string.awaiting_configuration)
        }
    }

    private fun isPaired(): Boolean = container.pairingManager.snapshot().state == PairingState.PAIRED

    private fun renderDeviceState(state: DeviceRuntimeState) {
        runOnUiThread {
            statusView.text = when (state.phase) {
                RuntimePhase.STARTING -> getString(R.string.status_starting)
                RuntimePhase.READY_OFFLINE -> getString(R.string.status_ready_offline)
                RuntimePhase.READY_ONLINE -> getString(R.string.status_ready_online)
                RuntimePhase.DEGRADED -> getString(R.string.status_degraded)
                RuntimePhase.STOPPED -> getString(R.string.status_stopped)
            }
        }
    }

    private fun renderPlaybackState(snapshot: PlaybackSnapshot) {
        runOnUiThread {
            latestPlayback = snapshot
            container.operationalState.playback(snapshot.state, snapshot.lastError)
            if (snapshot.currentItemId != null && snapshot.currentItemId != lastLoggedItemId) {
                lastLoggedItemId = snapshot.currentItemId
                container.logger.log(LogLevel.INFO, TAG, "${ContentLogEvent.CONTENT_SELECTED} ${snapshot.currentItemId}")
            }
            if (snapshot.state != lastLoggedPlaybackState) {
                lastLoggedPlaybackState = snapshot.state
                when (snapshot.state) {
                    PlaybackState.PLAYING -> container.logger.log(LogLevel.INFO, TAG, ContentLogEvent.CONTENT_STARTED)
                    PlaybackState.COMPLETED -> container.logger.log(LogLevel.INFO, TAG, ContentLogEvent.CONTENT_FINISHED)
                    PlaybackState.ERROR -> container.logger.log(LogLevel.ERROR, TAG, ContentLogEvent.CONTENT_ERROR)
                    else -> Unit
                }
            }
            if (snapshot.state == PlaybackState.ERROR) {
                container.logger.log(LogLevel.ERROR, TAG, OperationalLogEvent.PLAYBACK_ERROR + ": " + snapshot.lastError)
                statusView.text = getString(R.string.content_unavailable, container.config.identity.friendlyCode)
            }
            identityPanel.visibility = if (!isPaired()) View.VISIBLE else when (snapshot.state) {
                PlaybackState.IDLE, PlaybackState.ERROR -> View.VISIBLE
                else -> View.GONE
            }
            if (diagnosticsVisible) renderDiagnostics()
        }
    }

    private fun toggleDiagnostics() {
        diagnosticsVisible = !diagnosticsVisible
        diagnosticView?.visibility = if (diagnosticsVisible) View.VISIBLE else View.GONE
        if (diagnosticsVisible) renderDiagnostics()
        kioskController.reapplySystemUi()
    }

    private fun renderDiagnostics() {
        val health = container.healthManager.collectNow()
        val pairing = container.pairingManager.snapshot()
        val update = container.updateManager.snapshot()
        val date = health.lastSyncEpochMs?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Nunca"
        diagnosticView?.text = getString(R.string.diagnostics_template,
            health.friendlyCode, health.appVersion, health.connection, health.syncState, date,
            health.cacheState, formatBytes(health.freeStorageBytes), formatBytes(health.totalStorageBytes),
            health.playbackState, pairing.state, update.currentVersion,
            update.availableVersion ?: "—", update.channel, health.lastError ?: "Nenhum")
    }

    private fun formatBytes(bytes: Long): String = if (bytes >= 1_073_741_824L)
        "%.1f GB".format(bytes / 1_073_741_824.0) else "%.1f MB".format(bytes / 1_048_576.0)

    companion object {
        const val EXTRA_LAUNCHED_FROM_BOOT = "launched_from_boot"
        private const val TAG = "MainActivity"
    }
}
