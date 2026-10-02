package dev.steamprice

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_SEARCH = "search"
        private const val EXTRA_GAME = "game"

        fun searchIntent(context: Context) = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SEARCH, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // data делает интенты разных игр различимыми для PendingIntent
        fun gameIntent(context: Context, id: Int) = Intent(context, MainActivity::class.java)
            .setData("steamprice://app/$id".toUri())
            .putExtra(EXTRA_GAME, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    /** Что открыть по интенту из виджета; счётчик, чтобы повторное нажатие тоже срабатывало. */
    private val request = mutableStateOf(Request(0, false, null))

    data class Request(val n: Int, val search: Boolean, val gameId: Int?)

    private fun handle(intent: Intent) {
        val game = intent.getIntExtra(EXTRA_GAME, 0).takeIf { it != 0 }
        request.value = Request(request.value.n + 1, intent.getBooleanExtra(EXTRA_SEARCH, false), game)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Repo.schedule(this)
        handle(intent)
        setContent {
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                var settings by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()) {
                    if (settings) {
                        BackHandler { settings = false }
                        Settings(onClose = { settings = false })
                    } else {
                        Screen(request.value, onSettings = { settings = true })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }
}

@Composable
private fun Screen(request: MainActivity.Request, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Repo.state(context).collectAsState()
    val snap = s ?: Snapshot()
    val colors = MaterialTheme.colorScheme

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Game>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf(false) }
    var expanded by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(snap.widgetSource) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(request) {
        if (request.search) {
            query = ""
            expanded = 0
            focus.requestFocus()
            keyboard?.show()
        } else if (request.gameId != null) {
            query = ""
            expanded = request.gameId
            // Открываем вкладку, на которой игра есть; если она в обеих — ту, что показывает виджет.
            val inWishlist = snap.wishlist.any { it.game.id == request.gameId }
            val inRecent = snap.recent.any { it.id == request.gameId }
            tab = if (inWishlist && (!inRecent || snap.widgetSource == Source.WISHLIST)) Source.WISHLIST else Source.RECENT
            focusManager.clearFocus()
        }
    }

    // При открытии обновляем цены, если они старше получаса.
    LaunchedEffect(Unit) {
        if (System.currentTimeMillis() - snap.updatedAt > 30 * 60_000) Repo.refresh(context)
    }

    LaunchedEffect(query) {
        searchError = false
        if (query.isBlank()) {
            results = emptyList(); searching = false; return@LaunchedEffect
        }
        delay(350)
        searching = true
        try {
            val found = withContext(Dispatchers.IO) { Steam.search(query.trim()) }
            results = found
            expanded = 0
            searching = false
            Repo.fetchPrices(context, found.map { it.id })
        } catch (e: Exception) {
            searchError = true
            searching = false
        }
    }

    val inSearch = query.isNotBlank()
    val hasWishlist = snap.accounts.isNotEmpty()
    val showWishlist = !inSearch && hasWishlist && tab == Source.WISHLIST
    val list: List<Pair<Game, List<Region>>> = when {
        inSearch -> results.map { it to emptyList() }
        showWishlist -> snap.visibleWishlist.map { it.game to snap.accountRegions(it) }
        else -> snap.recent.map { it to emptyList() }
    }

    Column(Modifier.fillMaxSize()) {
        // Поиск
        Row(
            Modifier.padding(16.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
                .background(colors.surfaceContainerHigh).padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Поиск игры", color = colors.onSurfaceVariant, fontSize = 16.sp)
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, fontSize = 16.sp),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                RoundButton(onClick = { query = "" }) { Text("✕", color = colors.onSurfaceVariant, fontSize = 16.sp) }
            } else {
                RoundButton(onClick = onSettings) {
                    Icon(painterResource(R.drawable.ic_settings), "Настройки", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                }
            }
        }

        if (!inSearch && hasWishlist) {
            Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Недавние", tab == Source.RECENT) { tab = Source.RECENT; expanded = 0 }
                Chip("Вишлист  ${snap.wishlist.size}", tab == Source.WISHLIST) { tab = Source.WISHLIST; expanded = 0 }
            }
        }
        if (showWishlist && snap.wishlist.isNotEmpty()) WishToolbar(snap)

        val hint = when {
            inSearch && searching && results.isEmpty() -> "Ищу…"
            inSearch && searchError -> "Нет соединения"
            inSearch && !searching && results.isEmpty() -> "Ничего не нашлось"
            showWishlist && list.isEmpty() && snap.loading -> "Загружаю вишлист…"
            showWishlist && list.isEmpty() && snap.wishlist.isNotEmpty() -> "Под фильтр ничего не подходит"
            showWishlist && list.isEmpty() -> "Вишлист пуст или скрыт настройками приватности"
            !inSearch && list.isEmpty() -> "Начни вводить название —\nоткрытые игры появятся здесь и в виджете"
            else -> null
        }
        if (hint != null) {
            Text(hint, color = colors.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp))
        }

        val listState = rememberLazyListState()
        LaunchedEffect(snap.wishView, tab, inSearch) { listState.scrollToItem(0) }
        LazyColumn(Modifier.weight(1f).imePadding(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            items(list, key = { it.first.id }) { (game, tags) ->
                GameItem(
                    game = game,
                    tags = tags,
                    snap = snap,
                    expanded = expanded == game.id,
                    onClick = {
                        expanded = if (expanded == game.id) 0 else game.id
                        if (inSearch) {
                            keyboard?.hide()
                            scope.launch { Repo.open(context, game) }
                        }
                    },
                    onRemove = if (!inSearch && !showWishlist) ({ scope.launch { Repo.remove(context, game) } }) else null,
                )
            }
            if (!inSearch && list.isNotEmpty()) {
                item(key = "footer") { Footer(snap) { scope.launch { Repo.refresh(context) } } }
            }
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        content()
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        text, fontSize = 14.sp,
        color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(18.dp))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun GameItem(
    game: Game,
    tags: List<Region>,
    snap: Snapshot,
    expanded: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val prices = snap.prices[game.id]
    val converted = convertedPrices(prices, snap.rates, snap.base)
    val best = cheapest(converted)
    val discount = maxDiscount(prices)

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(20.dp))
            .background(if (expanded) colors.surfaceContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(12.dp).animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(game.image, Modifier.width(64.dp).height(24.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(game.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!expanded) {
                    Text(
                        buildAnnotatedString {
                            Region.entries.forEachIndexed { i, r ->
                                if (i > 0) append("   ")
                                if (r == best) withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.SemiBold)) { append(priceText(prices?.get(r), r)) }
                                else append(priceText(prices?.get(r), r))
                            }
                        },
                        fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (discount > 0) Text("−$discount%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.primary)
                if (tags.isNotEmpty()) Text(tags.joinToString(" ") { it.name }, fontSize = 10.sp, color = colors.outline)
            }
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            if (prices == null) {
                Text("загружаю…", color = colors.onSurfaceVariant, fontSize = 13.sp)
            } else {
                Region.entries.forEach { region ->
                    PriceLine(region, prices[region], converted[region], snap.base, region == best, best?.let { converted[it] })
                }
                val ends = prices.values.mapNotNull { (it as? RegionPrice.Paid)?.price?.discountEnd?.takeIf { e -> e > 0 } }.maxOrNull()
                if (ends != null) {
                    Text("скидка до ${SimpleDateFormat("d MMMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(ends * 1000))}",
                        fontSize = 12.sp, color = colors.primary, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { open(context, "https://store.steampowered.com/app/${game.id}/") }) { Text("Steam") }
                TextButton(onClick = { open(context, "https://steamdb.info/app/${game.id}/") }) { Text("SteamDB") }
                Spacer(Modifier.weight(1f))
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Убрать", color = colors.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun PriceLine(region: Region, price: RegionPrice?, converted: Double?, base: Region, isBest: Boolean, bestValue: Double?) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(region.title, color = colors.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.width(96.dp))
        Column(Modifier.weight(1f)) {
            Text(priceText(price, region).replace("—", "не продаётся"), fontSize = 16.sp,
                fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                color = if (isBest) colors.primary else colors.onSurface)
            val p = (price as? RegionPrice.Paid)?.price
            if (p != null && p.discount > 0) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(formatMoney(p.initial, region)) }
                        append("  −${p.discount}%")
                    },
                    fontSize = 12.sp, color = colors.onSurfaceVariant,
                )
            }
        }
        Text(
            if (converted != null && region != base) "≈ ${formatMoney(converted, base)}" else "",
            fontSize = 14.sp, color = colors.onSurfaceVariant,
        )
        Text(
            when {
                converted == null -> ""
                isBest -> "мин."
                bestValue != null && bestValue > 0 -> "+${((converted / bestValue - 1) * 100).roundToInt()}%"
                else -> ""
            },
            fontSize = 12.sp, color = if (isBest) colors.primary else colors.onSurfaceVariant,
            textAlign = TextAlign.End, modifier = Modifier.width(52.dp),
        )
    }
}

@Composable
private fun Footer(s: Snapshot, onRefresh: () -> Unit) {
    val ru = Locale.forLanguageTag("ru")
    val text = buildList {
        s.rates?.let { addAll(rateLines(it, s.base)) }
        when {
            s.loading -> add("обновляю…")
            s.error != null -> add("нет сети, повторить")
            s.updatedAt > 0 -> add("обновлено ${SimpleDateFormat("HH:mm", ru).format(Date(s.updatedAt))}")
        }
    }.joinToString("  ·  ")
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onRefresh).padding(horizontal = 20.dp, vertical = 16.dp))
}

@Composable
private fun Settings(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Repo.state(context).collectAsState()
    val snap = s ?: Snapshot()
    val colors = MaterialTheme.colorScheme
    var addOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(onClick = onClose) { Text("←", fontSize = 22.sp, color = colors.onSurface) }
            Spacer(Modifier.width(8.dp))
            Text("Настройки", fontSize = 22.sp, color = colors.onSurface)
        }

        Section("Основная валюта")
        Group {
            Segmented(Region.entries.map { "${it.symbol} ${it.currency}" }, Region.entries.indexOf(snap.base)) {
                scope.launch { Repo.setBase(context, Region.entries[it]) }
            }
            Hint("В ней считаются «≈» цены и выгода между регионами")
        }

        Section("Аккаунты Steam")
        Group(padding = 0.dp) {
            snap.accounts.forEach { acc ->
                val count = snap.wishlist.count { acc.steamId in it.owners }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RemoteImage(acc.avatar, Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(acc.name, fontSize = 16.sp, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (count > 0) "в вишлисте: $count" else "вишлист скрыт или пуст",
                            fontSize = 13.sp, color = if (count > 0) colors.onSurfaceVariant else colors.error,
                        )
                    }
                    // Регион аккаунта: нажатие переключает DE → KZ → RU.
                    Text(
                        acc.region.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.onSecondaryContainer,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.secondaryContainer)
                            .clickable {
                                val next = Region.entries[(acc.region.ordinal + 1) % Region.entries.size]
                                scope.launch { Repo.setAccountRegion(context, acc, next) }
                            }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    RoundButton(onClick = { scope.launch { Repo.removeAccount(context, acc) } }) {
                        Text("✕", color = colors.onSurfaceVariant, fontSize = 15.sp)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable { addOpen = true }.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
                    Text("+", fontSize = 22.sp, color = colors.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Text("Добавить аккаунт", fontSize = 16.sp, color = colors.primary)
            }
        }
        if (snap.accounts.any { a -> snap.wishlist.none { a.steamId in it.owners } }) {
            Hint("Если вишлист не виден: Steam → Профиль → Редактировать → Приватность → «Доступ к игровой информации: Открытый»")
        }

        Section("Виджет")
        Group {
            Text("Показывать", fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            Segmented(listOf("Недавние", "Вишлист"), snap.widgetSource.ordinal, enabled = listOf(true, snap.accounts.isNotEmpty())) {
                scope.launch { Repo.setWidgetSource(context, Source.entries[it]) }
            }
            if (snap.widgetSource == Source.WISHLIST) Hint("С теми же сортировкой и фильтрами, что выбраны в приложении")
        }

        Section("Данные")
        Group(padding = 0.dp) {
            Row(
                Modifier.fillMaxWidth().clickable { scope.launch { Repo.refresh(context) } }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Обновить сейчас", fontSize = 16.sp, color = colors.onSurface)
                    Text(
                        when {
                            snap.loading -> "обновляю…"
                            snap.error != null -> "ошибка: нет сети"
                            snap.updatedAt > 0 -> "последнее обновление " + SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(snap.updatedAt))
                            else -> "ещё не обновлялось"
                        } + " · автоматически раз в 3 часа",
                        fontSize = 13.sp, color = colors.onSurfaceVariant,
                    )
                }
                if (snap.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            snap.rates?.let { r ->
                Text(
                    "Курс ЦБ на ${r.date}: " + rateLines(r, snap.base).joinToString(", "),
                    fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (addOpen) AddAccountDialog(snap) { addOpen = false }
}

@Composable
private fun AddAccountDialog(snap: Snapshot, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var input by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(Region.entries.firstOrNull { r -> snap.accounts.none { it.region == r } } ?: Region.DE) }
    var adding by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text("Аккаунт Steam") },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerHighest).padding(14.dp)) {
                    if (input.isEmpty()) Text("ссылка на профиль или ник", color = colors.onSurfaceVariant, fontSize = 15.sp)
                    BasicTextField(
                        value = input, onValueChange = { input = it; message = null }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, fontSize = 15.sp),
                        cursorBrush = SolidColor(colors.primary), modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Text("Например steamcommunity.com/id/name", fontSize = 12.sp, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                Text("Регион аккаунта", fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                Segmented(Region.entries.map { it.title }, region.ordinal) { region = Region.entries[it] }
                message?.let { Text(it, fontSize = 13.sp, color = colors.error, modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            if (adding) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else TextButton(enabled = input.isNotBlank(), onClick = {
                adding = true
                scope.launch {
                    val error = Repo.addAccount(context, input, region)
                    adding = false
                    if (error == null) onDismiss() else message = error
                }
            }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !adding) { Text("Отмена") } },
    )
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
}

@Composable
private fun Group(padding: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(padding)) {
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

/** Сегментированный переключатель на всю ширину. */
@Composable
private fun Segmented(options: List<String>, selected: Int, enabled: List<Boolean>? = null, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerHighest).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = enabled?.getOrNull(i) ?: true
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (i == selected) colors.secondaryContainer else Color.Transparent)
                    .clickable(enabled = on) { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 14.sp, maxLines = 1,
                    fontWeight = if (i == selected) FontWeight.Medium else FontWeight.Normal,
                    color = when {
                        !on -> colors.outline
                        i == selected -> colors.onSecondaryContainer
                        else -> colors.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Сортировка и фильтры вишлиста — горизонтальная полоска чипов. */
@Composable
private fun WishToolbar(snap: Snapshot) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val v = snap.wishView
    fun set(nv: WishView) = scope.launch { Repo.setWishView(context, nv) }
    var menu by remember { mutableStateOf(false) }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    ) {
        item {
            Box {
                OutlineChip("↕ ${v.sort.title}", true) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    WishSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.title, fontWeight = if (sort == v.sort) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { menu = false; set(v.copy(sort = sort)) },
                        )
                    }
                }
            }
        }
        item { OutlineChip("Со скидкой", v.onlyDiscount) { set(v.copy(onlyDiscount = !v.onlyDiscount)) } }
        if (snap.accounts.size > 1) {
            items(snap.accounts, key = { it.steamId }) { acc ->
                OutlineChip("Только ${acc.region.name}", v.account == acc.steamId) {
                    set(v.copy(account = if (v.account == acc.steamId) null else acc.steamId))
                }
            }
        }
    }
}

@Composable
private fun OutlineChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        text, fontSize = 13.sp, maxLines = 1,
        color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else colors.outlineVariant, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

private val imageCache = LruCache<String, ImageBitmap>(64)

@Composable
private fun RemoteImage(url: String, modifier: Modifier) {
    val bitmap by produceState(imageCache.get(url), url) {
        if (value == null && url.isNotEmpty()) {
            value = withContext(Dispatchers.IO) {
                runCatching { URL(url).openStream().use(BitmapFactory::decodeStream)?.asImageBitmap() }.getOrNull()
            }?.also { imageCache.put(url, it) }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}
