package com.hq.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Tap the widget's ＋ → this pops up, you dump a thought, it lands in the HQ inbox. */
class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (18 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Capture to HQ"
            setTextColor(0xFFDC9A58.toInt())
            textSize = 12f
            letterSpacing = 0.12f
        })

        val input = EditText(this).apply {
            hint = "dump a thought — task, worry, idea…"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            textSize = 17f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10 * d).toInt()
            }
        }
        root.addView(input)
        setContentView(root)

        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.CENTER)
        input.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        input.setOnEditorActionListener { _, _, _ ->
            submit(input.text.toString())
            true
        }
    }

    private fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) {
            finish(); return
        }
        Thread {
            var ok = false
            try {
                val conn = URL(Config.BASE_URL + "/api/capture").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("x-hq-token", Config.TOKEN)
                conn.setRequestProperty("content-type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.outputStream.use { it.write(JSONObject().put("text", t).toString().toByteArray()) }
                ok = conn.responseCode in 200..299
                conn.disconnect()
            } catch (_: Exception) {
            }
            runOnUiThread {
                Toast.makeText(this, if (ok) "Captured ✓" else "Failed — try again", Toast.LENGTH_SHORT).show()
                if (ok) sendBroadcast(Intent(this, NowWidget::class.java).setAction(Config.REFRESH))
                finish()
            }
        }.start()
    }
}
