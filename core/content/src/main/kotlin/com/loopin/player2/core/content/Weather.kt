package com.loopin.player2.core.content

data class WeatherData(
    val city: String,
    val temperatureCelsius: Double,
    val feelsLikeCelsius: Double,
    val condition: String,
    val icon: String,
    val maximumCelsius: Double,
    val minimumCelsius: Double,
    val updatedAtEpochMs: Long,
    val forecast: List<WeatherForecastDay> = emptyList(),
) { init { require(city.isNotBlank()); require(updatedAtEpochMs > 0) } }

data class WeatherForecastDay(val label: String, val minimumCelsius: Double, val maximumCelsius: Double, val icon: String)
enum class WeatherBackground { CLEAR_DAY, CLEAR_NIGHT, CLOUDY_DAY, CLOUDY_NIGHT, RAIN_DAY, RAIN_NIGHT, STORM, FALLBACK }
class WeatherBackgroundSelector {
    fun select(condition: String, isNight: Boolean): WeatherBackground {
        val normalized = condition.lowercase()
        return when {
            "storm" in normalized || "tempest" in normalized -> WeatherBackground.STORM
            "rain" in normalized || "chuva" in normalized -> if (isNight) WeatherBackground.RAIN_NIGHT else WeatherBackground.RAIN_DAY
            "cloud" in normalized || "nuv" in normalized || "nubl" in normalized ->
                if (isNight) WeatherBackground.CLOUDY_NIGHT else WeatherBackground.CLOUDY_DAY
            "clear" in normalized || "sol" in normalized || "limpo" in normalized -> if (isNight) WeatherBackground.CLEAR_NIGHT else WeatherBackground.CLEAR_DAY
            else -> WeatherBackground.FALLBACK
        }
    }
}

enum class WeatherState { LOADING, AVAILABLE, STALE, UNAVAILABLE }
data class WeatherSnapshot(val state: WeatherState, val data: WeatherData? = null, val error: String? = null)
sealed interface WeatherSourceResult {
    data class Success(val data: WeatherData) : WeatherSourceResult
    data class Failure(val reason: String) : WeatherSourceResult
}
fun interface WeatherDataSource { fun fetch(): WeatherSourceResult }
interface WeatherCache { fun load(): WeatherData?; fun save(data: WeatherData) }
fun interface WeatherProvider { fun current(online: Boolean): WeatherSnapshot }

class WeatherRepository(
    private val source: WeatherDataSource,
    private val cache: WeatherCache,
    private val clock: () -> Long = System::currentTimeMillis,
    private val staleAfterMs: Long = 2L * 60 * 60 * 1_000,
) : WeatherProvider {
    override fun current(online: Boolean): WeatherSnapshot {
        if (online) when (val fetched = runCatching { source.fetch() }.getOrElse { WeatherSourceResult.Failure(it.message ?: "weather failure") }) {
            is WeatherSourceResult.Success -> { cache.save(fetched.data); return classify(fetched.data) }
            is WeatherSourceResult.Failure -> return cachedOrUnavailable(fetched.reason)
        }
        return cachedOrUnavailable("offline")
    }
    private fun cachedOrUnavailable(reason: String): WeatherSnapshot = cache.load()?.let(::classify)
        ?: WeatherSnapshot(WeatherState.UNAVAILABLE, error = reason)
    private fun classify(data: WeatherData) = WeatherSnapshot(
        if (clock() - data.updatedAtEpochMs > staleAfterMs) WeatherState.STALE else WeatherState.AVAILABLE, data)
}
