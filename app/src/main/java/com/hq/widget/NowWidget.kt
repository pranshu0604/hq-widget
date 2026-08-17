package com.hq.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

object Config {
    // your live, gated HQ; the widget authenticates with the API token (no login on the widget)
    const val BASE_URL = "https://hq-three-mauve.vercel.app"
    // token is injected from local.properties (git-ignored) via BuildConfig — never in source
    val TOKEN = BuildConfig.HQ_TOKEN
    const val REFRESH = "com.hq.widget.REFRESH"
}

class NowWidget : AppWidgetProvider() {

    // handle both the system's periodic update and our own tap-to-refresh, on a
    // background thread (goAsync keeps the process alive for the network call).
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == AppWidgetManager.ACTION_APPWIDGET_UPDATE || action == Config.REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids =
                intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                    ?: mgr.getAppWidgetIds(ComponentName(context, NowWidget::class.java))
            val pending = goAsync()
            Thread {
                try {
                    val data = fetch()
                    for (id in ids) {
                        val v = RemoteViews(context.packageName, R.layout.widget_now)
                        render(v, data)
                        wireClicks(context, v)
                        mgr.updateAppWidget(id, v)
                    }
                } finally {
                    pending.finish()
                }
            }.start()
        } else {
            super.onReceive(context, intent)
        }
    }

    private fun wireClicks(context: Context, v: RemoteViews) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(Intent.ACTION_VIEW, Uri.parse(Config.BASE_URL)), PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.now_label, open)
        v.setOnClickPendingIntent(R.id.eyebrow, open)
        v.setOnClickPendingIntent(R.id.next_line, open)

        val refreshIntent = Intent(context, NowWidget::class.java).setAction(Config.REFRESH)
        val refresh = PendingIntent.getBroadcast(
            context, 1, refreshIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        v.setOnClickPendingIntent(R.id.refresh, refresh)

        val captureIntent = Intent(context, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val capture = PendingIntent.getActivity(context, 2, captureIntent, PendingIntent.FLAG_IMMUTABLE)
        v.setOnClickPendingIntent(R.id.capture, capture)
    }

    // ---------- data ----------
    private class Data {
        var error = false
        var nowLabel: String? = null
        var nowRemainingMs = -1L
        var nowKind = "FOCUS"
        var nowPaused = false
        var nowDone = false
        var oneLabel: String? = null
        var oneKind = "NONE"
        var counts = ""
        var nextLabel: String? = null
        var nextAt: String? = null
    }

    private fun fetch(): Data {
        val d = Data()
        // Each call is independent: a cold Vercel function (Turso remote DB) can take
        // several seconds, so one slow/failed endpoint must not blank the whole widget.
        // We only show the "can't reach" state when EVERY call failed.
        var reached = 0

        getJson("/api/now")?.let { reached++
            it.optJSONObject("now")?.let { n ->
                if (!n.isNull("label")) {
                    d.nowLabel = n.optString("label")
                    d.nowRemainingMs = n.optLong("remainingMs", -1)
                    d.nowKind = n.optString("kind", "FOCUS")
                    d.nowPaused = n.optBoolean("paused")
                    d.nowDone = n.optBoolean("done")
                }
            }
        }
        getJson("/api/overload")?.let { ov -> reached++
            ov.optJSONObject("one")?.let { d.oneLabel = it.optString("label"); d.oneKind = it.optString("kind", "NONE") }
            ov.optJSONObject("counts")?.let {
                d.counts = "${it.optInt("todos")} todos · ${it.optInt("parked")} parked · ${it.optInt("worries")} worries"
            }
        }
        getJson("/api/schedule")?.let { s -> reached++
            s.optJSONArray("reminders")?.let { rems ->
                val now = System.currentTimeMillis()
                for (i in 0 until rems.length()) {
                    val r = rems.getJSONObject(i)
                    if (r.optString("status") == "PENDING" && parseIso(r.optString("at")) >= now) {
                        d.nextLabel = r.optString("label"); d.nextAt = r.optString("at"); break
                    }
                }
            }
        }

        d.error = reached == 0
        return d
    }

    private fun getJson(path: String): JSONObject? {
        return try {
            val conn = URL(Config.BASE_URL + path).openConnection() as HttpURLConnection
            try {
                conn.setRequestProperty("x-hq-token", Config.TOKEN)
                // generous timeouts — serverless cold start + remote DB on mobile data
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                if (conn.responseCode in 200..299) JSONObject(conn.inputStream.bufferedReader().use { it.readText() }) else null
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- render ----------
    private fun render(v: RemoteViews, d: Data) {
        if (d.error) {
            v.setTextViewText(R.id.now_label, "Can't reach HQ")
            v.setTextViewText(R.id.now_sub, "tap ↻ to retry")
            v.setTextViewText(R.id.next_line, "")
            v.setTextViewText(R.id.updated, "updated " + stamp())
            return
        }

        when {
            d.nowLabel != null -> {
                v.setTextViewText(R.id.now_label, d.nowLabel)
                v.setTextViewText(
                    R.id.now_sub,
                    when {
                        d.nowDone -> "done — nice"
                        d.nowPaused -> "paused"
                        d.nowKind == "CULTURE" -> "culture — enjoy it"
                        d.nowRemainingMs > 0 -> "~${d.nowRemainingMs / 60000} min left"
                        else -> "in progress"
                    }
                )
            }
            d.oneLabel != null && d.oneKind != "NONE" -> {
                v.setTextViewText(R.id.now_label, d.oneLabel)
                v.setTextViewText(
                    R.id.now_sub,
                    when (d.oneKind) {
                        "COMMITMENT" -> "someone's relying on you"
                        "TODO" -> "your most pressing todo"
                        else -> "next up"
                    }
                )
            }
            else -> {
                v.setTextViewText(R.id.now_label, "Nothing running")
                v.setTextViewText(R.id.now_sub, "tap to open HQ")
            }
        }

        if (d.nextLabel != null) {
            v.setTextViewText(R.id.next_line, "NEXT · ${d.nextLabel} · ${fmtTime(d.nextAt)}")
        } else {
            v.setTextViewText(R.id.next_line, d.counts)
        }
        v.setTextViewText(R.id.updated, "updated " + stamp())
    }

    // ---------- time helpers ----------
    private fun parseIso(iso: String): Long = try { Instant.parse(iso).toEpochMilli() } catch (e: Exception) { 0L }

    private fun fmtTime(iso: String?): String {
        if (iso == null) return ""
        return try {
            val t = Instant.parse(iso).atZone(ZoneId.systemDefault())
            String.format("%d:%02d", (t.hour + 11) % 12 + 1, t.minute)
        } catch (e: Exception) {
            ""
        }
    }

    private fun stamp(): String {
        val t = LocalTime.now()
        return String.format("%d:%02d", (t.hour + 11) % 12 + 1, t.minute)
    }
}
