package dev.steamprice

import android.content.Context
import androidx.core.content.edit
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Аккаунт Steam, чей вишлист подтягиваем. [region] — к какому региону привязан аккаунт. */
data class Account(val steamId: String, val name: String, val region: Region, val avatar: String = "")

/** Игра из вишлиста; [owners] — steamId аккаунтов, у которых она в вишлисте. */
data class Wish(val game: Game, val owners: List<String>, val added: Long)

enum class Source { RECENT, WISHLIST }

enum class WishSort(val title: String) {
    DISCOUNT("По скидке"),
    CHEAP("Сначала дешёвые"),
    EXPENSIVE("Сначала дорогие"),
    ADDED("Недавно добавленные"),
    NAME("По названию"),
}

/** Как показывать вишлист: сортировка и фильтры. [account] — steamId или null для всех. */
data class WishView(val sort: WishSort = WishSort.DISCOUNT, val onlyDiscount: Boolean = false, val account: String? = null)

data class Snapshot(
    val recent: List<Game> = emptyList(),
    val wishlist: List<Wish> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val base: Region = Region.DE,
    val widgetSource: Source = Source.RECENT,
    val wishView: WishView = WishView(),
    val prices: Map<Int, Map<Region, RegionPrice>> = emptyMap(),
    val rates: Rates? = null,
    val updatedAt: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
) {
    /** Вишлист с применёнными сортировкой и фильтрами — его видят и приложение, и виджет. */
    val visibleWishlist: List<Wish>
        get() {
            val v = wishView
            val filtered = wishlist.filter { w ->
                (!v.onlyDiscount || maxDiscount(prices[w.game.id]) > 0) && (v.account == null || v.account in w.owners)
            }
            // Лучшая цена в основной валюте; без цены — в конец.
            fun best(w: Wish) = convertedPrices(prices[w.game.id], rates, base).values.minOrNull()
            val byAdded = compareByDescending<Wish> { it.added }
            return when (v.sort) {
                WishSort.DISCOUNT -> filtered.sortedWith(compareByDescending<Wish> { maxDiscount(prices[it.game.id]) }.then(byAdded))
                WishSort.CHEAP -> filtered.sortedWith(compareBy<Wish, Double?>(nullsLast()) { best(it) })
                WishSort.EXPENSIVE -> filtered.sortedWith(compareBy<Wish, Double?>(nullsLast(reverseOrder())) { best(it) })
                WishSort.ADDED -> filtered.sortedWith(byAdded)
                WishSort.NAME -> filtered.sortedBy { it.game.name.lowercase() }
            }
        }

    fun accountRegions(w: Wish) = w.owners.mapNotNull { id -> accounts.find { it.steamId == id }?.region }.distinct()
}

/** Одно общее состояние для приложения и виджета, сохраняется в SharedPreferences. */
object Repo {
    private const val MAX_RECENT = 15
    private val _state = MutableStateFlow<Snapshot?>(null)

    fun state(context: Context): StateFlow<Snapshot?> {
        if (_state.value == null) _state.compareAndSet(null, load(context))
        return _state
    }

    fun current(context: Context): Snapshot = state(context).value!!

    private suspend fun change(context: Context, f: (Snapshot) -> Snapshot) {
        state(context)
        _state.update { f(it!!) }
        save(context)
        updateWidgets(context)
    }

    suspend fun refresh(context: Context) {
        state(context)
        _state.update { it!!.copy(loading = true, error = null) }
        updateWidgets(context)
        try {
            val s = current(context)
            val (rates, wishlist, batch) = withContext(Dispatchers.IO) {
                val rates = Steam.rates()
                val wishlist = loadWishlists(s.accounts)
                // Аватарки у аккаунтов, добавленных до их появления.
                val avatars = s.accounts.filter { it.avatar.isEmpty() }
                    .mapNotNull { a -> runCatching { a.steamId to Steam.resolveProfile(a.steamId).avatar }.getOrNull() }.toMap()
                if (avatars.isNotEmpty()) _state.update { st ->
                    st!!.copy(accounts = st.accounts.map { a -> avatars[a.steamId]?.let { a.copy(avatar = it) } ?: a })
                }
                val ids = (s.recent.map { it.id } + wishlist.map { it.first }).distinct()
                Triple(rates, wishlist, Steam.fetch(ids))
            }
            _state.update { prev ->
                val st = prev!!
                val wishes = wishlist.mapNotNull { (id, owners, added) ->
                    val game = batch.games[id] ?: st.wishlist.find { it.game.id == id }?.game ?: return@mapNotNull null
                    Wish(game, owners, added)
                }
                st.copy(
                    rates = rates, wishlist = wishes, prices = st.prices + batch.prices,
                    updatedAt = System.currentTimeMillis(), loading = false,
                )
            }
        } catch (e: Exception) {
            _state.update { it!!.copy(loading = false, error = e.message ?: e.toString()) }
        }
        save(context)
        updateWidgets(context)
    }

    private fun loadWishlists(accounts: List<Account>): List<Triple<Int, List<String>, Long>> {
        val owners = linkedMapOf<Int, Pair<List<String>, Long>>()
        for (acc in accounts) {
            for (w in Steam.wishlist(acc.steamId)) {
                val prev = owners[w.appId]
                owners[w.appId] = ((prev?.first ?: emptyList()) + acc.steamId) to maxOf(prev?.second ?: 0, w.added)
            }
        }
        return owners.map { (id, e) -> Triple(id, e.first, e.second) }
    }

    /** Подгрузить цены для результатов поиска, не трогая недавние. */
    suspend fun fetchPrices(context: Context, ids: List<Int>) {
        state(context)
        val rates = if (current(context).rates == null) withContext(Dispatchers.IO) { Steam.rates() } else null
        val batch = withContext(Dispatchers.IO) { Steam.fetch(ids) }
        _state.update { it!!.copy(prices = it.prices + batch.prices, rates = rates ?: it.rates) }
    }

    /** Игру открыли — поднимаем её наверх недавних. */
    suspend fun open(context: Context, game: Game) {
        change(context) { s -> s.copy(recent = (listOf(game) + s.recent.filter { it.id != game.id }).take(MAX_RECENT)) }
        if (current(context).prices[game.id] == null) {
            runCatching { fetchPrices(context, listOf(game.id)) }
            save(context)
            updateWidgets(context)
        }
    }

    suspend fun remove(context: Context, game: Game) = change(context) { s -> s.copy(recent = s.recent.filter { it.id != game.id }) }

    suspend fun setBase(context: Context, region: Region) = change(context) { it.copy(base = region) }

    suspend fun setWidgetSource(context: Context, source: Source) = change(context) { it.copy(widgetSource = source) }

    suspend fun setWishView(context: Context, view: WishView) = change(context) { it.copy(wishView = view) }

    suspend fun setAccountRegion(context: Context, account: Account, region: Region) = change(context) { s ->
        s.copy(accounts = s.accounts.map { if (it.steamId == account.steamId) it.copy(region = region) else it })
    }

    /** Возвращает текст предупреждения/ошибки или null, если всё хорошо. */
    suspend fun addAccount(context: Context, input: String, region: Region): String? {
        val profile = try {
            withContext(Dispatchers.IO) { Steam.resolveProfile(input) }
        } catch (e: Exception) {
            return "Не нашёл такой профиль"
        }
        val empty = withContext(Dispatchers.IO) { runCatching { Steam.wishlist(profile.steamId).isEmpty() }.getOrDefault(true) }
        change(context) { s ->
            val accounts = s.accounts.filter { it.steamId != profile.steamId } + Account(profile.steamId, profile.name, region, profile.avatar)
            s.copy(accounts = accounts, widgetSource = if (s.accounts.isEmpty()) Source.WISHLIST else s.widgetSource)
        }
        refresh(context)
        return if (empty) "«${profile.name}» добавлен, но вишлист пустой или скрыт — в Steam поставь «Доступ к игровой информации: Открытый»" else null
    }

    suspend fun removeAccount(context: Context, account: Account) = change(context) { s ->
        val left = s.accounts.filter { it.steamId != account.steamId }
        val wishes = s.wishlist.mapNotNull { w -> (w.owners - account.steamId).takeIf { it.isNotEmpty() }?.let { w.copy(owners = it) } }
        val view = if (s.wishView.account == account.steamId) s.wishView.copy(account = null) else s.wishView
        s.copy(accounts = left, wishlist = wishes, wishView = view, widgetSource = if (left.isEmpty()) Source.RECENT else s.widgetSource)
    }

    private suspend fun updateWidgets(context: Context) = PriceWidget().updateAll(context)

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(3, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    // --- хранение ---

    private fun prefs(context: Context) = context.getSharedPreferences("data", Context.MODE_PRIVATE)

    private fun save(context: Context) {
        val s = _state.value ?: return
        val kept = (s.recent.map { it.id } + s.wishlist.map { it.game.id }).toSet()
        val prices = JSONObject()
        s.prices.filterKeys { it in kept }.forEach { (id, byRegion) ->
            prices.put(id.toString(), JSONObject().apply { byRegion.forEach { (r, p) -> put(r.name, p.toJson()) } })
        }
        val json = JSONObject()
            .put("recent", JSONArray(s.recent.map { it.toJson() }))
            .put("wishlist", JSONArray(s.wishlist.map { w ->
                w.game.toJson().put("owners", JSONArray(w.owners)).put("added", w.added)
            }))
            .put("accounts", JSONArray(s.accounts.map {
                JSONObject().put("id", it.steamId).put("name", it.name).put("region", it.region.name).put("avatar", it.avatar)
            }))
            .put("base", s.base.name)
            .put("widgetSource", s.widgetSource.name)
            .put("wishSort", s.wishView.sort.name)
            .put("onlyDiscount", s.wishView.onlyDiscount)
            .put("wishAccount", s.wishView.account ?: "")
            .put("prices", prices)
            .put("updatedAt", s.updatedAt)
        s.rates?.let { json.put("rates", JSONObject(it.rubPer)).put("ratesDate", it.date) }
        prefs(context).edit { putString("snapshot", json.toString()) }
    }

    private fun load(context: Context): Snapshot {
        val raw = prefs(context).getString("snapshot", null) ?: return Snapshot()
        return try {
            val json = JSONObject(raw)
            fun objects(key: String) = json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            val pricesJson = json.getJSONObject("prices")
            val prices = pricesJson.keys().asSequence().associate { id ->
                val o = pricesJson.getJSONObject(id)
                id.toInt() to Region.entries.filter { o.has(it.name) }.associateWith { regionPriceFromJson(o.getJSONObject(it.name)) }
            }
            val rates = json.optJSONObject("rates")?.let { r ->
                Rates(r.keys().asSequence().associateWith { r.getDouble(it) }, json.optString("ratesDate"))
            }
            Snapshot(
                recent = objects("recent").map(Game::fromJson),
                wishlist = objects("wishlist").map { o ->
                    val owners = o.getJSONArray("owners").let { a -> (0 until a.length()).map { a.getString(it) } }
                    Wish(Game.fromJson(o), owners, o.optLong("added"))
                },
                accounts = objects("accounts").map { Account(it.getString("id"), it.getString("name"), Region.valueOf(it.getString("region")), it.optString("avatar")) },
                base = runCatching { Region.valueOf(json.getString("base")) }.getOrDefault(Region.DE),
                widgetSource = runCatching { Source.valueOf(json.getString("widgetSource")) }.getOrDefault(Source.RECENT),
                wishView = WishView(
                    sort = runCatching { WishSort.valueOf(json.getString("wishSort")) }.getOrDefault(WishSort.DISCOUNT),
                    onlyDiscount = json.optBoolean("onlyDiscount"),
                    account = json.optString("wishAccount").ifEmpty { null },
                ),
                prices = prices, rates = rates, updatedAt = json.optLong("updatedAt"),
            )
        } catch (e: Exception) {
            Snapshot()
        }
    }

    private fun RegionPrice.toJson(): JSONObject = when (this) {
        is RegionPrice.Paid -> JSONObject().put("t", "paid").put("f", price.final).put("i", price.initial)
            .put("d", price.discount).put("e", price.discountEnd)
        RegionPrice.Free -> JSONObject().put("t", "free")
        RegionPrice.Unavailable -> JSONObject().put("t", "na")
    }

    private fun regionPriceFromJson(o: JSONObject): RegionPrice = when (o.getString("t")) {
        "paid" -> RegionPrice.Paid(Price(o.getDouble("f"), o.getDouble("i"), o.getInt("d"), o.optLong("e")))
        "free" -> RegionPrice.Free
        else -> RegionPrice.Unavailable
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Repo.refresh(applicationContext)
        return if (Repo.current(applicationContext).error == null) Result.success() else Result.retry()
    }
}

// --- форматирование и сравнение ---

private val intFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru"))
private val eurFormat = NumberFormat.getNumberInstance(Locale.GERMANY).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }

fun formatMoney(amount: Double, region: Region): String = when (region) {
    Region.DE -> "${eurFormat.format(amount)} €"
    else -> "${intFormat.format(Math.round(amount))} ${region.symbol}"
}

fun maxDiscount(prices: Map<Region, RegionPrice>?) =
    prices?.values?.maxOfOrNull { (it as? RegionPrice.Paid)?.price?.discount ?: 0 } ?: 0

/** Цена каждого региона в основной валюте [base] — для сравнения. */
fun convertedPrices(prices: Map<Region, RegionPrice>?, rates: Rates?, base: Region): Map<Region, Double> =
    prices.orEmpty().mapNotNull { (r, p) ->
        val paid = p as? RegionPrice.Paid ?: return@mapNotNull null
        rates?.convert(paid.price.final, r, base)?.let { r to it }
    }.toMap()

fun cheapest(converted: Map<Region, Double>): Region? = if (converted.size < 2) null else converted.minByOrNull { it.value }?.key

fun priceText(price: RegionPrice?, region: Region) = when (price) {
    is RegionPrice.Paid -> formatMoney(price.price.final, region)
    RegionPrice.Free -> "бесплатно"
    RegionPrice.Unavailable -> "—"
    null -> "…"
}
