package dev.steamprice

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PriceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val flow = Repo.state(context)
        provideContent {
            val s by flow.collectAsState()
            GlanceTheme { Content(context, s ?: Snapshot()) }
        }
    }

    @Composable
    private fun Content(context: Context, s: Snapshot) {
        val colors = GlanceTheme.colors
        Column(
            GlanceModifier.fillMaxSize().background(colors.widgetBackground)
                .cornerRadius(android.R.dimen.system_app_widget_background_radius).padding(12.dp)
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    GlanceModifier.defaultWeight().height(44.dp).background(colors.secondaryContainer).cornerRadius(22.dp)
                        .padding(horizontal = 14.dp).clickable(actionStartActivity(MainActivity.searchIntent(context))),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        ImageProvider(R.drawable.ic_search), "Поиск", GlanceModifier.size(20.dp),
                        colorFilter = ColorFilter.tint(colors.onSecondaryContainer),
                    )
                    Spacer(GlanceModifier.width(10.dp))
                    Text("Поиск игры", style = TextStyle(color = colors.onSecondaryContainer, fontSize = 15.sp))
                }
                Spacer(GlanceModifier.width(8.dp))
                Row(
                    GlanceModifier.size(44.dp).background(colors.secondaryContainer).cornerRadius(22.dp)
                        .clickable(actionRunCallback<RefreshAction>()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        ImageProvider(R.drawable.ic_refresh), "Обновить", GlanceModifier.size(20.dp),
                        colorFilter = ColorFilter.tint(if (s.loading) colors.outline else colors.onSecondaryContainer),
                    )
                }
            }

            val games = if (s.widgetSource == Source.WISHLIST) s.visibleWishlist.map { it.game } else s.recent
            if (games.isEmpty()) {
                Column(
                    GlanceModifier.fillMaxWidth().defaultWeight().clickable(actionStartActivity(MainActivity.searchIntent(context))),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (s.widgetSource == Source.WISHLIST) "Вишлист пуст" else "Тут появятся игры, которые ты смотрел",
                        style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp))
                }
            } else {
                LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    items(games, itemId = { it.id.toLong() }) { game -> GameRow(context, game, s) }
                }
            }

            Footer(s)
        }
    }

    @Composable
    private fun GameRow(context: Context, game: Game, s: Snapshot) {
        val colors = GlanceTheme.colors
        val prices = s.prices[game.id]
        val converted = convertedPrices(prices, s.rates, s.base)
        val best = cheapest(converted)
        val discount = maxDiscount(prices)

        Column(
            GlanceModifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp, end = 4.dp)
                .clickable(actionStartActivity(MainActivity.gameIntent(context, game.id)))
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    game.name, maxLines = 1, modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
                if (discount > 0) {
                    Text("−$discount%", style = TextStyle(color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                }
            }
            Spacer(GlanceModifier.height(2.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                Region.entries.forEach { region ->
                    val isBest = region == best
                    Column(GlanceModifier.defaultWeight()) {
                        Text(
                            priceText(prices?.get(region), region), maxLines = 1,
                            style = TextStyle(
                                color = if (isBest) colors.primary else colors.onSurface, fontSize = 13.sp,
                                fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                            ),
                        )
                        if (region != s.base) {
                            Text(
                                converted[region]?.let { "≈ ${formatMoney(it, s.base)}" } ?: "", maxLines = 1,
                                style = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Footer(s: Snapshot) {
        val ru = Locale.forLanguageTag("ru")
        val parts = buildList {
            s.rates?.let { r -> addAll(rateLines(r, s.base)) }
            when {
                s.error != null -> add("нет сети")
                s.updatedAt > 0 -> add(SimpleDateFormat("HH:mm", ru).format(Date(s.updatedAt)))
            }
        }
        if (parts.isNotEmpty()) {
            Text(
                parts.joinToString("  ·  "), maxLines = 1, modifier = GlanceModifier.padding(top = 8.dp, start = 4.dp),
                style = TextStyle(color = if (s.error != null) GlanceTheme.colors.error else GlanceTheme.colors.outline, fontSize = 10.sp),
            )
        }
    }
}

/** «1 € = 94,53 ₽» и т.п. — курсы остальных валют относительно основной. */
fun rateLines(r: Rates, base: Region): List<String> {
    val ru = Locale.forLanguageTag("ru")
    return Region.entries.filter { it != base }.mapNotNull { other ->
        // Показываем более «крупную» валюту слева, чтобы не было 0,002.
        val (big, small) = listOf(base, other).sortedByDescending { r.rubPer[it.currency] ?: 0.0 }
        val v = r.convert(1.0, big, small) ?: return@mapNotNull null
        "1 ${big.symbol} = ${"%.2f".format(ru, v)} ${small.symbol}"
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Repo.refresh(context)
    }
}

class PriceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PriceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Repo.schedule(context)
    }
}
