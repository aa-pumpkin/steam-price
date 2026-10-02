package dev.steamprice

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class Region(val cc: String, val currency: String, val symbol: String, val title: String) {
    DE("DE", "EUR", "€", "Германия"),
    KZ("KZ", "KZT", "₸", "Казахстан"),
    RU("RU", "RUB", "₽", "Россия"),
}

/** Цена в регионе в основных единицах валюты. [discountEnd] — unix-время конца скидки или 0. */
data class Price(val final: Double, val initial: Double, val discount: Int, val discountEnd: Long = 0)

sealed interface RegionPrice {
    data class Paid(val price: Price) : RegionPrice
    data object Free : RegionPrice
    data object Unavailable : RegionPrice
}

data class Game(val id: Int, val name: String, val image: String) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("image", image)

    companion object {
        fun fromJson(o: JSONObject) = Game(o.getInt("id"), o.getString("name"), o.optString("image"))
    }
}

/** Сколько рублей стоит единица валюты (курс ЦБ). */
data class Rates(val rubPer: Map<String, Double>, val date: String) {
    fun convert(amount: Double, from: Region, to: Region): Double? {
        val a = rubPer[from.currency] ?: return null
        val b = rubPer[to.currency] ?: return null
        return amount * a / b
    }
}

data class Profile(val steamId: String, val name: String, val avatar: String)

data class WishEntry(val appId: Int, val added: Long)

object Steam {
    private const val ASSETS = "https://shared.akamai.steamstatic.com/store_item_assets/"

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept-Language", "ru")
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun search(term: String): List<Game> {
        val items = JSONObject(get("https://store.steampowered.com/api/storesearch/?term=${enc(term)}&cc=de&l=russian"))
            .optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optString("type") == "app" }
            .map { Game(it.getInt("id"), it.getString("name"), it.optString("tiny_image")) }
    }

    private class Item(val game: Game?, val price: RegionPrice)

    /** Названия, картинки и цены пачками через IStoreBrowseService/GetItems (ключ не нужен). */
    private fun items(ids: List<Int>, region: Region): Map<Int, Item> = ids.chunked(50).flatMap { chunk ->
        val input = JSONObject()
            .put("ids", JSONArray(chunk.map { JSONObject().put("appid", it) }))
            .put("context", JSONObject().put("language", "russian").put("country_code", region.cc))
            .put("data_request", JSONObject().put("include_assets", true))
        val list = JSONObject(get("https://api.steampowered.com/IStoreBrowseService/GetItems/v1/?input_json=${enc(input.toString())}"))
            .getJSONObject("response").optJSONArray("store_items") ?: JSONArray()
        (0 until list.length()).map { list.getJSONObject(it) }.map { o -> o.getInt("id") to parseItem(o) }
    }.toMap()

    private fun parseItem(o: JSONObject): Item {
        if (o.optInt("success") != 1 || !o.optBoolean("visible", true)) return Item(null, RegionPrice.Unavailable)
        val image = o.optJSONObject("assets")?.let { a ->
            val file = a.optString("small_capsule").ifEmpty { a.optString("header") }
            if (file.isEmpty()) "" else ASSETS + a.optString("asset_url_format").replace("\${FILENAME}", file)
        }.orEmpty()
        val game = Game(o.getInt("id"), o.optString("name"), image)
        val opt = o.optJSONObject("best_purchase_option")
        val price = when {
            opt != null && opt.has("final_price_in_cents") -> {
                val final = opt.getString("final_price_in_cents").toLong() / 100.0
                val initial = opt.optString("original_price_in_cents").toLongOrNull()?.div(100.0) ?: final
                val end = opt.optJSONArray("active_discounts")?.optJSONObject(0)?.optLong("discount_end_date") ?: 0
                RegionPrice.Paid(Price(final, initial, opt.optInt("discount_pct"), end))
            }
            o.optBoolean("is_free") -> RegionPrice.Free
            else -> RegionPrice.Unavailable
        }
        return Item(game, price)
    }

    class Batch(val games: Map<Int, Game>, val prices: Map<Int, Map<Region, RegionPrice>>)

    fun fetch(ids: List<Int>): Batch {
        if (ids.isEmpty()) return Batch(emptyMap(), emptyMap())
        val byRegion = Region.entries.associateWith { items(ids, it) }
        // Название берём из любого региона, где игра продаётся.
        val games = ids.mapNotNull { id -> Region.entries.firstNotNullOfOrNull { byRegion[it]?.get(id)?.game }?.let { id to it } }.toMap()
        val prices = ids.associateWith { id -> Region.entries.associateWith { byRegion[it]?.get(id)?.price ?: RegionPrice.Unavailable } }
        return Batch(games, prices)
    }

    /** Принимает ссылку на профиль, steamid64 или короткое имя из ссылки. */
    fun resolveProfile(input: String): Profile {
        val s = input.trim().trimEnd('/')
        val byId = Regex("""/profiles/(\d{17})""").find(s)?.groupValues?.get(1)
        val byName = Regex("""/id/([^/?#]+)""").find(s)?.groupValues?.get(1)
        val path = when {
            byId != null -> "profiles/$byId"
            byName != null -> "id/${enc(byName)}"
            s.matches(Regex("""\d{17}""")) -> "profiles/$s"
            else -> "id/${enc(s)}"
        }
        val xml = get("https://steamcommunity.com/$path/?xml=1")
        val id = Regex("<steamID64>(\\d+)</steamID64>").find(xml)?.groupValues?.get(1) ?: error("Профиль не найден")
        val name = Regex("<steamID><!\\[CDATA\\[(.*?)]]></steamID>").find(xml)?.groupValues?.get(1) ?: id
        val avatar = Regex("<avatarMedium><!\\[CDATA\\[(.*?)]]></avatarMedium>").find(xml)?.groupValues?.get(1).orEmpty()
        return Profile(id, name, avatar)
    }

    /** Пустой список — вишлист пустой или скрыт настройками приватности. */
    fun wishlist(steamId: String): List<WishEntry> {
        val items = JSONObject(get("https://api.steampowered.com/IWishlistService/GetWishlist/v1/?steamid=$steamId"))
            .getJSONObject("response").optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { items.getJSONObject(it) }.map { WishEntry(it.getInt("appid"), it.optLong("date_added")) }
    }

    fun rates(): Rates {
        val json = JSONObject(get("https://www.cbr-xml-daily.ru/daily_json.js"))
        val valute = json.getJSONObject("Valute")
        val rubPer = mutableMapOf("RUB" to 1.0)
        for (code in listOf("EUR", "KZT")) {
            val v = valute.getJSONObject(code)
            rubPer[code] = v.getDouble("Value") / v.getDouble("Nominal")
        }
        return Rates(rubPer, json.getString("Date").take(10))
    }
}
