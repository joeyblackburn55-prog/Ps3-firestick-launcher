package com.xmb.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GoalInfo(
    val teamAbbr: String, val teamShort: String, val scorer: String, val goalNo: Int, val assists: List<String>,
    val period: String, val time: String, val myScore: Int, val oppAbbr: String, val oppScore: Int
)

/** Polls the NHL API while your favorite team is playing and calls onGoal for every new goal that team scores. */
class GoalWatcher(private val onGoal: (GoalInfo) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen = HashMap<Int, MutableSet<String>>()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                var wait = 30_000L
                if (Cfg.goalAlerts) wait = runCatching { tick() }.getOrDefault(30_000L)
                delay(wait)
            }
        }
    }
    fun stop() { job?.cancel() }

    private fun get(path: String): JSONObject {
        val c = URL("https://api-web.nhle.com/v1/$path").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "XMB-Launcher")
        return try { JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
    }
    private fun dd(o: Any?): String = when (o) { is JSONObject -> o.optString("default"); is String -> o; else -> "" }
    private fun fmt(first: String, last: String) = if (first.isNotEmpty() && last.isNotEmpty()) "${first.first()}. $last" else (last.ifEmpty { first })

    private fun tick(): Long {
        val abbr = Cfg.teamAbbr
        val games = get("score/now").optJSONArray("games") ?: return 30_000L
        var id = -1
        for (i in 0 until games.length()) {
            val g = games.getJSONObject(i)
            val st = g.optString("gameState")
            if (st != "LIVE" && st != "CRIT") continue
            val a = dd(g.optJSONObject("awayTeam")?.opt("abbrev")); val h = dd(g.optJSONObject("homeTeam")?.opt("abbrev"))
            if (a == abbr || h == abbr) { id = g.optInt("id"); break }
        }
        if (id < 0) return 30_000L
        val land = get("gamecenter/$id/landing")
        val aAb = dd(land.optJSONObject("awayTeam")?.opt("abbrev")); val hAb = dd(land.optJSONObject("homeTeam")?.opt("abbrev"))
        val periods = land.optJSONObject("summary")?.optJSONArray("scoring") ?: return 15_000L
        val base = !seen.containsKey(id)
        val set = seen.getOrPut(id) { mutableSetOf() }
        for (p in 0 until periods.length()) {
            val pd = periods.getJSONObject(p)
            val desc = pd.optJSONObject("periodDescriptor")
            val num = desc?.optInt("number") ?: 0
            val type = desc?.optString("periodType") ?: "REG"
            val per = if (type == "OT" || type == "SO") type else "P$num"
            val goals = pd.optJSONArray("goals") ?: continue
            for (k in 0 until goals.length()) {
                val gl = goals.getJSONObject(k)
                val tAb = dd(gl.opt("teamAbbrev"))
                val time = gl.optString("timeInPeriod")
                val last = dd(gl.opt("lastName")).ifEmpty { dd(gl.opt("name")) }
                val key = "$num-$time-$last"
                if (!set.add(key)) continue
                if (base || tAb != abbr) continue
                val hs = gl.optInt("homeScore"); val aS = gl.optInt("awayScore")
                val mine = if (tAb == hAb) hs else aS
                val opp = if (tAb == hAb) aS else hs
                val oppAb = if (tAb == hAb) aAb else hAb
                val ass = gl.optJSONArray("assists")
                val names = if (ass == null) emptyList() else (0 until ass.length()).map {
                    val a = ass.getJSONObject(it)
                    fmt(dd(a.opt("firstName")), dd(a.opt("lastName")).ifEmpty { dd(a.opt("name")) })
                }.filter { it.isNotEmpty() }
                onGoal(GoalInfo(abbr, Cfg.teams.firstOrNull { it.first == abbr }?.third ?: abbr, fmt(dd(gl.opt("firstName")), last),
                    gl.optInt("goalsToDate"), names, per, time, mine, oppAb, opp))
            }
        }
        return 15_000L
    }
}

/** The PS3 trophy-style pop-up (top-right, lavender rounded box, "pops" in and out). Built from plain Views so it can live in a system overlay. */
object GoalToast {
    private val main = Handler(Looper.getMainLooper())

    fun build(ctx: Context, g: GoalInfo): View {
        val dm = ctx.resources.displayMetrics
        val h = dm.heightPixels.toFloat()
        val fr = Cfg.lang == 1
        fun tv(px: Float, bold: Boolean): TextView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px); setTextColor(Color.WHITE); setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(h * 0.002f, 0f, h * 0.0015f, Color.argb(120, 0, 0, 0))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        val pad = (h * 0.012f).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad * 2, pad)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(245, 138, 149, 218), Color.argb(245, 104, 117, 194))).apply {
                cornerRadius = h * 0.009f
                setStroke(maxOf(1, (h * 0.0015f).toInt()), Color.argb(150, 200, 208, 244))
            }
        }
        val iconPx = (h * 0.085f).toInt()
        val tile = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = h * 0.008f; setColor(Color.argb(70, 10, 16, 40)) }
        }
        val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; val p = (iconPx * 0.08f).toInt(); setPadding(p, p, p, p) }
        tile.addView(iv, FrameLayout.LayoutParams(iconPx, iconPx))
        root.addView(tile, LinearLayout.LayoutParams(iconPx, iconPx))
        runCatching {
            val loader = ImageLoader.Builder(ctx).components { add(SvgDecoder.Factory()) }.build()
            loader.enqueue(ImageRequest.Builder(ctx).data("https://assets.nhle.com/logos/nhl/svg/${g.teamAbbr}_$LOGO_VARIANT.svg").target(iv).build())
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(h * 0.021f, false).apply {
            text = (if (fr) "But – " else "Goal – ") + g.teamShort + "   " + g.teamAbbr + " " + g.myScore + "–" + g.oppScore + " " + g.oppAbbr
        })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(tv(h * 0.025f, true).apply { text = g.scorer + if (g.goalNo > 0) " (${g.goalNo})" else "" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(tv(h * 0.019f, false).apply { setEllipsize(null); text = "  " + g.period + " " + g.time })
        col.addView(row)
        col.addView(tv(h * 0.019f, false).apply {
            text = if (g.assists.isEmpty()) (if (fr) "Sans aide" else "Unassisted") else (if (fr) "Passes : " else "Assists: ") + g.assists.joinToString(", ")
        })
        root.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = (h * 0.014f).toInt() })
        return root
    }

    private fun animate(v: View, remove: () -> Unit) {
        v.alpha = 0f; v.scaleX = 0.85f; v.scaleY = 0.85f
        v.addOnLayoutChangeListener { view, l, _, r, _, _, _, _, _ -> view.pivotX = (r - l).toFloat(); view.pivotY = 0f }
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        main.postDelayed({
            v.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(220).withEndAction { remove() }.start()
        }, 5500)
    }

    /** Shows the toast on top of everything (needs the accessibility service to be on). */
    fun showOverlay(ctx: Context, g: GoalInfo, sfx: Sfx?) {
        main.post {
            runCatching {
                val dm = ctx.resources.displayMetrics
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val v = build(ctx, g)
                val lp = WindowManager.LayoutParams((dm.widthPixels * 0.31f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT)
                lp.gravity = Gravity.TOP or Gravity.END
                lp.x = (dm.widthPixels * 0.036f).toInt(); lp.y = (dm.heightPixels * 0.077f).toInt()
                wm.addView(v, lp)
                animate(v) { runCatching { wm.removeView(v) } }
                sfx?.trophy()
            }
        }
    }

    /** Fallback when the service is not running: shows the toast inside the XMB window. */
    fun showInView(root: ViewGroup, g: GoalInfo, sfx: Sfx?) {
        main.post {
            runCatching {
                val dm = root.resources.displayMetrics
                val v = build(root.context, g)
                val lp = FrameLayout.LayoutParams((dm.widthPixels * 0.31f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                lp.topMargin = (dm.heightPixels * 0.077f).toInt(); lp.rightMargin = (dm.widthPixels * 0.036f).toInt()
                root.addView(v, lp)
                animate(v) { runCatching { root.removeView(v) } }
                sfx?.trophy()
            }
        }
    }

    fun test(ctx: Context, delayMs: Long, sfx: Sfx?) {
        val ab = Cfg.teamAbbr
        val g = GoalInfo(ab, Cfg.teamShort, "A. Matthews", 12, listOf("W. Nylander", "M. Marner"), "P2", "12:34", 2, if (ab == "MTL") "TOR" else "MTL", 1)
        main.postDelayed({
            val svc = HomeKeyService.instance
            if (svc != null) showOverlay(svc, g, sfx)
            else (ctx as? android.app.Activity)?.let { showInView(it.window.decorView as ViewGroup, g, sfx) }
        }, delayMs)
    }
}
