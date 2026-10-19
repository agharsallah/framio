package dev.framio.hub.core.feeds

import dev.framio.hub.core.model.Location
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/** What the Hub knows about the weather. All times are epoch ms. Cached as JSON, so serializable. */
@Serializable
data class Weather(
    val fetchedWallMs: Long,
    val current: Current?,
    val hourly: List<Hour>,
    /** 15-minute precipitation for the next hours: real nowcast data in Central Europe and North America. */
    val quarters: List<Quarter>,
    val days: List<Day>,
) {
    @Serializable
    data class Current(val atMs: Long, val temperature: Double, val apparent: Double?, val code: Int, val isDay: Boolean, val cloudCover: Int?, val windKmh: Double?)

    @Serializable
    data class Hour(val atMs: Long, val temperature: Double?, val precipitationMm: Double?, val probability: Int?, val code: Int?, val cloudCover: Int?)

    @Serializable
    data class Quarter(val atMs: Long, val precipitationMm: Double)

    @Serializable
    data class Day(val startMs: Long, val max: Double?, val min: Double?, val sunriseMs: Long?, val sunsetMs: Long?, val uvMax: Double?)

    fun hourAt(ms: Long): Hour? = hourly.lastOrNull { it.atMs <= ms }?.takeIf { ms - it.atMs < 3_600_000 }

    fun dayAt(ms: Long): Day? = days.lastOrNull { it.startMs <= ms }
}

/** The broad kinds of weather the Hub draws and talks about, from WMO weather codes. */
enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Drizzle, Rain, Showers, Snow, Thunder;

    val isWet get() = this == Drizzle || this == Rain || this == Showers || this == Snow || this == Thunder

    companion object {
        fun of(code: Int?): WeatherKind = when (code) {
            null -> Cloudy
            0 -> Clear
            1, 2 -> PartlyCloudy
            3 -> Cloudy
            45, 48 -> Fog
            in 51..57 -> Drizzle
            in 61..67 -> Rain
            in 71..77, 85, 86 -> Snow
            in 80..82 -> Showers
            in 95..99 -> Thunder
            else -> Cloudy
        }
    }
}

/**
 * Open-Meteo: free, no API key, CC BY 4.0. Asks for unix times so DST changes inside the forecast can't shift
 * hours (a single `utc_offset_seconds` would be wrong for half of a DST day).
 */
object OpenMeteo {
    private val json = Json { ignoreUnknownKeys = true }

    fun url(location: Location, base: String = "https://api.open-meteo.com"): String {
        val lat = round2(location.latitude) // about 1 km: Open-Meteo learns no more than the neighbourhood
        val lon = round2(location.longitude)
        return "$base/v1/forecast?latitude=$lat&longitude=$lon&timezone=auto&timeformat=unixtime&forecast_days=2" +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day,cloud_cover,wind_speed_10m" +
            "&minutely_15=precipitation" +
            "&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,cloud_cover" +
            "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max"
    }

    private fun round2(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    /** Parses a forecast response. Missing sections or values become empty lists and nulls, never errors. */
    fun parse(body: String, fetchedWallMs: Long): Weather {
        val r = json.decodeFromString(Response.serializer(), body)
        val current = r.current?.let { c ->
            c.temperature?.let { t -> Weather.Current(c.time * 1000, t, c.apparent, c.code ?: 3, c.isDay != 0, c.cloudCover, c.wind) }
        }
        val hourly = r.hourly?.let { h ->
            h.time.mapIndexed { i, t ->
                Weather.Hour(t * 1000, h.temperature.at(i), h.precipitation.at(i), h.probability.at(i), h.code.at(i), h.cloudCover.at(i))
            }
        } ?: emptyList()
        val quarters = r.quarters?.let { q -> q.time.mapIndexedNotNull { i, t -> q.precipitation.at(i)?.let { Weather.Quarter(t * 1000, it) } } } ?: emptyList()
        val days = r.daily?.let { d ->
            d.time.mapIndexed { i, t -> Weather.Day(t * 1000, d.max.at(i), d.min.at(i), d.sunrise.at(i)?.times(1000), d.sunset.at(i)?.times(1000), d.uv.at(i)) }
        } ?: emptyList()
        return Weather(fetchedWallMs, current, hourly, quarters, days)
    }

    private fun <T> List<T?>?.at(i: Int): T? = this?.getOrNull(i)

    @Serializable
    private class Response(
        val current: CurrentDto? = null,
        @SerialName("minutely_15") val quarters: QuartersDto? = null,
        val hourly: HourlyDto? = null,
        val daily: DailyDto? = null,
    )

    @Serializable
    private class CurrentDto(
        val time: Long,
        @SerialName("temperature_2m") val temperature: Double? = null,
        @SerialName("apparent_temperature") val apparent: Double? = null,
        @SerialName("weather_code") val code: Int? = null,
        @SerialName("is_day") val isDay: Int = 1,
        @SerialName("cloud_cover") val cloudCover: Int? = null,
        @SerialName("wind_speed_10m") val wind: Double? = null,
    )

    @Serializable
    private class QuartersDto(val time: List<Long> = emptyList(), val precipitation: List<Double?>? = null)

    @Serializable
    private class HourlyDto(
        val time: List<Long> = emptyList(),
        @SerialName("temperature_2m") val temperature: List<Double?>? = null,
        @SerialName("precipitation_probability") val probability: List<Int?>? = null,
        val precipitation: List<Double?>? = null,
        @SerialName("weather_code") val code: List<Int?>? = null,
        @SerialName("cloud_cover") val cloudCover: List<Int?>? = null,
    )

    @Serializable
    private class DailyDto(
        val time: List<Long> = emptyList(),
        @SerialName("temperature_2m_max") val max: List<Double?>? = null,
        @SerialName("temperature_2m_min") val min: List<Double?>? = null,
        val sunrise: List<Long?>? = null,
        val sunset: List<Long?>? = null,
        @SerialName("uv_index_max") val uv: List<Double?>? = null,
    )
}
