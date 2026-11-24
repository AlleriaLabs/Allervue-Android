package com.allerix.app

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal data class HomeWeather(
    val city: String,
    val temperature: Int,
    val apparentTemperature: Int,
    val condition: String,
    val kind: String,
    val isDay: Boolean
)

internal object HomeWeatherRepository {
    private val client = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun load(context: Context): HomeWeather? = withContext(Dispatchers.IO) {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .firstOrNull { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
                ?: return@withContext null

            val location = withTimeoutOrNull(15_000L) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val cancellation = CancellationSignal()
                    continuation.invokeOnCancellation { cancellation.cancel() }
                    try {
                        LocationManagerCompat.getCurrentLocation(
                            locationManager,
                            provider,
                            cancellation,
                            ContextCompat.getMainExecutor(context)
                        ) { result ->
                            if (continuation.isActive) continuation.resume(result)
                        }
                    } catch (_: SecurityException) {
                        if (continuation.isActive) continuation.resume(null)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            } ?: runCatching {
                LocationManagerCompat.getLastKnownLocation(locationManager, provider)
            }.getOrNull() ?: return@withContext null

            val city = runCatching {
                if (!Geocoder.isPresent()) null
                else Geocoder(context, Locale.getDefault())
                    .getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()
                    ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Your location"

            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${location.latitude}&longitude=${location.longitude}" +
                "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
                "&temperature_unit=celsius&timezone=auto"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val root = JSONObject(response.body?.string().orEmpty())
                val current = root.optJSONObject("current") ?: return@withContext null
                val code = current.optInt("weather_code", -1)
                val (condition, kind) = describe(code)
                HomeWeather(
                    city = city,
                    temperature = current.optDouble("temperature_2m").toInt(),
                    apparentTemperature = current.optDouble("apparent_temperature").toInt(),
                    condition = condition,
                    kind = kind,
                    isDay = current.optInt("is_day", 1) == 1
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "Clear sky" to "clear"
        1 -> "Mainly clear" to "partly"
        2 -> "Partly cloudy" to "partly"
        3 -> "Overcast" to "cloudy"
        45, 48 -> "Foggy" to "fog"
        51, 53, 55 -> "Drizzle" to "rain"
        56, 57 -> "Freezing drizzle" to "rain"
        61, 63, 65 -> "Rain" to "rain"
        66, 67 -> "Freezing rain" to "rain"
        71, 73, 75, 77 -> "Snow" to "snow"
        80, 81, 82 -> "Rain showers" to "rain"
        85, 86 -> "Snow showers" to "snow"
        95, 96, 99 -> "Thunderstorm" to "storm"
        else -> "Weather unavailable" to "unknown"
    }
}
