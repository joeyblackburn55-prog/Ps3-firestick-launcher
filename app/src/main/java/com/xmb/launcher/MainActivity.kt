package com.xmb.launcher

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONObject
import androidx.compose.foundation.border
import coil.ImageLoader
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import androidx.compose.ui.text.style.TextOverflow
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

const val FADE_MS = 2200L

data class Entry(val label: String, val pkg: String? = null, val action: String? = null, val desc: String? = null)
data class Cat(val name: String, val entries: List<Entry>)

/* ---------- CATEGORIES (edit packages here; in-app editor comes next) ---------- */
fun buildCats(ctx: Context): List<Cat> {
    val pm = ctx.packageManager
    val scanned = (pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0) +
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)).map { it.activityInfo.packageName }.toSet()
    fun inst(p: String) = p in scanned || pm.getLeanbackLaunchIntentForPackage(p) != null || pm.getLaunchIntentForPackage(p) != null
    fun apps(vararg e: Entry) = e.filter { it.pkg == null || inst(it.pkg) }
    val all = (pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0) +
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0))
        .map { it.activityInfo.packageName }.distinct().filter { it != ctx.packageName }
        .map { Entry(pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString(), it) }
        .sortedBy { it.label.lowercase() }
    val todo = "xmb.todo"
    return listOf(
        Cat("Settings", listOf(
            Entry("Language Settings", action = "xmb.set.0", desc = "Sets the menu language."),
            Entry("Date and Time Settings", action = "xmb.set.1", desc = "Sets the clock and the date format."),
            Entry("Weather Settings", action = "xmb.set.2", desc = "Sets the weather bar."),
            Entry("NHL Settings", action = "xmb.set.3", desc = "Sets your favorite team."),
            Entry("Sound Settings", action = "xmb.set.4", desc = "Sets the menu sounds."),
            Entry("Power Settings", action = "xmb.set.5", desc = "Sets the idle sleep mode."),
            Entry("Notification Settings", action = "xmb.set.6", desc = "Goal alerts for your favorite team."),
            Entry("Fire TV Settings", action = Settings.ACTION_SETTINGS, desc = "Opens the Fire TV system settings."),
            Entry("Amazon Home", action = "xmb.amazonhome", desc = "Opens the Amazon home screen."))),
        Cat("Photo", apps()),
        Cat("Music", apps(Entry("Spotify", "com.spotify.music"), Entry("YouTube Music", "com.google.android.apps.youtube.music"))),
        Cat("TV", apps(Entry("IPTV Smarters Pro", "com.nst.iptvsmarterstvbox"))),
        Cat("Streaming", apps(Entry("YouTube", "com.amazon.firetv.youtube"), Entry("Prime Video", "com.amazon.firebat"),
            Entry("Crave", "ca.bellmedia.cravetv"), Entry("Disney+", "com.disney.disneyplus"))),
        Cat("Games", apps()),
        Cat("NHL", listOf(Entry("Maple Leafs", action = "nhl.leafs"), Entry("Live Game", action = "nhl.live"), Entry("Today's Games", action = "nhl.today"),
            Entry("Yesterday's Scores", action = "nhl.yesterday"), Entry("Division", action = "nhl.division"), Entry("Wild Card", action = "nhl.wildcard"),
            Entry("League Standings", action = "nhl.standings"), Entry("Leaders", action = "nhl.leaders"))),
        Cat("Network", apps(Entry("App Store", "com.amazon.venezia"), Entry("Silk Browser", "com.amazon.cloud9"), Entry("Downloader", "com.esaba.downloader"))),
        Cat("All Apps", all)
    )
}

/* ---------- SOUND ---------- */
class Sfx(private val dir: File) {
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val ids = mutableMapOf<String, Int>()
    private val ok = mutableSetOf<Int>()
    private var mp: MediaPlayer? = null
    private fun find(n: String) = dir.listFiles()?.firstOrNull { it.name.substringBefore('.').lowercase() == n }
    init {
        pool.setOnLoadCompleteListener { _, id, st -> if (st == 0) ok.add(id) }
        listOf("move", "move_cat", "move_item", "select", "trophy").forEach { n -> find(n)?.let { ids[n] = pool.load(it.absolutePath, 1) } }
    }
    private fun play(n: String) {
        val v = Cfg.volume / 10f
        if (v <= 0f) return
        val id = ids[n]
        if (id != null && id in ok) { pool.play(id, v, v, 1, 0, 1f); return }
        find(n)?.let { f ->
            runCatching {
                MediaPlayer().apply {
                    setDataSource(f.path)
                    setOnPreparedListener { it.setVolume(v, v); it.start() }
                    setOnCompletionListener { it.release() }
                    setOnErrorListener { m, _, _ -> m.release(); true }
                    prepareAsync()
                }
            }
        }
    }
    fun move() = play("move")
    fun moveCat() = if (find("move_cat") != null) play("move_cat") else play("move")
    fun moveItem() = if (find("move_item") != null) play("move_item") else play("move")
    fun select() = play("select")
    fun trophy() = play("trophy")
    fun startup() { find("startup")?.let { runCatching { mp = MediaPlayer().apply { setDataSource(it.absolutePath); prepare(); start() } } } }
}

/* ---------- ACTIVITY ---------- */
class MainActivity : ComponentActivity() {
    private var cats by mutableStateOf<List<Cat>>(emptyList())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HomeGuard.ensureEnabled(this)
        Cfg.load(this)
        Idle.last = SystemClock.uptimeMillis()
        val dir = File(filesDir, "media").apply { mkdirs() }
        val sfx = Sfx(dir)
        val logo = File(dir, "logo.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
        cats = buildCats(this)
        val video = File(dir, "intro.mp4").takeIf { it.exists() }
        val bg = File(dir, "bg.mp4").takeIf { it.exists() }
        setContent { Root(cats, sfx, logo, video, bg) { launch(it) } }
    }
    override fun onResume() {
        super.onResume()
        Idle.last = SystemClock.uptimeMillis(); Idle.active = false
        Thread { val c = runCatching { buildCats(this) }.getOrNull(); if (c != null) runOnUiThread { cats = c } }.start()
    }
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val wasIdle = Idle.active
        Idle.last = SystemClock.uptimeMillis(); Idle.active = false
        if (wasIdle) return true
        return super.dispatchKeyEvent(e)
    }
    private fun launchPkg(p: String) {
        val pm = packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(p) ?: pm.getLaunchIntentForPackage(p) ?: run {
            val r = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).firstNotNullOfOrNull { c ->
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(c).setPackage(p), 0).firstOrNull() }
            r?.let { Intent(Intent.ACTION_MAIN).setClassName(it.activityInfo.packageName, it.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
        i?.let { startActivity(it) }
    }
    private fun openAmazonHome() {
        HomeKeyService.allowAmazonUntil = System.currentTimeMillis() + 15000
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage("com.amazon.tv.launcher")
        val ri = packageManager.resolveActivity(q, 0)
        if (ri != null) startActivity(Intent(q).setClassName(ri.activityInfo.packageName, ri.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else Toast.makeText(this, "Amazon Home not found", Toast.LENGTH_SHORT).show()
    }
    private fun launch(e: Entry) {
        try {
            when {
                e.action == "xmb.amazonhome" -> openAmazonHome()
                e.action == "xmb.todo" -> Toast.makeText(this, "Coming in the next step", Toast.LENGTH_SHORT).show()
                e.action != null -> startActivity(Intent(e.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                e.pkg != null -> launchPkg(e.pkg)
            }
        } catch (_: Exception) {}
    }
}

/* ---------- UI ---------- */
@Composable
fun Root(cats: List<Cat>, sfx: Sfx, logo: ImageBitmap?, video: File?, bg: File?, onLaunch: (Entry) -> Unit) {
    var intro by remember { mutableStateOf(true) }
    var reveal by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val mins = Cfg.idleOpts[Cfg.idle]
            if (mins > 0 && !intro && !Idle.active && SystemClock.uptimeMillis() - Idle.last > mins * 60_000L) { Idle.active = true; panel = null }
        }
    }
    val idleA by animateFloatAsState(if (Idle.active) 0f else 1f, tween(1200), label = "idle")
    val openOrLaunch: (Entry) -> Unit = { e -> if (e.action?.startsWith("nhl.") == true || e.action?.startsWith("xmb.set.") == true) panel = e.action else onLaunch(e) }
    val edge: List<Color>? = null
    BackHandler { if (panel != null) panel = null }
    val xmbA by animateFloatAsState(if (panel?.startsWith("xmb.set.") == true) 0f else 1f, tween(250), label = "xa")
    LaunchedEffect(Unit) {
        delay(3000)
        for (k in listOf("nhl.leafs", "nhl.live", "nhl.today", "nhl.yesterday", "nhl.division", "nhl.wildcard", "nhl.standings", "nhl.leaders")) {
            if (Nhl.cache[k] == null) withContext(Dispatchers.IO) { runCatching { Nhl.load(k) } }.getOrNull()?.let { Nhl.cache[k] = it }
        }
    }
    val menuA by animateFloatAsState(if (video == null || reveal) 1f else 0f, tween(FADE_MS.toInt() + 600, delayMillis = 200), label = "menu")
    LaunchedEffect(Unit) { if (video == null) { sfx.startup(); delay(3800); intro = false } }
    val introA by animateFloatAsState(if (intro) 1f else 0f, tween(900), label = "intro")
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (bg != null) BgVideo(bg) { Wave(edge) } else Wave(edge)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = menuA * idleA }) {
            if (cats.isNotEmpty()) Box(Modifier.fillMaxSize().graphicsLayer { alpha = xmbA }) { Xmb(cats, sfx, panel == null, openOrLaunch) }
            TopBars()
        }
        panel?.let { p ->
            if (p.startsWith("xmb.set.")) SettingsPanel(p.removePrefix("xmb.set.").toIntOrNull() ?: 0, cats.firstOrNull { c -> c.name == "Settings" }?.entries.orEmpty(), sfx) { panel = null }
            else NhlPanel(p) { panel = null }
        }
        if (video != null && intro) IntroVideo(video, { reveal = true }) { intro = false }
        if (video == null && introA > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = introA }.background(Color.Black), Alignment.Center) {
            if (logo != null) Image(logo, null, Modifier.width(360.dp))
            else BasicText("XMB", style = TextStyle(color = Color.White, fontSize = 64.sp))
        }
    }
}

fun sampleEdge(file: File): List<Color>? = runCatching {
    val r = android.media.MediaMetadataRetriever()
    r.setDataSource(file.path)
    val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
    val bmp = r.getFrameAtTime(max(0L, dur - 500L) * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
    r.release()
    if (bmp == null) null else {
        val n = 8
        List(n) { i ->
            val y = (((i + 0.5f) / n) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            val xs = listOf((bmp.width * 0.03f).toInt(), (bmp.width * 0.97f).toInt().coerceAtMost(bmp.width - 1))
            val px = xs.map { bmp.getPixel(it, y) }
            Color(px.map { android.graphics.Color.red(it) }.average().toFloat() / 255f,
                px.map { android.graphics.Color.green(it) }.average().toFloat() / 255f,
                px.map { android.graphics.Color.blue(it) }.average().toFloat() / 255f)
        }
    }
}.getOrNull()

@Composable
fun Wave(edge: List<Color>?) {
    val inf = rememberInfiniteTransition(label = "w")
    val t by inf.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(48000, easing = LinearEasing)), label = "t")
    val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(150000, easing = LinearEasing)), label = "p")
    val dust = remember {
        val r = java.util.Random(11)
        List(45) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 0.9f, r.nextFloat() * 6.28f, 0.3f + r.nextFloat() * 0.7f) }
    }
    val strands = 24
    val n = 48
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(0f to Color(0xFF6E5C2E), 0.35f to Color(0xFF55421B), 0.60f to Color(0xFF3A260D), 1f to Color(0xFF2A1A08), startY = 0f, endY = h))
        drawRect(Brush.radialGradient(listOf(Color(0xFFB89B4A).copy(alpha = 0.20f), Color.Transparent), center = Offset(w * 0.5f, 0f), radius = w * 0.65f))
        val xs = FloatArray(n + 1) { it * w / n }
        val cy = FloatArray(n + 1); val th = FloatArray(n + 1)
        for (i in 0..n) {
            val u = i / n.toFloat()
            cy[i] = h * (0.53f + 0.09f * cos(t) * (u - 0.4f) + 0.05f * sin(u * 3.2f + t))
            th[i] = h * (0.030f + 0.030f * (0.5f + 0.5f * sin(u * 4.3f + t * 2f + 1f)))
        }
        val warm = Color(0xFFF1E6C8)
        val body = Path()
        body.moveTo(xs[0], cy[0] - th[0])
        for (i in 1..n) body.lineTo(xs[i], cy[i] - th[i])
        for (i in n downTo 0) body.lineTo(xs[i], cy[i] + th[i])
        body.close()
        drawPath(body, Brush.horizontalGradient(listOf(warm.copy(alpha = 0.07f), warm.copy(alpha = 0.10f), warm.copy(alpha = 0.05f)), 0f, w))
        for (k in 0 until strands) {
            val ph = k * 0.83f
            val fr = 1.6f + (k % 4) * 0.7f
            val sp = 1 + k % 3
            val pa = Path()
            for (i in 0..n) {
                val u = i / n.toFloat()
                val y = cy[i] + sin(ph + u * fr * 3f + t * sp) * th[i]
                if (i == 0) pa.moveTo(xs[i], y) else pa.lineTo(xs[i], y)
            }
            val al = 0.10f + 0.16f * (0.5f + 0.5f * sin(k * 1.7f))
            val br = Brush.horizontalGradient(listOf(warm.copy(alpha = al * 0.5f), warm.copy(alpha = al), warm.copy(alpha = al * 0.55f)), 0f, w)
            drawPath(pa, br, alpha = 0.30f, style = Stroke(width = 6f, cap = StrokeCap.Round))
            drawPath(pa, br, style = Stroke(width = 1.3f, cap = StrokeCap.Round))
        }
        dust.forEach { q ->
            val px = ((q[0] + p) % 1f) * w
            val py = q[1] * h + sin(t + q[3]) * 10f
            val a = (0.06f + 0.25f * abs(sin(t * 2f + q[3]))) * q[4]
            drawCircle(warm.copy(alpha = a), radius = q[2], center = Offset(px, py))
        }
    }
}

@Composable
fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    val f = remember { SimpleDateFormat("EEE dd/MM   HH:mm", Locale.getDefault()) }
    Box(Modifier.fillMaxSize().padding(48.dp), Alignment.TopEnd) {
        BasicText(f.format(now), style = TextStyle(color = Color.White, fontSize = 22.sp))
    }
}

@Composable
fun Xmb(cats: List<Cat>, sfx: Sfx, active: Boolean, onLaunch: (Entry) -> Unit) {
    var sel by remember { mutableIntStateOf(0) }
    val idx = remember(cats.size) { mutableStateListOf<Int>().apply { repeat(cats.size) { add(0) } } }
    val fr = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) fr.requestFocus() }
    val animSel by animateFloatAsState(sel.toFloat(), tween(280), label = "sel")
    Box(Modifier.fillMaxSize().focusRequester(fr).focusable().onKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
        val list = cats[sel].entries
        when (ev.key) {
            Key.DirectionRight -> { if (sel < cats.size - 1) { sel++; sfx.moveCat() }; true }
            Key.DirectionLeft -> { if (sel > 0) { sel--; sfx.moveCat() }; true }
            Key.DirectionDown -> { if (idx[sel] < list.size - 1) { idx[sel]++; sfx.moveItem() }; true }
            Key.DirectionUp -> { if (idx[sel] > 0) { idx[sel]--; sfx.moveItem() }; true }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { list.getOrNull(idx[sel])?.let { sfx.select(); onLaunch(it) }; true }
            else -> false
        }
    }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val dn = LocalDensity.current
            val h = constraints.maxHeight.toFloat(); val w = constraints.maxWidth.toFloat()
            // Proportions measured from the real PS3 XMB
            val selX = w * 0.295f; val gap = w * 0.105f; val catY = h * 0.269f
            val iconPx = w * 0.036f
            val iconDp = with(dn) { iconPx.toDp() }
            val labelFs = with(dn) { (h * 0.024f).toSp() }
            cats.forEachIndexed { i, c ->
                val near = max(0f, 1f - abs(i - animSel))
                val cx = selX + (i - animSel) * gap
                if (cx > -gap && cx < w + gap) {
                    Box(Modifier.size(iconDp).offset { IntOffset((cx - iconPx / 2).roundToInt(), (catY - iconPx / 2).roundToInt()) }
                        .graphicsLayer { val sc = 1f + 0.6f * near; scaleX = sc; scaleY = sc; alpha = 0.55f + 0.45f * near }) { CatIcon(c.name, iconDp) }
                    Box(Modifier.width(with(dn) { (gap * 2).toDp() }).offset { IntOffset((cx - gap).roundToInt(), (catY + h * 0.05f).roundToInt()) }
                        .graphicsLayer { alpha = near * near }, Alignment.TopCenter) {
                        BasicText(tr(c.name), style = TextStyle(color = Color.White, fontSize = labelFs, shadow = TXT_SH))
                    }
                }
            }
            val curKind = cats[sel].entries.getOrNull(idx[sel])?.action?.takeIf { it.startsWith("nhl.") }
            if (curKind != null && active) NhlPreview(curKind, Modifier.offset { IntOffset((w * 0.54f).roundToInt(), (h * 0.36f).roundToInt()) }.width(maxWidth * 0.44f))
            key(sel) {
                val entries = cats[sel].entries
                if (entries.isEmpty()) Box(Modifier.offset { IntOffset((w * 0.355f).roundToInt(), (h * 0.49f).roundToInt()) }) {
                    BasicText("Empty", style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = with(dn) { (h * 0.03f).toSp() }, shadow = TXT_SH))
                } else ItemList(entries, idx[sel], w, h, selX)
            }
        }
    }
}

@Composable
fun ItemList(entries: List<Entry>, index: Int, w: Float, h: Float, selX: Float) {
    val a by animateFloatAsState(index.toFloat(), tween(220), label = "idx")
    val ctx = LocalContext.current
    val dn = LocalDensity.current
    val iconPx = w * 0.040f
    val iconDp = with(dn) { iconPx.toDp() }
    val gapDp = with(dn) { (w * 0.355f - selX - iconPx / 2f).toDp() }
    val selY = h * 0.51f; val above0 = h * 0.208f; val below1 = h * 0.14f; val step = h * 0.074f
    entries.forEachIndexed { j, e ->
        val dy = j - a
        if (abs(dy) > 8f) return@forEachIndexed
        val y = when {
            dy >= 1f -> selY + below1 + (dy - 1f) * step
            dy >= 0f -> selY + dy * below1
            dy > -1f -> selY + dy * (selY - above0)
            else -> above0 + (dy + 1f) * step
        }
        val s = 1f - min(1f, abs(dy))
        val fade = ((y - h * 0.05f) / (h * 0.07f)).coerceIn(0f, 1f)
        if (fade <= 0f || y > h * 1.02f) return@forEachIndexed
        key(j) {
            val icon = remember(e) { e.pkg?.let { p -> runCatching { ctx.packageManager.getApplicationIcon(p).toBitmap(96, 96).asImageBitmap() }.getOrNull() } }
            Row(Modifier.offset { IntOffset((selX - iconPx / 2f).roundToInt(), (y - iconPx / 2f).roundToInt()) }
                .graphicsLayer { alpha = fade * (0.6f + 0.4f * s) }, verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) Image(icon, null, Modifier.size(iconDp).clip(RoundedCornerShape(8.dp)))
                else if (e.action?.startsWith("xmb.set.") == true) SettingsItemIcon(e.action.removePrefix("xmb.set.").toIntOrNull() ?: 0, iconDp)
                else if (e.action.orEmpty().let { it.startsWith("nhl.") || it == "xmb.settings" || it == "xmb.amazonhome" || it == Settings.ACTION_SETTINGS }) NhlEntryIcon(e.action.orEmpty(), iconDp)
                else Box(Modifier.size(iconDp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.2f)))
                Spacer(Modifier.width(gapDp))
                Box {
                    BasicText(if (e.action == "nhl.leafs") Cfg.teamShort else tr(e.label), maxLines = 1, style = TextStyle(color = Color.White, fontSize = with(dn) { (h * (0.030f + 0.004f * s)).toSp() },
                        fontWeight = FontWeight.Light, shadow = if (s > 0.5f && e.desc != null) PS_GLOW else TXT_SH))
                    if (e.desc != null) BasicText(tr(e.desc), Modifier.offset(y = with(dn) { (h * 0.040f).toDp() }).graphicsLayer { alpha = s }, maxLines = 1,
                        style = TextStyle(color = Color.White, fontSize = with(dn) { (h * 0.019f).toSp() }, shadow = TXT_SH))
                }
            }
        }
    }
}

@Composable
fun IntroVideo(file: File, onFadeStart: () -> Unit, onFinished: () -> Unit) {
    var fade by remember { mutableStateOf(false) }
    LaunchedEffect(fade) { if (fade) onFadeStart() }
    val a by animateFloatAsState(if (fade) 0f else 1f, tween(FADE_MS.toInt()), label = "vf", finishedListener = { if (it == 0f) onFinished() })
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a }.background(Color.Black)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    var mp: MediaPlayer? = null
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        mp = MediaPlayer().apply {
                            setDataSource(file.path); setSurface(Surface(st))
                            setOnPreparedListener { p -> p.start(); Handler(Looper.getMainLooper()).postDelayed({ fade = true }, max(0L, p.duration - FADE_MS)) }
                            setOnCompletionListener { fade = true }
                            setOnErrorListener { _, _, _ -> onFinished(); true }
                            prepareAsync()
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { runCatching { mp?.release() }; mp = null; return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        })
    }
}

@Composable
fun CatIcon(name: String, size: Dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 100f
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        fun poly(vararg v: Float, close: Boolean = false): Path {
            val pa = Path(); pa.moveTo(v[0] * u, v[1] * u)
            var i = 2
            while (i < v.size) { pa.lineTo(v[i] * u, v[i + 1] * u); i += 2 }
            if (close) pa.close()
            return pa
        }
        for (pass in 0..1) {
            val glow = pass == 0
            val c = Color.White.copy(alpha = if (glow) 0.20f else 0.95f)
            val sw = (if (glow) 10f else 3.6f) * u
            val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float = sw) = drawLine(c, o(x1, y1), o(x2, y2), w, StrokeCap.Round)
            fun box(x: Float, y: Float, w: Float, h: Float, r: Float) =
                drawRoundRect(color = c, topLeft = o(x, y), size = Size(w * u, h * u), cornerRadius = CornerRadius(r * u), style = st)
            when (name) {
                "Settings" -> {
                    drawCircle(c, 22 * u, o(50f, 50f), style = st); drawCircle(c, 8 * u, o(50f, 50f), style = st)
                    for (i in 0 until 8) {
                        val a = i * PI / 4
                        val cx = cos(a).toFloat(); val cy = sin(a).toFloat()
                        line(50 + 30 * cx, 50 + 30 * cy, 50 + 40 * cx, 50 + 40 * cy, sw * 1.7f)
                    }
                }
                "Photo" -> {
                    box(12f, 22f, 76f, 56f, 8f)
                    drawPath(poly(20f, 70f, 40f, 46f, 54f, 62f, 64f, 52f, 80f, 70f), c, style = st)
                    drawCircle(c, 6 * u, o(68f, 38f), style = st)
                }
                "Music" -> {
                    drawCircle(c, 9 * u, o(32f, 74f)); drawCircle(c, 9 * u, o(68f, 66f))
                    line(41f, 74f, 41f, 26f); line(77f, 66f, 77f, 18f); line(41f, 26f, 77f, 18f, sw * 1.6f)
                }
                "TV" -> {
                    box(12f, 20f, 76f, 50f, 6f)
                    line(34f, 84f, 66f, 84f); line(50f, 70f, 50f, 84f)
                }
                "Streaming" -> {
                    box(12f, 24f, 76f, 52f, 8f)
                    drawPath(poly(42f, 38f, 42f, 62f, 64f, 50f, close = true), c, style = st)
                }
                "Games" -> {
                    box(8f, 32f, 84f, 40f, 20f)
                    line(28f, 43f, 28f, 61f); line(19f, 52f, 37f, 52f)
                    drawCircle(c, 4.5f * u, o(64f, 46f), style = st); drawCircle(c, 4.5f * u, o(75f, 57f), style = st)
                }
                "NHL" -> {
                    drawPath(poly(22f, 12f, 44f, 72f, 66f, 72f), c, style = st)
                    drawOval(color = c, topLeft = o(68f, 64f), size = Size(24f * u, 12f * u), style = st)
                }
                "Network" -> {
                    drawCircle(c, 36 * u, o(50f, 50f), style = st)
                    drawOval(color = c, topLeft = o(32f, 14f), size = Size(36f * u, 72f * u), style = st)
                    line(14f, 50f, 86f, 50f)
                }
                "All Apps" -> {
                    box(16f, 16f, 28f, 28f, 6f); box(56f, 16f, 28f, 28f, 6f)
                    box(16f, 56f, 28f, 28f, 6f); box(56f, 56f, 28f, 28f, 6f)
                }
                else -> drawCircle(c, 30 * u, o(50f, 50f), style = st)
            }
        }
    }
}

/* ---------- NHL ---------- */
const val LOGO_VARIANT = "dark" // change to "light" if logos look wrong on the gold background

data class NRow(
    val cols: List<String>, val header: Boolean = false, val hl: Boolean = false,
    val logos: List<String?>? = null, val big: Boolean = false, val w: List<Float>? = null,
    val badges: List<String> = emptyList(), val badgeCol: Int = -1
)
data class NPanel(val title: String, val weights: List<Float>, val rows: List<NRow>)

object LogoLoader {
    private var l: ImageLoader? = null
    fun get(c: Context): ImageLoader = l ?: ImageLoader.Builder(c.applicationContext).components { add(SvgDecoder.Factory()) }.crossfade(true).build().also { l = it }
}

@Composable
fun TeamLogo(abbr: String, size: Dp) {
    val ctx = LocalContext.current
    AsyncImage(
        model = "https://assets.nhle.com/logos/nhl/svg/${abbr}_$LOGO_VARIANT.svg",
        contentDescription = null, imageLoader = remember { LogoLoader.get(ctx) }, modifier = Modifier.size(size)
    )
}

object Nhl {
    val cache = java.util.concurrent.ConcurrentHashMap<String, NPanel>()
    fun refreshMs(kind: String) = when (kind) { "nhl.live" -> 15000L; "nhl.today", "nhl.leafs" -> 30000L; else -> 300000L }

    private fun get(path: String): JSONObject {
        val c = URL("https://api-web.nhle.com/v1/$path").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "XMB-Launcher")
        return try { JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
    }
    // Value can be a plain string or {"default": "..."}
    private fun d(j: JSONObject?, key: String): String = when (val v = j?.opt(key)) {
        is JSONObject -> v.optString("default"); is String -> v; else -> ""
    }
    private fun ymd(offset: Int): String {
        val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, offset)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }
    private fun localTime(utc: String): String = runCatching {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(f.parse(utc)!!)
    }.getOrDefault("")
    private fun perLabel(pd: JSONObject?): String {
        val type = pd?.optString("periodType") ?: "REG"
        return if (type == "OT" || type == "SO") type else "P" + (pd?.optInt("number") ?: 0)
    }
    private fun status(g: JSONObject): String = when (g.optString("gameState")) {
        "FUT", "PRE" -> localTime(g.optString("startTimeUTC"))
        "LIVE", "CRIT" -> {
            val per = perLabel(g.optJSONObject("periodDescriptor"))
            val clk = g.optJSONObject("clock")
            if (clk?.optBoolean("inIntermission") == true) "$per INT" else per + " " + (clk?.optString("timeRemaining") ?: "")
        }
        else -> when (g.optJSONObject("gameOutcome")?.optString("lastPeriodType")) { "OT" -> "Final/OT"; "SO" -> "Final/SO"; else -> "Final" }
    }
    private fun isLive(g: JSONObject) = g.optString("gameState") in listOf("LIVE", "CRIT")
    private fun isDone(g: JSONObject) = g.optString("gameState") in listOf("FINAL", "OFF")
    // Canadian channels first (you are in Quebec); falls back to US networks
    private fun watch(g: JSONObject): List<String> {
        val arr = g.optJSONArray("tvBroadcasts") ?: return emptyList()
        val l = (0 until arr.length()).map { arr.getJSONObject(it) }
        val ca = l.filter { it.optString("countryCode") == "CA" }
        val pick = if (ca.isNotEmpty()) ca else l.filter { it.optString("countryCode") == "US" }
        return pick.map { it.optString("network") }.filter { it.isNotEmpty() }.distinct().take(3)
    }

    private fun gameRow(g: JSONObject): NRow {
        val a = g.optJSONObject("awayTeam"); val h = g.optJSONObject("homeTeam")
        val ab = d(a, "abbrev"); val hb = d(h, "abbrev")
        val started = g.optString("gameState") !in listOf("FUT", "PRE")
        val sa = if (started) (a?.optInt("score", 0) ?: 0).toString() else "-"
        val sh = if (started) (h?.optInt("score", 0) ?: 0).toString() else "-"
        return NRow(listOf("$ab  $sa", "$hb  $sh", status(g), ""), hl = ab == Cfg.teamAbbr || hb == Cfg.teamAbbr, logos = listOf(ab, hb, null, null),
            badges = if (isDone(g)) emptyList() else watch(g), badgeCol = 3)
    }
    private fun games(offset: Int, title: String): NPanel {
        val arr = get("score/" + ymd(offset)).optJSONArray("games")
        val rows = mutableListOf(NRow(listOf("AWAY", "HOME", "STATUS", if (offset == 0) "WATCH" else ""), header = true))
        if (arr == null || arr.length() == 0) rows += NRow(listOf("No games")) else for (i in 0 until arr.length()) rows += gameRow(arr.getJSONObject(i))
        return NPanel(title, listOf(1.2f, 1.2f, 0.95f, 1.5f), rows)
    }

    private fun teams(): List<JSONObject> {
        val arr = get("standings/now").getJSONArray("standings")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }
    private fun tAb(t: JSONObject) = d(t, "teamAbbrev")
    private fun tRow(rank: String, t: JSONObject, wide: Boolean = true): NRow {
        val ab = tAb(t); val nm = d(t, "teamName").ifEmpty { ab }
        return if (wide) NRow(listOf(rank, nm, "${t.optInt("gamesPlayed")}", "${t.optInt("wins")}", "${t.optInt("losses")}", "${t.optInt("otLosses")}", "${t.optInt("points")}"),
            hl = ab == Cfg.teamAbbr, logos = listOf(null, ab, null, null, null, null, null))
        else NRow(listOf(rank, nm, "${t.optInt("gamesPlayed")}", "${t.optInt("points")}"), hl = ab == Cfg.teamAbbr, logos = listOf(null, ab, null, null))
    }
    private val head = NRow(listOf("#", "TEAM", "GP", "W", "L", "OTL", "PTS"), header = true)
    private val headS = NRow(listOf("", "TEAM", "GP", "PTS"), header = true)
    private val wideW = listOf(0.5f, 2.6f, 0.8f, 0.8f, 0.8f, 0.8f, 1f)

    private fun standings(): NPanel {
        val list = teams(); val rows = mutableListOf<NRow>()
        fun block(l: List<JSONObject>) = l.sortedWith(compareByDescending<JSONObject> { it.optInt("points") }.thenByDescending { it.optInt("wins") })
            .forEachIndexed { i, t -> rows += tRow("${i + 1}", t) }
        if (list.any { it.optString("conferenceAbbrev") in listOf("E", "W") }) {
            for ((code, name) in listOf("E" to "EASTERN CONFERENCE", "W" to "WESTERN CONFERENCE")) {
                rows += NRow(listOf(name), header = true); rows += head
                block(list.filter { it.optString("conferenceAbbrev") == code })
            }
        } else { rows += head; block(list) }
        return NPanel("League standings", wideW, rows)
    }
    private fun division(): NPanel {
        val list = teams(); val rows = mutableListOf<NRow>()
        val myDiv = list.firstOrNull { tAb(it) == Cfg.teamAbbr }?.optString("divisionName") ?: ""
        val divs = list.map { it.optString("divisionName") }.distinct().sortedBy { if (it == myDiv) 0 else 1 }
        for (dn in divs) {
            rows += NRow(listOf(dn.uppercase() + " DIVISION"), header = true); rows += head
            list.filter { it.optString("divisionName") == dn }
                .sortedWith(compareBy<JSONObject> { if (it.optInt("divisionSequence") > 0) it.optInt("divisionSequence") else 99 }.thenByDescending { it.optInt("points") })
                .forEachIndexed { i, t -> rows += tRow("${i + 1}", t) }
        }
        return NPanel("Division standings", wideW, rows)
    }
    private fun wildcard(): NPanel {
        val list = teams(); val rows = mutableListOf<NRow>()
        val myConf = list.firstOrNull { tAb(it) == Cfg.teamAbbr }?.optString("conferenceName") ?: ""
        val confs = list.map { it.optString("conferenceName") }.distinct().sortedBy { if (it == myConf) 0 else 1 }
        for (cn in confs) {
            val c = list.filter { it.optString("conferenceName") == cn }
            rows += NRow(listOf(cn.uppercase() + " CONFERENCE"), header = true)
            for (dn in c.map { it.optString("divisionName") }.distinct()) {
                val dt = c.filter { it.optString("divisionName") == dn }.sortedBy { it.optInt("divisionSequence") }.take(3)
                rows += NRow(listOf(dn.uppercase()), header = true); rows += headS
                dt.forEachIndexed { i, t -> rows += tRow("${dn.take(1)}${i + 1}", t, false) }
            }
            val wc = c.filter { it.optInt("divisionSequence") > 3 }.sortedWith(compareBy<JSONObject> { if (it.optInt("wildcardSequence") > 0) it.optInt("wildcardSequence") else 99 }.thenByDescending { it.optInt("points") })
            rows += NRow(listOf("WILD CARD"), header = true); rows += headS
            wc.take(2).forEachIndexed { i, t -> rows += tRow("WC${i + 1}", t, false) }
            rows += NRow(listOf("IN THE HUNT"), header = true)
            wc.drop(2).take(4).forEach { t -> rows += tRow("", t, false) }
        }
        return NPanel("Wild card race", listOf(0.7f, 2.6f, 0.8f, 1f), rows)
    }
    private fun leaders(): NPanel {
        val j = get("skater-stats-leaders/current?categories=points,goals,assists&limit=10")
        val rows = mutableListOf<NRow>()
        for ((k, label) in listOf("points" to "POINTS", "goals" to "GOALS", "assists" to "ASSISTS")) {
            val arr = j.optJSONArray(k) ?: continue
            rows += NRow(listOf(label), header = true)
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val name = d(p, "firstName").take(1) + ". " + d(p, "lastName")
                val team = p.optString("teamAbbrev")
                rows += NRow(listOf("${i + 1}", name, team, p.optInt("value").toString()), hl = team == Cfg.teamAbbr, logos = listOf(null, null, team, null))
            }
        }
        return NPanel("Scoring leaders", listOf(0.5f, 2.5f, 1.2f, 0.8f), rows)
    }
    private fun leafRow(g: JSONObject, withWatch: Boolean = false): NRow {
        val a = g.optJSONObject("awayTeam"); val h = g.optJSONObject("homeTeam")
        val home = d(h, "abbrev") == Cfg.teamAbbr
        val opp = d(if (home) a else h, "abbrev")
        val my = (if (home) h else a)?.optInt("score", 0) ?: 0
        val th = (if (home) a else h)?.optInt("score", 0) ?: 0
        val st = g.optString("gameState")
        val extra = status(g).removePrefix("Final").removePrefix("/")
        val res = when (st) {
            "FINAL", "OFF" -> (if (my > th) "W " else "L ") + "$my-$th" + (if (extra.isNotEmpty()) " ($extra)" else "")
            "LIVE", "CRIT" -> "$my-$th  " + status(g)
            else -> localTime(g.optString("startTimeUTC"))
        }
        return if (withWatch) NRow(listOf(g.optString("gameDate").takeLast(5), (if (home) "vs " else "@ ") + opp, res, ""), logos = listOf(null, opp, null, null), badges = watch(g), badgeCol = 3)
        else NRow(listOf(g.optString("gameDate").takeLast(5), (if (home) "vs " else "@ ") + opp, res), logos = listOf(null, opp, null))
    }
    private fun leafGames(): List<JSONObject> {
        val arr = get("club-schedule-season/${Cfg.teamAbbr}/now").optJSONArray("games") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }
    private fun leafs(): NPanel {
        val rows = mutableListOf<NRow>()
        teams().firstOrNull { tAb(it) == Cfg.teamAbbr }?.let { t ->
            rows += NRow(listOf("${t.optInt("wins")}-${t.optInt("losses")}-${t.optInt("otLosses")}   |   ${t.optInt("points")} PTS   |   ${t.optInt("gamesPlayed")} GP   |   ${t.optString("divisionName")} #${t.optInt("divisionSequence")}"),
                header = true, logos = null)
        }
        val list = leafGames()
        val done = list.filter { it.optString("gameState") in listOf("FINAL", "OFF") }.takeLast(3)
        val next = list.filter { it.optString("gameState") !in listOf("FINAL", "OFF") }.take(3)
        rows += NRow(listOf("RECENT"), header = true); done.forEach { rows += leafRow(it) }
        rows += NRow(listOf("UPCOMING"), header = true); next.forEach { rows += leafRow(it, true) }
        return NPanel(Cfg.teamName, listOf(0.8f, 1.3f, 1.2f, 1.5f), rows)
    }
    private fun live(): NPanel {
        val today = get("score/now").optJSONArray("games")
        val gl = if (today == null) emptyList() else (0 until today.length()).map { today.getJSONObject(it) }
        val g = gl.firstOrNull { d(it.optJSONObject("awayTeam"), "abbrev") == Cfg.teamAbbr || d(it.optJSONObject("homeTeam"), "abbrev") == Cfg.teamAbbr } ?: gl.firstOrNull { isLive(it) }
        if (g == null || g.optString("gameState") in listOf("FUT", "PRE")) {
            val rows = mutableListOf(NRow(listOf(if (g == null) "No game in progress" else "Next game today at " + localTime(g.optString("startTimeUTC"))), header = true))
            leafGames().filter { it.optString("gameState") !in listOf("FINAL", "OFF") }.take(1).forEach { rows += NRow(listOf("NEXT GAME"), header = true); rows += leafRow(it, true) }
            return NPanel("Live game", listOf(0.8f, 1.3f, 1.2f, 1.5f), rows)
        }
        val a = g.optJSONObject("awayTeam"); val h = g.optJSONObject("homeTeam")
        val ab = d(a, "abbrev"); val hb = d(h, "abbrev")
        val rows = mutableListOf<NRow>()
        rows += NRow(listOf("$ab  ${a?.optInt("score", 0) ?: 0}", "$hb  ${h?.optInt("score", 0) ?: 0}"), hl = false, logos = listOf(ab, hb), big = true, w = listOf(1f, 1f))
        rows += NRow(listOf(status(g)), header = true)
        watch(g).takeIf { it.isNotEmpty() }?.let { rows += NRow(listOf("WATCH ON", ""), header = true, badges = it, badgeCol = 1, w = listOf(1f, 3f)) }
        val land = runCatching { get("gamecenter/${g.optInt("id")}/landing") }.getOrNull()
        if (land != null) {
            val sa = land.optJSONObject("awayTeam")?.optInt("sog", -1) ?: -1
            val sh = land.optJSONObject("homeTeam")?.optInt("sog", -1) ?: -1
            val sums = land.optJSONObject("summary")
            val sbp = sums?.optJSONArray("shotsByPeriod")
            if (sa >= 0 || sbp != null) {
                rows += NRow(listOf("SHOTS ON GOAL", ab, hb), header = true, w = listOf(2f, 1f, 1f))
                if (sa >= 0) rows += NRow(listOf("Total", "$sa", "$sh"), w = listOf(2f, 1f, 1f))
                if (sbp != null) for (i in 0 until sbp.length()) {
                    val o = sbp.getJSONObject(i)
                    rows += NRow(listOf(perLabel(o.optJSONObject("periodDescriptor")), "${o.optInt("away")}", "${o.optInt("home")}"), w = listOf(2f, 1f, 1f))
                }
            }
            val sc = sums?.optJSONArray("scoring")
            var any = false
            if (sc != null) for (i in 0 until sc.length()) {
                val per = sc.getJSONObject(i); val goals = per.optJSONArray("goals") ?: continue
                for (n in 0 until goals.length()) {
                    if (!any) { rows += NRow(listOf("GOALS"), header = true); any = true }
                    val gl2 = goals.getJSONObject(n)
                    val team = d(gl2, "teamAbbrev")
                    val who = d(gl2, "lastName").ifEmpty { d(gl2, "name") }
                    val ast = gl2.optJSONArray("assists")
                    val names = if (ast == null) "" else (0 until ast.length()).map { d(ast.getJSONObject(it), "lastName").ifEmpty { d(ast.getJSONObject(it), "name") } }.filter { it.isNotEmpty() }.joinToString(", ")
                    rows += NRow(listOf(perLabel(per.optJSONObject("periodDescriptor")) + " " + gl2.optString("timeInPeriod"), team, who + (if (names.isNotEmpty()) " ($names)" else ""), "${gl2.optInt("awayScore")}-${gl2.optInt("homeScore")}"),
                        hl = team == Cfg.teamAbbr, logos = listOf(null, team, null, null), w = listOf(1.1f, 1f, 2.6f, 0.7f))
                }
            }
        } else rows += NRow(listOf("Game detail unavailable"), header = true)
        return NPanel("Live game", listOf(1f), rows)
    }
    fun load(kind: String): NPanel = when (kind) {
        "nhl.today" -> games(0, "Today's games")
        "nhl.yesterday" -> games(-1, "Yesterday's scores")
        "nhl.standings" -> standings()
        "nhl.division" -> division()
        "nhl.wildcard" -> wildcard()
        "nhl.leaders" -> leaders()
        "nhl.live" -> live()
        else -> leafs()
    }
}

@Composable
fun NRowView(r: NRow, defW: List<Float>, small: Boolean) {
    val gold = Color(0xFFFFE08A); val head = Color(0xFFE6D7A8)
    val sh = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 6f)
    val col = if (r.hl) gold else Color.White
    val fs = if (r.big) 28.sp else if (small) 15.sp else 18.sp
    val hs = if (small) 12.sp else 14.sp
    if (r.cols.size == 1) {
        BasicText(tr(r.cols[0]), Modifier.fillMaxWidth().padding(top = if (r.header) 10.dp else 3.dp, bottom = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (r.header) head else col, fontSize = if (r.header) hs else fs, fontWeight = if (r.header) FontWeight.Medium else FontWeight.Light,
                letterSpacing = if (r.header) 1.2.sp else 0.sp, shadow = sh))
    } else {
        val w = r.w ?: defW
        val logoSize = if (r.big) 46.dp else if (small) 20.dp else 24.dp
        Row(Modifier.fillMaxWidth().padding(vertical = if (r.big) 6.dp else 3.dp), verticalAlignment = Alignment.CenterVertically) {
            r.cols.forEachIndexed { i, c ->
                Row(Modifier.weight(w.getOrElse(i) { 1f }), verticalAlignment = Alignment.CenterVertically) {
                    val lg = r.logos?.getOrNull(i)
                    if (!lg.isNullOrEmpty()) { TeamLogo(lg, logoSize); Spacer(Modifier.width(if (r.big) 12.dp else 8.dp)) }
                    if (i == r.badgeCol) Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        r.badges.forEach { ChannelBadge(it, if (small) 19.dp else 23.dp) }
                    }
                    else BasicText(tr(c), Modifier.weight(1f, false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = if (r.header) head else col, fontSize = if (r.header) hs else fs,
                            fontWeight = if (r.header) FontWeight.Medium else if (r.big) FontWeight.Normal else FontWeight.Light,
                            letterSpacing = if (r.header) 1.0.sp else 0.sp, shadow = sh))
                }
            }
        }
    }
    if (!r.header) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
}

@Composable
fun rememberNhl(kind: String): State<NPanel?> {
    val st = remember(kind) { mutableStateOf(Nhl.cache[kind]) }
    LaunchedEffect(kind) {
        if (st.value == null) delay(250)
        while (true) {
            val r = withContext(Dispatchers.IO) { runCatching { Nhl.load(kind) } }.getOrNull()
            if (r != null) { Nhl.cache[kind] = r; st.value = r }
            else if (st.value == null) st.value = NPanel("NHL", listOf(1f), listOf(NRow(listOf("Can't reach NHL data"))))
            delay(Nhl.refreshMs(kind))
        }
    }
    return st
}

@Composable
fun NhlPreview(kind: String, modifier: Modifier) {
    val data by rememberNhl(kind)
    var shown by remember(kind) { mutableStateOf(false) }
    LaunchedEffect(kind) { shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(350), label = "pv")
    val max = if (kind == "nhl.live") 11 else 9
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.graphicsLayer { alpha = a }.clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0x30FFFFFF), Color(0x10FFFFFF))))
        .border(1.dp, Color.White.copy(alpha = 0.25f), shape).padding(horizontal = 18.dp, vertical = 14.dp)) {
        val p = data
        BasicText(tr(p?.title ?: "NHL").uppercase(), style = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp,
            shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 6f)))
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.3f)))
        if (p == null) BasicText(tr("Loading..."), Modifier.padding(top = 8.dp), style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp))
        else {
            p.rows.take(max).forEach { NRowView(it, p.weights, true) }
            if (p.rows.size > max) BasicText(if (Cfg.lang == 1) "+ ${p.rows.size - max} de plus   -   appuyez sur OK" else "+ ${p.rows.size - max} more   -   press OK", Modifier.padding(top = 8.dp),
                style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, letterSpacing = 1.sp))
        }
    }
}

@Composable
fun NhlPanel(kind: String, onClose: () -> Unit) {
    val data by rememberNhl(kind)
    val fr = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { fr.requestFocus() }
    BackHandler { onClose() }
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).focusRequester(fr).focusable().onKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (ev.key) {
            Key.Back, Key.Escape -> { onClose(); true }
            Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(240f) }; true }
            Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-240f) }; true }
            else -> false
        }
    }, contentAlignment = Alignment.Center) {
        Column(Modifier.width(680.dp).fillMaxHeight(0.82f).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xE61E1608), Color(0xE6120C04))))
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape).padding(horizontal = 26.dp, vertical = 22.dp)) {
            val p = data
            BasicText(tr(p?.title ?: "NHL").uppercase(), style = TextStyle(color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Light, letterSpacing = 3.sp,
                shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 6f)))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.3f)))
            if (p == null) BasicText(tr("Loading..."), Modifier.padding(top = 10.dp), style = TextStyle(color = Color.White, fontSize = 18.sp))
            else Column(Modifier.weight(1f).verticalScroll(scroll)) { p.rows.forEach { NRowView(it, p.weights, false) } }
            Spacer(Modifier.height(8.dp))
            BasicText(tr("UP / DOWN scroll    BACK close    auto-refreshing"), style = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, letterSpacing = 1.sp))
        }
    }
}

fun chStyle(code: String): Pair<String, Long> = when {
    code.startsWith("TSN") -> code to 0xFFFF4A52
    code == "SN" -> "SPORTSNET" to 0xFF4D9BFF
    code == "SNE" -> "SN EAST" to 0xFF4D9BFF
    code == "SNO" -> "SN ONT" to 0xFF4D9BFF
    code == "SNP" -> "SN PAC" to 0xFF4D9BFF
    code == "SNW" -> "SN WEST" to 0xFF4D9BFF
    code.startsWith("SN") -> code to 0xFF4D9BFF
    code == "CBC" -> "CBC" to 0xFFFF5A6E
    code == "CITY" -> "CITY" to 0xFFE6E6E6
    code.startsWith("TVAS") -> ("TVA " + code.removePrefix("TVAS").ifEmpty { "SPORTS" }) to 0xFF5AA0FF
    code.startsWith("RDS") -> code to 0xFFFF4A52
    code == "NHLN" -> "NHL NET" to 0xFF9CB8FF
    code.startsWith("ESPN") -> code to 0xFFFF5252
    code == "ABC" -> "ABC" to 0xFFFFFFFF
    code == "TNT" || code == "TBS" -> code to 0xFFCFCFCF
    else -> code to 0xFFFFFFFF
}

@Composable
fun ChannelBadge(code: String, h: Dp) {
    val (name, color) = chStyle(code)
    BasicText(name, maxLines = 1, style = TextStyle(color = Color(color), fontSize = (h.value * 0.72f).sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
        shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 6f)))
}

@Composable
fun NhlEntryIcon(action: String, size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Brush.verticalGradient(listOf(Color(0x44FFFFFF), Color(0x18FFFFFF)))), Alignment.Center) {
        if (action == "nhl.leafs") TeamLogo(Cfg.teamAbbr, size * 0.8f)
        else Canvas(Modifier.size(size * 0.64f)) {
            val u = this.size.width / 100f
            fun o(x: Float, y: Float) = Offset(x * u, y * u)
            for (pass in 0..1) {
                val glow = pass == 0
                val c = Color.White.copy(alpha = if (glow) 0.22f else 0.95f)
                val sw = (if (glow) 11f else 5f) * u
                val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, o(x1, y1), o(x2, y2), sw, StrokeCap.Round)
                fun box(x: Float, y: Float, w: Float, h: Float, r: Float) =
                    drawRoundRect(color = c, topLeft = o(x, y), size = Size(w * u, h * u), cornerRadius = CornerRadius(r * u), style = st)
                fun arc(x: Float, y: Float, w: Float, h: Float, a: Float, sweep: Float) =
                    drawArc(color = c, startAngle = a, sweepAngle = sweep, useCenter = false, topLeft = o(x, y), size = Size(w * u, h * u), style = st)
                when (action) {
                    "nhl.live" -> {
                        drawCircle(c, 9 * u, o(50f, 50f))
                        arc(26f, 26f, 48f, 48f, -45f, 90f); arc(26f, 26f, 48f, 48f, 135f, 90f)
                        arc(8f, 8f, 84f, 84f, -45f, 90f); arc(8f, 8f, 84f, 84f, 135f, 90f)
                    }
                    "nhl.today" -> {
                        box(14f, 20f, 72f, 66f, 8f); line(14f, 40f, 86f, 40f); line(34f, 10f, 34f, 28f); line(66f, 10f, 66f, 28f)
                        drawCircle(c, 10 * u, o(50f, 65f))
                    }
                    "nhl.yesterday" -> {
                        box(14f, 20f, 72f, 66f, 8f); line(14f, 40f, 86f, 40f); line(34f, 10f, 34f, 28f); line(66f, 10f, 66f, 28f)
                        line(64f, 64f, 36f, 64f); line(36f, 64f, 47f, 53f); line(36f, 64f, 47f, 75f)
                    }
                    "nhl.division" -> {
                        box(14f, 52f, 22f, 34f, 4f); box(39f, 24f, 22f, 62f, 4f); box(64f, 66f, 22f, 20f, 4f)
                    }
                    "nhl.wildcard" -> {
                        box(22f, 10f, 56f, 80f, 8f)
                        val star = Path()
                        for (i in 0 until 10) {
                            val r = if (i % 2 == 0) 22f else 9f
                            val a = (-PI / 2 + i * PI / 5).toFloat()
                            val x = 50f + r * cos(a); val y = 50f + r * sin(a)
                            if (i == 0) star.moveTo(x * u, y * u) else star.lineTo(x * u, y * u)
                        }
                        star.close(); drawPath(star, c, style = st)
                    }
                    "nhl.standings" -> {
                        val cup = Path(); cup.moveTo(30f * u, 14f * u); cup.lineTo(70f * u, 14f * u); cup.lineTo(66f * u, 46f * u); cup.lineTo(34f * u, 46f * u); cup.close()
                        drawPath(cup, c, style = st)
                        line(50f, 46f, 50f, 70f); line(34f, 82f, 66f, 82f); line(50f, 70f, 50f, 82f)
                        arc(8f, 18f, 26f, 24f, 90f, 180f); arc(66f, 18f, 26f, 24f, 270f, 180f)
                    }
                    "xmb.settings" -> {
                        for ((i, ky) in listOf(60f, 32f, 52f).withIndex()) {
                            val x = 22f + 28f * i
                            line(x, 14f, x, 86f)
                            drawCircle(c, 9 * u, o(x, ky))
                        }
                    }
                    Settings.ACTION_SETTINGS -> {
                        box(8f, 16f, 84f, 56f, 7f); line(34f, 86f, 66f, 86f); line(50f, 72f, 50f, 86f)
                        drawCircle(c, 9 * u, o(50f, 44f), style = st)
                        for (i in 0 until 6) {
                            val a = (i * PI / 3).toFloat()
                            drawLine(c, o(50f + 15f * cos(a), 44f + 15f * sin(a)), o(50f + 21f * cos(a), 44f + 21f * sin(a)), sw, StrokeCap.Round)
                        }
                    }
                    "xmb.amazonhome" -> {
                        val roof = Path(); roof.moveTo(10f * u, 48f * u); roof.lineTo(50f * u, 14f * u); roof.lineTo(90f * u, 48f * u)
                        drawPath(roof, c, style = st)
                        box(22f, 44f, 56f, 42f, 4f); line(50f, 86f, 50f, 62f)
                    }
                    "nhl.leaders" -> {
                        drawCircle(c, 24 * u, o(50f, 62f), style = st)
                        line(36f, 8f, 45f, 40f); line(64f, 8f, 55f, 40f)
                        line(50f, 52f, 50f, 72f); line(43f, 58f, 50f, 52f)
                    }
                    else -> drawCircle(c, 26 * u, o(50f, 50f), style = st)
                }
            }
        }
    }
}

/* ---------- SETTINGS / TOP BARS ---------- */
val TXT_SH = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 6f)

object Cfg {
    var h24 by mutableStateOf(true)
    var fahrenheit by mutableStateOf(false)
    var feels by mutableStateOf(true)
    var city by mutableIntStateOf(0)
    var lang by mutableIntStateOf(0)
    var dateFmt by mutableIntStateOf(0)
    var seconds by mutableStateOf(false)
    var team by mutableIntStateOf(0)
    var volume by mutableIntStateOf(8)
    var idle by mutableIntStateOf(0)
    var goalAlerts by mutableStateOf(true)
    val idleOpts = listOf(0, 1, 2, 5, 10)
    val cities = listOf(Triple("Chicoutimi", 48.4283, -71.0537), Triple("Montreal", 45.5017, -73.5673), Triple("Quebec City", 46.8139, -71.2080),
        Triple("Toronto", 43.6532, -79.3832), Triple("Ottawa", 45.4215, -75.6972), Triple("Sherbrooke", 45.4042, -71.8929))
    val teams = listOf(
        Triple("ANA", "Anaheim Ducks", "Ducks"), Triple("BOS", "Boston Bruins", "Bruins"), Triple("BUF", "Buffalo Sabres", "Sabres"),
        Triple("CGY", "Calgary Flames", "Flames"), Triple("CAR", "Carolina Hurricanes", "Hurricanes"), Triple("CHI", "Chicago Blackhawks", "Blackhawks"),
        Triple("COL", "Colorado Avalanche", "Avalanche"), Triple("CBJ", "Columbus Blue Jackets", "Blue Jackets"), Triple("DAL", "Dallas Stars", "Stars"),
        Triple("DET", "Detroit Red Wings", "Red Wings"), Triple("EDM", "Edmonton Oilers", "Oilers"), Triple("FLA", "Florida Panthers", "Panthers"),
        Triple("LAK", "Los Angeles Kings", "Kings"), Triple("MIN", "Minnesota Wild", "Wild"), Triple("MTL", "Montréal Canadiens", "Canadiens"),
        Triple("NSH", "Nashville Predators", "Predators"), Triple("NJD", "New Jersey Devils", "Devils"), Triple("NYI", "New York Islanders", "Islanders"),
        Triple("NYR", "New York Rangers", "Rangers"), Triple("OTT", "Ottawa Senators", "Senators"), Triple("PHI", "Philadelphia Flyers", "Flyers"),
        Triple("PIT", "Pittsburgh Penguins", "Penguins"), Triple("SJS", "San Jose Sharks", "Sharks"), Triple("SEA", "Seattle Kraken", "Kraken"),
        Triple("STL", "St. Louis Blues", "Blues"), Triple("TBL", "Tampa Bay Lightning", "Lightning"), Triple("TOR", "Toronto Maple Leafs", "Maple Leafs"),
        Triple("UTA", "Utah Mammoth", "Mammoth"), Triple("VAN", "Vancouver Canucks", "Canucks"), Triple("VGK", "Vegas Golden Knights", "Golden Knights"),
        Triple("WSH", "Washington Capitals", "Capitals"), Triple("WPG", "Winnipeg Jets", "Jets"))
    val teamAbbr: String get() = teams[team].first
    val teamName: String get() = teams[team].second
    val teamShort: String get() = teams[team].third
    private var sp: android.content.SharedPreferences? = null
    fun load(c: Context) {
        val x = c.getSharedPreferences("xmb", Context.MODE_PRIVATE); sp = x
        h24 = x.getBoolean("h24", true); fahrenheit = x.getBoolean("f", false); feels = x.getBoolean("feels", true)
        city = x.getInt("city", 0).coerceIn(0, cities.size - 1)
        lang = x.getInt("lang", 0).coerceIn(0, 1); dateFmt = x.getInt("datefmt", 0).coerceIn(0, 1); seconds = x.getBoolean("seconds", false)
        team = x.getInt("team", teams.indexOfFirst { it.first == "TOR" }).coerceIn(0, teams.size - 1)
        volume = x.getInt("volume", 8).coerceIn(0, 10); idle = x.getInt("idle", 0).coerceIn(0, idleOpts.size - 1)
        goalAlerts = x.getBoolean("goals", true)
    }
    fun save() {
        sp?.edit()?.putBoolean("h24", h24)?.putBoolean("f", fahrenheit)?.putBoolean("feels", feels)?.putInt("city", city)
            ?.putInt("lang", lang)?.putInt("datefmt", dateFmt)?.putBoolean("seconds", seconds)?.putInt("team", team)
            ?.putInt("volume", volume)?.putInt("idle", idle)?.putBoolean("goals", goalAlerts)?.apply()
    }
}

object Idle { @Volatile var last = 0L; var active by mutableStateOf(false) }

val FR = mapOf(
    "Settings" to "Paramètres", "Music" to "Musique", "TV" to "Télé", "Streaming" to "Diffusion", "Games" to "Jeux", "NHL" to "LNH",
    "Network" to "Réseau", "All Apps" to "Toutes les applis", "Fire TV Settings" to "Paramètres Fire TV", "Amazon Home" to "Accueil Amazon", "XMB Settings" to "Paramètres XMB",
    "Live Game" to "Match en direct", "Today's Games" to "Matchs du jour", "Yesterday's Scores" to "Résultats d'hier", "Wild Card" to "Cartes sauvages",
    "League Standings" to "Classement général", "Leaders" to "Meneurs", "App Store" to "Boutique d'applis",
    "Today's games" to "Matchs du jour", "Yesterday's scores" to "Résultats d'hier", "League standings" to "Classement général",
    "Division standings" to "Classement par division", "Wild card race" to "Course aux cartes sauvages", "Scoring leaders" to "Meneurs pointeurs", "Live game" to "Match en direct",
    "AWAY" to "VISITEUR", "HOME" to "DOMICILE", "STATUS" to "ÉTAT", "WATCH" to "TÉLÉ", "TEAM" to "ÉQUIPE", "GP" to "PJ", "W" to "V", "L" to "D", "OTL" to "DP",
    "RECENT" to "RÉCENTS", "UPCOMING" to "À VENIR", "POINTS" to "POINTS", "GOALS" to "BUTS", "ASSISTS" to "PASSES", "WILD CARD" to "CARTE SAUVAGE",
    "IN THE HUNT" to "DANS LA COURSE", "SHOTS ON GOAL" to "TIRS AU BUT", "WATCH ON" to "À REGARDER SUR", "NEXT GAME" to "PROCHAIN MATCH",
    "No games" to "Aucun match", "No game in progress" to "Aucun match en cours", "Game detail unavailable" to "Détails indisponibles",
    "Can't reach NHL data" to "Données LNH inaccessibles", "Loading..." to "Chargement...", "Final/OT" to "Final/Prol.", "Final/SO" to "Final/TB",
    "Feels like" to "Ressenti", "EASTERN CONFERENCE" to "CONFÉRENCE DE L'EST", "WESTERN CONFERENCE" to "CONFÉRENCE DE L'OUEST",
    "ATLANTIC" to "ATLANTIQUE", "METROPOLITAN" to "MÉTROPOLITAINE", "CENTRAL" to "CENTRALE", "PACIFIC" to "PACIFIQUE",
    "UP / DOWN scroll    BACK close    auto-refreshing" to "HAUT / BAS défiler    RETOUR fermer    mise à jour auto",
    "UP / DOWN select    LEFT / RIGHT / OK change    BACK close" to "HAUT / BAS choisir    GAUCHE / DROITE / OK changer    RETOUR fermer",
    "XMB SETTINGS" to "PARAMÈTRES XMB", "Language" to "Langue", "Clock format" to "Format de l'heure", "Date format" to "Format de la date",
    "Show seconds" to "Afficher les secondes", "Temperature unit" to "Unité de température", "Show feels like" to "Afficher le ressenti",
    "Weather city" to "Ville météo", "Favorite NHL team" to "Équipe LNH favorite", "Menu sound volume" to "Volume des sons du menu", "Idle mode" to "Mode veille",
    "Language Settings" to "Paramètres de langue", "Date and Time Settings" to "Paramètres de date et heure", "Weather Settings" to "Paramètres météo", "NHL Settings" to "Paramètres LNH", "Sound Settings" to "Paramètres du son", "Power Settings" to "Paramètres d'alimentation",
    "Sets the menu language." to "Choisit la langue du menu.", "Sets the clock and the date format." to "Règle le format de l'heure et de la date.", "Sets the weather bar." to "Règle la barre météo.",
    "Sets your favorite team." to "Choisit ton équipe favorite.", "Sets the menu sounds." to "Règle les sons du menu.", "Sets the idle sleep mode." to "Règle la mise en veille.",
    "Opens the Fire TV system settings." to "Ouvre les paramètres système Fire TV.", "Opens the Amazon home screen." to "Ouvre l'écran d'accueil Amazon.",
    "UP / DOWN select    OK change    LEFT / BACK close" to "HAUT / BAS choisir    OK changer    GAUCHE / RETOUR fermer",
    "Notification Settings" to "Paramètres des notifications", "Goal alerts for your favorite team." to "Alertes de but pour ton équipe favorite.",
    "Goal notifications" to "Notifications de but", "Test goal notification" to "Tester la notification de but", "Show now" to "Afficher maintenant", "Show in 10 seconds" to "Afficher dans 10 secondes",
    "Shows a trophy-style pop-up when your team scores." to "Affiche une fenêtre style trophée quand ton équipe marque.", "Shows a sample pop-up to check how it looks." to "Affiche un exemple pour voir le rendu.",
    "24-hour" to "24 h", "12-hour" to "12 h", "On" to "Oui", "Off" to "Non"
)
fun tr(s: String): String {
    if (Cfg.lang == 0) return s
    FR[s]?.let { return it }
    if (s.endsWith(" DIVISION")) { val n = s.removeSuffix(" DIVISION"); return "DIVISION " + (FR[n] ?: n) }
    return s
}

data class Wx(val temp: Double, val feels: Double, val code: Int, val day: Boolean)
object WxStore { var last: Wx? = null; var lastCity = -1 }

suspend fun fetchWx(lat: Double, lon: Double): Wx? = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,apparent_temperature,weather_code,is_day&timezone=auto")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        val j = try { JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
        val cur = j.getJSONObject("current")
        Wx(cur.getDouble("temperature_2m"), cur.getDouble("apparent_temperature"), cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1)
    }.getOrNull()
}

@Composable
fun PsBar(modifier: Modifier, barH: Dp, fromRight: Boolean, content: @Composable RowScope.() -> Unit) {
    val line = Color.White.copy(alpha = 0.5f)
    Row(modifier.height(barH).drawBehind {
        val w = size.width; val h = size.height
        drawRect(Brush.horizontalGradient(if (fromRight) listOf(Color(0x34FFFFFF), Color(0x12FFFFFF)) else listOf(Color(0x12FFFFFF), Color(0x34FFFFFF))))
        drawLine(line, Offset(0f, 0f), Offset(w, 0f), 1.5f); drawLine(line, Offset(0f, h), Offset(w, h), 1.5f)
        val x = if (fromRight) 0f else w
        drawLine(line, Offset(x, 0f), Offset(x, h), 1.5f)
    }.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (fromRight) Arrangement.End else Arrangement.Start) { content() }
}

@Composable
fun ClockIcon(size: Dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 100f
        val st = Stroke(width = 8f * u, cap = StrokeCap.Round)
        drawCircle(Color.White.copy(alpha = 0.95f), 42 * u, Offset(50f * u, 50f * u), style = st)
        drawLine(Color.White.copy(alpha = 0.95f), Offset(50f * u, 50f * u), Offset(50f * u, 26f * u), 8f * u, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.95f), Offset(50f * u, 50f * u), Offset(66f * u, 58f * u), 8f * u, StrokeCap.Round)
    }
}

fun wxKind(code: Int, day: Boolean) = when (code) {
    0 -> if (day) "sun" else "moon"
    1, 2 -> if (day) "psun" else "pmoon"
    3 -> "cloud"
    45, 48 -> "fog"
    in 51..67 -> "rain"
    in 71..77 -> "snow"
    in 80..82 -> "rain"
    85, 86 -> "snow"
    in 95..99 -> "storm"
    else -> "cloud"
}

@Composable
fun WxIcon(code: Int, day: Boolean, size: Dp) {
    val kind = wxKind(code, day)
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 100f
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        val sunC = Color(0xFFFFD54F); val moonC = Color(0xFFFFF3C4)
        fun sun(cx: Float, cy: Float, r: Float) {
            drawCircle(sunC, r * u, o(cx, cy))
            for (i in 0 until 8) {
                val a = (i * PI / 4).toFloat()
                drawLine(sunC, o(cx + (r + 7) * cos(a), cy + (r + 7) * sin(a)), o(cx + (r + 16) * cos(a), cy + (r + 16) * sin(a)), 4.5f * u, StrokeCap.Round)
            }
        }
        fun moon(cx: Float, cy: Float, r: Float) {
            val a = Path().apply { addOval(Rect(o(cx - r, cy - r), Size(2 * r * u, 2 * r * u))) }
            val b = Path().apply { addOval(Rect(o(cx - r + r * 0.55f, cy - r - r * 0.25f), Size(2 * r * 0.88f * u, 2 * r * 0.88f * u))) }
            drawPath(Path.combine(PathOperation.Difference, a, b), moonC)
        }
        fun cloud(col: Color, dx: Float = 0f, dy: Float = 0f) {
            drawCircle(col, 16 * u, o(36 + dx, 60 + dy)); drawCircle(col, 22 * u, o(56 + dx, 48 + dy)); drawCircle(col, 14 * u, o(74 + dx, 60 + dy))
            drawRoundRect(col, o(18 + dx, 58 + dy), Size(70 * u, 20 * u), CornerRadius(10 * u))
        }
        val white = Color(0xFFF2F2F2)
        when (kind) {
            "sun" -> sun(50f, 50f, 20f)
            "moon" -> moon(50f, 50f, 32f)
            "psun" -> { sun(36f, 36f, 14f); cloud(white, 4f, 6f) }
            "pmoon" -> { moon(36f, 36f, 20f); cloud(white, 4f, 6f) }
            "cloud" -> cloud(white, 0f, -2f)
            "fog" -> { cloud(Color(0xFFD5D9DE), 0f, -12f); drawLine(Color(0xFFD5D9DE), o(20f, 78f), o(84f, 78f), 5f * u, StrokeCap.Round); drawLine(Color(0xFFD5D9DE), o(30f, 90f), o(74f, 90f), 5f * u, StrokeCap.Round) }
            "rain" -> {
                cloud(Color(0xFFDDE4EC), 0f, -12f)
                for (x in listOf(34f, 52f, 70f)) drawLine(Color(0xFF7EC8FF), o(x, 74f), o(x - 6f, 92f), 5f * u, StrokeCap.Round)
            }
            "snow" -> {
                cloud(Color(0xFFD7E3F0), 0f, -12f)
                for ((x, y) in listOf(34f to 80f, 52f to 90f, 70f to 80f)) drawCircle(Color.White, 5f * u, o(x, y))
            }
            "storm" -> {
                cloud(Color(0xFFB4BCC8), 0f, -14f)
                val bolt = Path()
                val pts = listOf(54f to 62f, 38f to 86f, 50f to 86f, 44f to 100f, 68f to 74f, 56f to 74f, 64f to 62f)
                pts.forEachIndexed { i, (x, y) -> if (i == 0) bolt.moveTo(x * u, y * u) else bolt.lineTo(x * u, y * u) }
                bolt.close(); drawPath(bolt, sunC)
            }
        }
    }
}

@Composable
fun TopBars() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    var wx by remember { mutableStateOf(WxStore.last) }
    val cityIdx = Cfg.city
    LaunchedEffect(cityIdx) {
        if (WxStore.lastCity != cityIdx) { WxStore.last = null; wx = null; WxStore.lastCity = cityIdx }
        val (_, lat, lon) = Cfg.cities[cityIdx]
        while (true) {
            val r = fetchWx(lat, lon)
            if (r != null) { wx = r; WxStore.last = r; delay(15 * 60 * 1000L) } else delay(20_000L)
        }
    }
    val fmt = remember(Cfg.h24, Cfg.dateFmt, Cfg.seconds) {
        val dPart = if (Cfg.dateFmt == 0) "MM/dd" else "dd/MM"
        val tPart = (if (Cfg.h24) "HH:mm" else "h:mm") + (if (Cfg.seconds) ":ss" else "") + (if (Cfg.h24) "" else " a")
        SimpleDateFormat("$dPart $tPart", Locale.US)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val barH = maxHeight * 0.056f
        val top = maxHeight * 0.07f
        val fs = (barH.value * 0.52f).sp
        PsBar(Modifier.align(Alignment.TopEnd).offset(y = top).width(maxWidth * 0.31f), barH, true) {
            BasicText(fmt.format(now), style = TextStyle(color = Color.White, fontSize = fs, letterSpacing = 0.6.sp, shadow = TXT_SH))
            Spacer(Modifier.width(10.dp))
            ClockIcon(barH * 0.58f)
        }
        PsBar(Modifier.align(Alignment.TopStart).offset(y = top).width(maxWidth * 0.34f), barH, false) {
            val w = wx
            val unit = if (Cfg.fahrenheit) "°F" else "°C"
            fun cv(c: Double) = Math.round(if (Cfg.fahrenheit) c * 9 / 5 + 32 else c)
            if (w == null) BasicText("--$unit", style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = fs, shadow = TXT_SH))
            else {
                WxIcon(w.code, w.day, barH * 0.84f)
                Spacer(Modifier.width(10.dp))
                BasicText("${cv(w.temp)}$unit", style = TextStyle(color = Color.White, fontSize = fs, letterSpacing = 0.6.sp, shadow = TXT_SH))
                if (Cfg.feels) {
                    Spacer(Modifier.width(16.dp))
                    BasicText("${tr("Feels like")} ${cv(w.feels)}$unit", style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = fs * 0.8f, shadow = TXT_SH))
                }
            }
        }
    }
}

class PsRow(val label: String, val desc: String, val value: String?)

val PS_GLOW = Shadow(Color.White.copy(alpha = 0.75f), Offset(0f, 0f), 18f)

@Composable
fun WrenchDot(dp: Dp, tint: Color = Color.White) {
    Canvas(Modifier.size(dp)) {
        val s = this.size.minDimension
        val c = Color(0xFF2C5C9C)
        drawCircle(tint)
        drawLine(c, Offset(s * 0.30f, s * 0.70f), Offset(s * 0.58f, s * 0.42f), strokeWidth = s * 0.13f, cap = StrokeCap.Round)
        drawCircle(c, s * 0.15f, Offset(s * 0.64f, s * 0.36f), style = Stroke(s * 0.085f))
    }
}

/** PS3 settings-style icon: small wrench badge at the top-left, glyph at the bottom-right. */
@Composable
fun SettingsItemIcon(idx: Int, dp: Dp) {
    Canvas(Modifier.size(dp)) {
        val u = this.size.minDimension / 100f
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        val white = Color.White.copy(alpha = 0.95f)
        val blue = Color(0xFF2C5C9C)
        when (idx) {
            0 -> {
                drawRoundRect(white, o(32f, 36f), Size(60f * u, 38f * u), CornerRadius(14f * u))
                val tail = Path(); tail.moveTo(48f * u, 70f * u); tail.lineTo(48f * u, 92f * u); tail.lineTo(66f * u, 70f * u); tail.close()
                drawPath(tail, white)
            }
            1 -> {
                drawCircle(white, 31f * u, o(63f, 63f))
                drawLine(blue, o(63f, 63f), o(63f, 43f), 7f * u, StrokeCap.Round)
                drawLine(blue, o(63f, 63f), o(77f, 71f), 7f * u, StrokeCap.Round)
            }
            2 -> {
                drawCircle(white, 15f * u, o(63f, 63f))
                for (k in 0 until 8) {
                    val a = (k * PI / 4).toFloat()
                    drawLine(white, o(63f + 22f * cos(a), 63f + 22f * sin(a)), o(63f + 31f * cos(a), 63f + 31f * sin(a)), 6f * u, StrokeCap.Round)
                }
            }
            3 -> {
                drawOval(white, o(32f, 54f), Size(62f * u, 28f * u))
                drawRect(white, o(32f, 48f), Size(62f * u, 20f * u))
                drawOval(Color(0xFFDDE6F2), o(32f, 34f), Size(62f * u, 28f * u))
            }
            4 -> {
                val sp = Path()
                sp.moveTo(32f * u, 54f * u); sp.lineTo(46f * u, 54f * u); sp.lineTo(62f * u, 40f * u)
                sp.lineTo(62f * u, 86f * u); sp.lineTo(46f * u, 72f * u); sp.lineTo(32f * u, 72f * u); sp.close()
                drawPath(sp, white)
                drawArc(white, -45f, 90f, false, o(54f, 49f), Size(28f * u, 28f * u), style = Stroke(6f * u, cap = StrokeCap.Round))
                drawArc(white, -45f, 90f, false, o(46f, 39f), Size(48f * u, 48f * u), style = Stroke(6f * u, cap = StrokeCap.Round))
            }
            5 -> {
                drawArc(white, -55f, 290f, false, o(38f, 40f), Size(48f * u, 48f * u), style = Stroke(8f * u, cap = StrokeCap.Round))
                drawLine(white, o(62f, 34f), o(62f, 62f), 8f * u, StrokeCap.Round)
            }
            else -> {
                val cup = Path()
                cup.moveTo(40f * u, 36f * u); cup.lineTo(88f * u, 36f * u); cup.lineTo(80f * u, 64f * u); cup.lineTo(48f * u, 64f * u); cup.close()
                drawPath(cup, white)
                drawArc(white, 90f, 180f, false, o(28f, 38f), Size(20f * u, 20f * u), style = Stroke(5f * u, cap = StrokeCap.Round))
                drawArc(white, -90f, 180f, false, o(80f, 38f), Size(20f * u, 20f * u), style = Stroke(5f * u, cap = StrokeCap.Round))
                drawRect(white, o(60f, 64f), Size(8f * u, 14f * u))
                drawRoundRect(white, o(46f, 78f), Size(36f * u, 10f * u), CornerRadius(3f * u))
            }
        }
        drawCircle(white, 16f * u, o(22f, 22f))
        drawLine(blue, o(14f, 30f), o(25f, 19f), 5f * u, StrokeCap.Round)
        drawCircle(blue, 5.5f * u, o(28f, 16f), style = Stroke(3.2f * u))
    }
}

@Composable
fun EntryIcon(e: Entry, dp: Dp) {
    val a = e.action.orEmpty()
    if (a.startsWith("xmb.set.")) SettingsItemIcon(a.removePrefix("xmb.set.").toIntOrNull() ?: 0, dp)
    else if (a == "xmb.amazonhome" || a == Settings.ACTION_SETTINGS) NhlEntryIcon(a, dp)
    else Box(Modifier.size(dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.2f)))
}

/** The previous column on the left (category + sibling items), shown dim like the PS3 sub-menu screen. */
@Composable
fun PsSiblings(entries: List<Entry>, selIdx: Int) {
    val dn = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val selY = h * 0.47f
        val tb = w * 0.075f
        Box(Modifier.offset { IntOffset((w * 0.114f - tb / 2f).roundToInt(), (h * 0.238f - tb / 2f).roundToInt()) }
            .graphicsLayer { alpha = 0.55f }) { CatIcon("Settings", with(dn) { tb.toDp() }) }
        entries.forEachIndexed { j, e ->
            val dy = j - selIdx
            val big = dy == 0
            val px = if (big) w * 0.095f else w * 0.032f
            val cx = if (big) w * 0.12f else w * 0.21f
            val y = when {
                dy == 0 -> selY
                dy > 0 -> selY + h * 0.155f + (dy - 1) * h * 0.075f
                else -> selY - h * 0.37f + (dy + 1) * h * 0.07f
            }
            val al = (if (big) 1f else 0.4f) * ((y - h * 0.10f) / (h * 0.05f)).coerceIn(0f, 1f) * ((h * 0.98f - y) / (h * 0.05f)).coerceIn(0f, 1f)
            if (al <= 0.01f) return@forEachIndexed
            Box(Modifier.offset { IntOffset((cx - px / 2f).roundToInt(), (y - px / 2f).roundToInt()) }.graphicsLayer { alpha = al }) {
                EntryIcon(e, with(dn) { px.toDp() })
            }
        }
        BasicText("◄", Modifier.offset { IntOffset((w * 0.2f).roundToInt(), (selY - h * 0.016f).roundToInt()) },
            style = TextStyle(color = Color.White, fontSize = with(dn) { (h * 0.026f).toSp() }, shadow = TXT_SH))
    }
}

/** PS3-style settings rows: selected row large with description, others faded, values on the right. */
@Composable
fun PsList(rows: List<PsRow>, sel: Int) {
    val a by animateFloatAsState(sel.toFloat(), tween(220), label = "psl")
    val dn = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val selY = h * 0.47f; val gap = h * 0.175f; val step = h * 0.080f
        val dotX = w * 0.282f; val labelX = w * 0.32f
        rows.forEachIndexed { j, r ->
            val dy = j - a
            if (abs(dy) > 7f) return@forEachIndexed
            val y = when {
                dy >= 1f -> selY + gap + (dy - 1f) * step
                dy >= 0f -> selY + dy * gap
                dy > -1f -> selY + dy * gap
                else -> selY - gap + (dy + 1f) * step
            }
            val near = 1f - min(1f, abs(dy))
            val edge = (min(y - h * 0.12f, h * 0.97f - y) / (h * 0.08f)).coerceIn(0f, 1f)
            val al = (0.38f + 0.62f * near) * edge
            if (al <= 0.01f) return@forEachIndexed
            val dotPx = w * (0.013f + 0.014f * near)
            val titlePx = h * (0.032f + 0.005f * near)
            Box(Modifier.offset { IntOffset((dotX - dotPx / 2f).roundToInt(), (y - dotPx / 2f).roundToInt()) }
                .graphicsLayer { alpha = al }) { WrenchDot(with(dn) { dotPx.toDp() }) }
            BasicText(r.label, Modifier.offset { IntOffset(labelX.roundToInt(), (y - titlePx * 0.65f).roundToInt()) }.graphicsLayer { alpha = al },
                maxLines = 1, style = TextStyle(color = Color.White, fontSize = with(dn) { titlePx.toSp() }, fontWeight = FontWeight.Normal, shadow = if (near > 0.5f) PS_GLOW else TXT_SH))
            BasicText(r.desc, Modifier.offset { IntOffset(labelX.roundToInt(), (y + titlePx * 0.55f).roundToInt()) }.graphicsLayer { alpha = near * edge },
                maxLines = 1, style = TextStyle(color = Color.White, fontSize = with(dn) { (h * 0.019f).toSp() }, shadow = TXT_SH))
            if (r.value != null) {
                BasicText(r.value, Modifier.offset { IntOffset((w * 0.77f).roundToInt(), (y - h * 0.016f).roundToInt()) }.graphicsLayer { alpha = al },
                    maxLines = 1, style = TextStyle(color = Color.White, fontSize = with(dn) { (h * 0.023f).toSp() }, shadow = TXT_SH))
            }
        }
    }
}

/** Option pop-up list, like the PS3 when you press OK on a setting. */
@Composable
fun PsPopup(options: List<String>, sel: Int) {
    val dn = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val rowH = h * 0.064f
        val shown = min(7, options.size)
        val first = (sel - 3).coerceIn(0, options.size - shown)
        val boxH = rowH * shown + h * 0.03f
        val shape = RoundedCornerShape(6.dp)
        Column(Modifier.offset { IntOffset((w * 0.60f).roundToInt(), (h * 0.47f - boxH / 2f).roundToInt()) }
            .width(with(dn) { (w * 0.30f).toDp() }).height(with(dn) { boxH.toDp() })
            .clip(shape).background(Brush.verticalGradient(listOf(Color(0xF0566478), Color(0xF02C3848))))
            .border(1.dp, Color.White.copy(alpha = 0.35f), shape).padding(vertical = with(dn) { (h * 0.015f).toDp() })) {
            for (k in first until first + shown) {
                val on = k == sel
                Box(Modifier.fillMaxWidth().height(with(dn) { rowH.toDp() }).background(if (on) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                    .padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    BasicText(tr(options[k]), maxLines = 1, style = TextStyle(color = Color.White.copy(alpha = if (on) 1f else 0.65f),
                        fontSize = with(dn) { (h * 0.027f).toSp() }, shadow = if (on) PS_GLOW else TXT_SH))
                }
            }
        }
    }
}

@Composable
fun SettingsPanel(groupIdx: Int, siblings: List<Entry>, sfx: Sfx, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    val ctx = LocalContext.current
    var sel by remember { mutableIntStateOf(0) }
    var pop by remember { mutableStateOf(false) }
    var popSel by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { fr.requestFocus() }
    val labels = listOf("Language", "Clock format", "Date format", "Show seconds", "Temperature unit", "Show feels like", "Weather city", "Favorite NHL team", "Menu sound volume", "Idle mode", "Goal notifications", "Test goal notification")
    val descs = listOf(
        "Sets the language used in the menu.", "Sets 24-hour or 12-hour time.", "Sets the order of the month and the day.",
        "Shows the seconds on the clock.", "Sets the temperature unit.", "Shows the feels-like temperature on the weather bar.",
        "Sets the city used for the weather.", "Sets the team shown first in the NHL category.", "Sets the volume of the menu sounds.",
        "Sets how long before the menu goes to sleep.", "Shows a trophy-style pop-up when your team scores.", "Shows a sample pop-up to check how it looks.")
    val groupItems = listOf(listOf(0), listOf(1, 2, 3), listOf(4, 5, 6), listOf(7), listOf(8), listOf(9), listOf(10, 11))
    val items: List<Int> = groupItems[groupIdx.coerceIn(0, groupItems.size - 1)]
    fun opts(i: Int): List<String> = when (i) {
        0 -> listOf("English", "Français")
        1 -> listOf("24-hour", "12-hour")
        2 -> listOf("MM/DD", "DD/MM")
        3, 5 -> listOf("On", "Off")
        4 -> listOf("°C", "°F")
        6 -> Cfg.cities.map { it.first }
        7 -> Cfg.teams.map { it.second }
        8 -> (0..10).map { if (it == 0) "Off" else "$it / 10" }
        9 -> Cfg.idleOpts.map { if (it == 0) "Off" else "$it min" }
        10 -> listOf("On", "Off")
        else -> listOf("Show now", "Show in 10 seconds")
    }
    fun cur(i: Int): Int = when (i) {
        0 -> Cfg.lang
        1 -> if (Cfg.h24) 0 else 1
        2 -> Cfg.dateFmt
        3 -> if (Cfg.seconds) 0 else 1
        4 -> if (Cfg.fahrenheit) 1 else 0
        5 -> if (Cfg.feels) 0 else 1
        6 -> Cfg.city
        7 -> Cfg.team
        8 -> Cfg.volume
        9 -> Cfg.idle
        10 -> if (Cfg.goalAlerts) 0 else 1
        else -> 0
    }
    fun applyOpt(i: Int, k: Int) {
        when (i) {
            0 -> Cfg.lang = k
            1 -> Cfg.h24 = (k == 0)
            2 -> Cfg.dateFmt = k
            3 -> Cfg.seconds = (k == 0)
            4 -> Cfg.fahrenheit = (k == 1)
            5 -> Cfg.feels = (k == 0)
            6 -> Cfg.city = k
            7 -> { Cfg.team = k; Nhl.cache.clear() }
            8 -> { Cfg.volume = k; sfx.moveItem() }
            9 -> Cfg.idle = k
            10 -> Cfg.goalAlerts = (k == 0)
            else -> GoalToast.test(ctx, if (k == 0) 500L else 10_000L, sfx)
        }
        Cfg.save()
    }
    fun goBack() { if (pop) { pop = false; sfx.moveCat() } else { sfx.moveCat(); onClose() } }
    BackHandler { goBack() }
    val rows = items.map { PsRow(tr(labels[it]), tr(descs[it]), tr(opts(it)[cur(it).coerceIn(0, opts(it).size - 1)])) }
    Box(Modifier.fillMaxSize().focusRequester(fr).focusable().onKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (ev.key) {
            Key.Back, Key.Escape, Key.DirectionLeft -> { goBack(); true }
            Key.DirectionDown -> {
                if (pop) { if (popSel < opts(items[sel]).size - 1) { popSel++; sfx.moveItem() } }
                else if (sel < items.size - 1) { sel++; sfx.moveItem() }
                true
            }
            Key.DirectionUp -> {
                if (pop) { if (popSel > 0) { popSel--; sfx.moveItem() } }
                else if (sel > 0) { sel--; sfx.moveItem() }
                true
            }
            Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (pop) { applyOpt(items[sel], popSel); pop = false; sfx.select() }
                else { popSel = cur(items[sel]).coerceIn(0, opts(items[sel]).size - 1); pop = true; sfx.select() }
                true
            }
            else -> false
        }
    }) {
        PsSiblings(siblings, groupIdx)
        PsList(rows, sel)
        if (pop) PsPopup(opts(items[sel]), popSel)
        BasicText(tr("UP / DOWN select    OK change    LEFT / BACK close"), Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp, letterSpacing = 1.sp))
    }
}

@Composable
fun BgVideo(file: File, fallback: @Composable () -> Unit) {
    var err by remember { mutableStateOf(false) }
    if (err) fallback() else AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
        TextureView(ctx).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                var mp: MediaPlayer? = null
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    mp = MediaPlayer().apply {
                        setDataSource(file.path); setSurface(Surface(st)); isLooping = true; setVolume(0f, 0f)
                        setOnPreparedListener { it.start() }
                        setOnErrorListener { _, _, _ -> err = true; true }
                        prepareAsync()
                    }
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { runCatching { mp?.release() }; mp = null; return true }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    })
}
