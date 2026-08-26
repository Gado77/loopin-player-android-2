package com.loopin.player2

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.loopin.player2.core.content.*
import com.loopin.player2.core.model.LogLevel
import com.loopin.player2.core.model.PlayerLogger
import com.loopin.player2.core.playback.DynamicContentSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Owns only visual composition. Playlist order and duration remain in LoopingPlaybackEngine. */
class DynamicContentController(private val context: Context, private val logger: PlayerLogger) : AutoCloseable, DynamicContentSurface {
    private val presentation = ContentPresentation()
    private val density = context.resources.displayMetrics.density
    val view = FrameLayout(context)
    private val handler = Handler(Looper.getMainLooper())
    private val weatherLayer = FrameLayout(context).apply {
        visibility = View.GONE
        setBackgroundColor(Color.parseColor("#55000000"))
    }
    private val weatherComposition = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }
    private val weatherCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = GlassPanelDrawable(density, radiusDp = 30f, fillAlpha = 0x20)
        elevation = 20f * density
    }
    private val weatherTitle = text(11f).apply {
        text = context.getString(R.string.weather_legacy_title)
        setTextColor(Color.argb(0xCC, 255, 255, 255))
        letterSpacing = 0.25f
        translationY = dp(7).toFloat()
    }
    private val city = text(30f, "sans-serif").apply {
        setTypeface(typeface, Typeface.BOLD)
        translationY = dp(6).toFloat()
    }
    private val temperature = text(80f, "sans-serif").apply {
        setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }
    private val temperatureUnit = text(32f, "sans-serif-medium").apply {
        text = "°"
        includeFontPadding = false
    }
    private val temperatureContainer = FrameLayout(context).apply {
        addView(temperature, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        addView(temperatureUnit, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }
    private val condition = text(22f).apply {
        includeFontPadding = false
        translationY = -dp(4).toFloat()
    }
    private val range = text(17f)
    private val feelsLike = text(14f).apply { setTextColor(Color.argb(205, 255, 255, 255)) }
    private val detailsWrapper = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, dp(3), 0, 0)
    }
    private val clock = text(18f).apply {
        gravity = Gravity.END
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }
    private val date = text(9f).apply {
        gravity = Gravity.END
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }
    private val clockPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        addView(clock)
        addView(date)
    }
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("d 'de' MMMM", Locale.getDefault())
    private var running = false
    private val minuteTick = object : Runnable {
        override fun run() { if (running) { updateClock(); handler.postDelayed(this, nextMinuteDelay()) } }
    }

    init {
        detailsWrapper.addView(range)
        detailsWrapper.addView(feelsLike)
        weatherCard.addView(weatherTitle); weatherCard.addView(city)
        weatherCard.addView(temperatureContainer, LinearLayout.LayoutParams(-1, -2))
        weatherCard.addView(condition); weatherCard.addView(detailsWrapper)
        weatherComposition.addView(weatherCard)
        weatherLayer.addView(weatherComposition, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        view.addView(weatherLayer, FrameLayout.LayoutParams(-1, -1))
        view.addView(clockPanel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        view.addOnLayoutChangeListener { _, left, topEdge, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - topEdge
            if (width > 0 && height > 0) {
                applyResponsiveLayout(width, height)
            }
        }
        logger.log(LogLevel.INFO, TAG, "${ContentLogEvent.LAYOUT_CHANGED} scheduled-dynamic-content")
    }

    fun start() {
        if (running) return
        running = true; updateClock(); handler.postDelayed(minuteTick, nextMinuteDelay())
        logger.log(LogLevel.INFO, TAG, "${ContentLogEvent.WIDGET_STARTED} CLOCK")
    }

    override fun showWeather(snapshot: WeatherSnapshot, background: WeatherBackground) {
        val data = snapshot.data
        city.text = (data?.city ?: "Clima local").uppercase(Locale.getDefault())
        temperature.text = data?.temperatureCelsius?.toInt()?.toString() ?: "—"
        temperatureUnit.visibility = if (data == null) View.GONE else View.VISIBLE
        positionTemperatureUnit()
        condition.text = data?.condition ?: "Dados temporariamente indisponíveis"
        range.text = data?.let {
            context.getString(R.string.weather_temperature_range, it.maximumCelsius.toInt(), it.minimumCelsius.toInt())
        }.orEmpty()
        feelsLike.text = data?.let { context.getString(R.string.weather_feels_like, it.feelsLikeCelsius.toInt()) }.orEmpty()
        weatherLayer.visibility = View.VISIBLE
        weatherLayer.alpha = 0f; weatherLayer.animate().alpha(1f).setDuration(250).start()
        logger.log(LogLevel.INFO, TAG, "${ContentLogEvent.TRANSITION_STARTED} FADE background=$background")
    }

    override fun hideWeather() {
        weatherLayer.animate().cancel(); weatherLayer.visibility = View.GONE
    }

    override fun close() { running = false; handler.removeCallbacks(minuteTick); view.animate().cancel(); hideWeather() }

    private fun updateClock() {
        val now = Date(); clock.text = clockFormat.format(now)
        date.text = dateFormat.format(now).uppercase(Locale.getDefault())
    }
    private fun nextMinuteDelay() = 60_000L - System.currentTimeMillis() % 60_000L
    private fun applyResponsiveLayout(width: Int, height: Int) {
        val contentWidth = presentation.weatherCardWidthPx(width)
        val horizontalPadding = (width * 0.075f).toInt()
        val mainVerticalPadding = (height * 0.025f).toInt()
        weatherCard.layoutParams = LinearLayout.LayoutParams(contentWidth, -2)
        weatherCard.setPadding(horizontalPadding, mainVerticalPadding, horizontalPadding, mainVerticalPadding)
        weatherComposition.translationY = height * 0.035f
        clockPanel.setPadding(0, 0, 0, 0)
        (clockPanel.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = (height * 0.022f).toInt()
            it.marginEnd = (width * 0.035f).toInt()
            clockPanel.layoutParams = it
        }
        city.textSize = (width * 0.047f).coerceIn(21f, 27f)
        temperature.textSize = (width * 0.158f).coerceIn(70f, 108f)
        temperatureUnit.textSize = temperature.textSize * 0.38f
        positionTemperatureUnit()
        condition.textSize = (width * 0.034f).coerceIn(17f, 24f)
    }

    private fun positionTemperatureUnit() {
        temperatureContainer.post {
            temperatureUnit.translationX = temperature.paint.measureText(temperature.text.toString()) / 2f + dp(5)
            temperatureUnit.translationY = -temperature.textSize * 0.24f
        }
    }

    private fun text(size: Float, family: String = "sans-serif") = TextView(context).apply {
        textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        typeface = Typeface.create(family, Typeface.NORMAL)
    }
    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    private companion object { const val TAG = "DynamicContent" }
}

fun createLocalWeatherRepository(context: Context): WeatherRepository {
    val cache = SharedPreferencesWeatherCache(context)
    val source = WeatherDataSource {
        if (BuildConfig.DEBUG) WeatherSourceResult.Success(WeatherData(
            "São José do Piauí", 28.0, 29.0, "Ensolarado", "☀", 31.0, 22.0,
            System.currentTimeMillis(), listOf(
                WeatherForecastDay("AMANHÃ", 23.0, 31.0, "☀"),
                WeatherForecastDay("QUI", 22.0, 30.0, "☁"),
                WeatherForecastDay("SEX", 21.0, 29.0, "☂"),
                WeatherForecastDay("SÁB", 22.0, 30.0, "☀"),
            ))) else WeatherSourceResult.Failure("No weather source configured")
    }
    return WeatherRepository(source, cache)
}

private class SharedPreferencesWeatherCache(context: Context) : WeatherCache {
    private val prefs = context.getSharedPreferences("loopin_weather_cache", Context.MODE_PRIVATE)
    override fun load(): WeatherData? {
        val updated = prefs.getLong("updated", 0L).takeIf { it > 0 } ?: return null
        return WeatherData(prefs.getString("city", null) ?: return null,
            Double.fromBits(prefs.getLong("temperature", 0)), Double.fromBits(prefs.getLong("feels", 0)),
            prefs.getString("condition", "").orEmpty(), prefs.getString("icon", "").orEmpty(),
            Double.fromBits(prefs.getLong("maximum", 0)), Double.fromBits(prefs.getLong("minimum", 0)), updated)
    }
    override fun save(data: WeatherData) {
        prefs.edit().putString("city", data.city).putLong("temperature", data.temperatureCelsius.toRawBits())
            .putLong("feels", data.feelsLikeCelsius.toRawBits()).putString("condition", data.condition)
            .putString("icon", data.icon).putLong("maximum", data.maximumCelsius.toRawBits())
            .putLong("minimum", data.minimumCelsius.toRawBits()).putLong("updated", data.updatedAtEpochMs).apply()
    }
}
