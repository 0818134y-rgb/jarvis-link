package com.zen.jarvis.brain

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/** 天気 (Open-Meteo: 無料・APIキー不要)。 */
class Weather(private val context: Context) {

    data class Place(val name: String, val lat: Double, val lon: Double)

    /** 地名 → 座標。端末のジオコーダ → Open-Meteo の順に試す。 */
    suspend fun geocode(name: String): Place? = withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.JAPAN).getFromLocationName(name, 1)?.firstOrNull()?.let {
                Place(name, it.latitude, it.longitude)
            }
        }.getOrNull() ?: runCatching {
            val j = JSONObject(Net.get(
                "https://geocoding-api.open-meteo.com/v1/search?count=1&language=ja&name=" + Net.enc(name)))
            val r = j.optJSONArray("results")?.optJSONObject(0) ?: return@runCatching null
            Place(r.optString("name", name), r.getDouble("latitude"), r.getDouble("longitude"))
        }.getOrNull()
    }

    /** 端末の現在地 (最後に取得した位置)。権限がなければ null。 */
    @SuppressLint("MissingPermission")
    suspend fun currentPlace(): Place? = withContext(Dispatchers.IO) {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!ok) return@withContext null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time } ?: return@withContext null
        val name = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.JAPAN).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull() ?: "現在地"
        Place(name, loc.latitude, loc.longitude)
    }

    enum class When { NOW, TODAY, TOMORROW, DAY_AFTER, WEEKEND }

    suspend fun report(place: Place, whenDay: When): String {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=Asia%2FTokyo&forecast_days=7"
        val j = JSONObject(Net.get(url))
        val daily = j.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        fun day(i: Int): String {
            val code = daily.getJSONArray("weather_code").getInt(i)
            val hi = daily.getJSONArray("temperature_2m_max").getDouble(i).roundToInt()
            val lo = daily.getJSONArray("temperature_2m_min").getDouble(i).roundToInt()
            val pop = daily.getJSONArray("precipitation_probability_max").optInt(i, -1)
            val popText = if (pop >= 0) "、降水確率は${pop}パーセント" else ""
            return "${describe(code)}、最高気温${hi}度、最低気温${lo}度$popText"
        }
        fun pop(i: Int) = daily.getJSONArray("precipitation_probability_max").optInt(i, 0)
        val umbrella = { i: Int -> if (pop(i) >= 50) "傘をお持ちになった方がよさそうです。" else if (pop(i) >= 30) "念のため折りたたみ傘があると安心です。" else "" }

        return when (whenDay) {
            When.NOW, When.TODAY -> {
                val cur = j.getJSONObject("current")
                val t = cur.getDouble("temperature_2m").roundToInt()
                "${place.name}は今${describe(cur.getInt("weather_code"))}で、気温は${t}度です。今日は${day(0)}。${umbrella(0)}"
            }
            When.TOMORROW -> "明日の${place.name}は${day(1)}の予報です。${umbrella(1)}"
            When.DAY_AFTER -> "明後日の${place.name}は${day(2)}の予報です。${umbrella(2)}"
            When.WEEKEND -> {
                val idx = (0 until dates.length()).filter {
                    val d = LocalDate.parse(dates.getString(it)).dayOfWeek
                    d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY
                }.take(2)
                if (idx.isEmpty()) "週末の予報はまだ出ていません。"
                else idx.joinToString("。") { i ->
                    val dw = if (LocalDate.parse(dates.getString(i)).dayOfWeek == DayOfWeek.SATURDAY) "土曜日" else "日曜日"
                    "${dw}の${place.name}は${day(i)}"
                } + "です。"
            }
        }
    }

    companion object {
        fun describe(code: Int): String = when (code) {
            0 -> "快晴"
            1 -> "晴れ"
            2 -> "晴れ時々曇り"
            3 -> "曇り"
            45, 48 -> "霧"
            51, 53, 55 -> "霧雨"
            56, 57 -> "冷たい霧雨"
            61 -> "小雨"
            63 -> "雨"
            65 -> "強い雨"
            66, 67 -> "冷たい雨"
            71 -> "小雪"
            73 -> "雪"
            75 -> "大雪"
            77 -> "雪あられ"
            80, 81 -> "にわか雨"
            82 -> "激しいにわか雨"
            85, 86 -> "にわか雪"
            95 -> "雷雨"
            96, 99 -> "ひょうを伴う雷雨"
            else -> "不明な天気"
        }

        fun whenOf(text: String): When = when {
            Regex("明後日|あさって").containsMatchIn(text) -> When.DAY_AFTER
            Regex("明日|あした|あす").containsMatchIn(text) -> When.TOMORROW
            Regex("週末|土曜|日曜|土日").containsMatchIn(text) -> When.WEEKEND
            Regex("今|外").containsMatchIn(text) -> When.NOW
            else -> When.TODAY
        }

        private val NOT_PLACE = setOf("今日", "明日", "明後日", "あした", "あさって", "週末", "今夜", "今", "外", "天気",
            "気温", "最高気温", "最低気温", "今日の", "明日の", "ここ", "こっち", "うち")

        /** 「大阪の明日の天気」→「大阪」。見つからなければ null。 */
        fun placeOf(text: String): String? {
            val t = text.replace(Regex("[、。？?！!\\s]|ジャーヴィス|ジャービス"), "")
                .replace(Regex("(今日|明日|明後日|あした|あさって|週末|今夜|今週|土曜|日曜)(日)?(の|は)?"), "")
            val m = Regex("^(.+?)(?:の|は|って|で)(?:天気|気温|雨|晴|雪|最高|最低|暑|寒|何度|傘)").find(t)
                ?: Regex("^(.+?)(?:天気|気温)").find(t)
            val p = m?.groupValues?.get(1)?.trim()?.removeSuffix("の") ?: return null
            return if (p.isBlank() || p in NOT_PLACE || p.length > 12) null else p
        }
    }
}
