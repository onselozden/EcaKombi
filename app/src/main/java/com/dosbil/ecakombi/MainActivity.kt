package com.dosbil.ecakombi

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity() {

    private val api = CosaApi()
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("kombi", Context.MODE_PRIVATE) }

    private var endpoints = listOf<CosaApi.Endpoint>()
    private var current: CosaApi.Endpoint? = null
    private var status: CosaApi.Status? = null
    private var pendingTarget: Double? = null

    // Renkler
    private val bg = Color.parseColor("#0F1115")
    private val card = Color.parseColor("#1A1D24")
    private val accent = Color.parseColor("#FF6A2B")
    private val txt = Color.WHITE
    private val dim = Color.parseColor("#8A8F98")

    // Ana ekran görünümleri
    private lateinit var tvName: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvTarget: TextView
    private lateinit var tvFlame: TextView
    private lateinit var tvMsg: TextView
    private val optionButtons = mutableMapOf<String, Button>()
    private val modeButtons = mutableMapOf<String, Button>()

    private val refreshLoop = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 30_000) }
    }
    private val sendTarget = Runnable { applyTarget() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        api.token = prefs.getString("token", null)
        if (api.token == null) showLogin() else loadEndpoints()
    }

    override fun onResume() { super.onResume(); if (current != null) ui.post(refreshLoop) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refreshLoop) }

    // ---------- yardımcılar ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bgShape(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private fun text(s: String, size: Float, color: Int = txt, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 15f; setTextColor(txt)
        background = bgShape(card, 12)
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT,
                   h: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
                   weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private fun bgTask(work: () -> Unit, onError: (String) -> Unit = { showMsg(it) }) {
        Thread {
            try { work() } catch (e: CosaApi.ApiException) {
                if (e.message?.contains("Oturum") == true) ui.post { logout() }
                else ui.post { onError(e.message ?: "Hata") }
            } catch (e: Exception) {
                ui.post { onError("Bağlantı hatası: ${e.javaClass.simpleName} ${e.message ?: ""}") }
            }
        }.start()
    }

    private fun showMsg(m: String) {
        if (::tvMsg.isInitialized) tvMsg.text = m
        else Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    }

    private fun fmt(d: Double?) = d?.let { String.format(Locale("tr"), "%.1f°", it) } ?: "--"

    // ---------- giriş ----------
    private fun showLogin() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg); setPadding(dp(28), dp(80), dp(28), dp(28))
        }
        root.addView(text("Kombi", 34f, bold = true))
        root.addView(text("E.C.A. Smart hesabınla giriş yap", 15f, dim), lp(top = 6))

        fun field(hint: String, pass: Boolean) = EditText(this).apply {
            this.hint = hint; setHintTextColor(dim); setTextColor(txt)
            background = bgShape(card, 12); setPadding(dp(16), dp(14), dp(16), dp(14))
            inputType = if (pass) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val email = field("E-posta", false).apply { setText(prefs.getString("email", "")) }
        val pass = field("Şifre", true)
        root.addView(email, lp(top = 32))
        root.addView(pass, lp(top = 12))

        val err = text("", 14f, Color.parseColor("#FF5C5C"))
        val btn = button("Giriş") {}.apply { background = bgShape(accent, 12) }
        btn.setOnClickListener {
            btn.isEnabled = false; err.text = "Giriş yapılıyor…"
            val e = email.text.toString().trim(); val p = pass.text.toString()
            bgTask({
                val t = api.login(e, p)
                prefs.edit().putString("token", t).putString("email", e).apply()
                ui.post { loadEndpoints() }
            }, { m -> err.text = m; btn.isEnabled = true })
        }
        root.addView(btn, lp(h = dp(52), top = 24))
        root.addView(err, lp(top = 16))
        setContentView(root)
    }

    private fun logout() {
        prefs.edit().remove("token").apply()
        api.token = null; current = null
        ui.removeCallbacks(refreshLoop)
        showLogin()
    }

    // ---------- cihazlar ----------
    private fun loadEndpoints() {
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(bg)
            addView(ProgressBar(this@MainActivity),
                FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        })
        bgTask({
            val list = api.getEndpoints()
            ui.post {
                endpoints = list
                if (list.isEmpty()) { Toast.makeText(this, "Hesapta cihaz yok", Toast.LENGTH_LONG).show(); showLogin(); return@post }
                val saved = prefs.getString("endpoint", null)
                current = list.firstOrNull { it.id == saved } ?: list[0]
                showMain()
                ui.post(refreshLoop)
            }
        }, { m -> Toast.makeText(this, m, Toast.LENGTH_LONG).show(); showLogin() })
    }

    // ---------- ana ekran ----------
    private fun showMain() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        scroll.addView(root)

        // Üst satır
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tvName = text(current?.name ?: "", 22f, bold = true).apply {
            setOnLongClickListener { showRaw(); true }   // uzun bas: ham veri
        }
        top.addView(tvName, lp(0, weight = 1f))
        if (endpoints.size > 1) top.addView(button("Cihaz") { pickDevice() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        top.addView(button("⟳") { refresh() }, lp(dp(52)).apply { leftMargin = dp(8) })
        top.addView(button("Çıkış") { logout() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        root.addView(top)

        // Sıcaklık kartı
        val big = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = bgShape(card, 24); setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        big.addView(text("Oda sıcaklığı", 14f, dim))
        tvTemp = text("--", 64f, bold = true)
        big.addView(tvTemp)
        tvInfo = text("", 14f, dim)
        big.addView(tvInfo)
        tvFlame = text("", 16f, bold = true).apply { setPadding(0, dp(10), 0, 0) }
        big.addView(tvFlame)
        root.addView(big, lp(top = 20))

        // Hedef sıcaklık
        val tgt = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = bgShape(card, 24)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val minus = button("−") { changeTarget(-0.5) }.apply { textSize = 28f }
        val plus = button("+") { changeTarget(+0.5) }.apply { textSize = 28f; background = bgShape(accent, 12) }
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        mid.addView(text("Hedef", 13f, dim))
        tvTarget = text("--", 36f, accent, true)
        mid.addView(tvTarget)
        tgt.addView(minus, lp(dp(64), dp(64)))
        tgt.addView(mid, lp(0, weight = 1f))
        tgt.addView(plus, lp(dp(64), dp(64)))
        root.addView(tgt, lp(top = 14))

        // Seçenekler
        root.addView(text("Durum", 14f, dim), lp(top = 22))
        val opts = listOf("home" to "Evde", "away" to "Dışarı", "sleep" to "Uyku", "custom" to "Özel", "frozen" to "Kapalı")
        val optRow = LinearLayout(this)
        opts.forEachIndexed { i, (k, label) ->
            val b = button(label) { setOption(k) }.apply { textSize = 13f }
            optionButtons[k] = b
            optRow.addView(b, lp(0, dp(48), 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        root.addView(optRow, lp(top = 8))

        // Mod
        root.addView(text("Mod", 14f, dim), lp(top = 18))
        val modes = listOf("manual" to "Manuel", "schedule" to "Program", "auto" to "Otomatik")
        val modeRow = LinearLayout(this)
        modes.forEachIndexed { i, (k, label) ->
            val b = button(label) { setMode(k) }
            modeButtons[k] = b
            modeRow.addView(b, lp(0, dp(48), 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        root.addView(modeRow, lp(top = 8))

        tvMsg = text("", 13f, dim).apply { gravity = Gravity.CENTER }
        root.addView(tvMsg, lp(top = 20))

        setContentView(scroll)
        status?.let { render(it) }
    }

    private fun showRaw() {
        val raw = status?.raw?.toString(2) ?: "Veri yok"
        val tv = TextView(this).apply {
            text = raw; textSize = 11f; setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE; setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle("Ham veri")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Kopyala") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("kombi", raw))
                Toast.makeText(this, "Kopyalandı", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun pickDevice() {
        AlertDialog.Builder(this)
            .setTitle("Cihaz seç")
            .setItems(endpoints.map { it.name }.toTypedArray()) { _, i ->
                current = endpoints[i]
                prefs.edit().putString("endpoint", current!!.id).apply()
                tvName.text = current!!.name
                status = null; refresh()
            }.show()
    }

    private fun refresh() {
        val ep = current ?: return
        if (pendingTarget != null) return
        bgTask({
            val s = api.getStatus(ep.id)
            ui.post { status = s; render(s) }
        })
    }

    private fun currentTargetOf(s: CosaApi.Status): Double? {
        val o = s.option
        return if (o in listOf("home", "away", "sleep", "custom") && s.targets.has(o))
            s.targets.optDouble(o) else s.targetTemperature
    }

    private fun render(s: CosaApi.Status) {
        tvName.text = s.name
        tvTemp.text = fmt(s.temperature)
        val parts = mutableListOf<String>()
        s.humidity?.let { parts += "Nem %${it.toInt()}" }
        when (s.connected) { true -> parts += "Bağlı"; false -> parts += "Bağlantı yok"; null -> {} }
        tvInfo.text = parts.joinToString("  •  ")
        tvFlame.text = when (s.combiOn) { true -> "🔥 Kombi yanıyor"; false -> "Kombi beklemede"; null -> "" }
        tvFlame.setTextColor(if (s.combiOn == true) accent else dim)
        if (pendingTarget == null) tvTarget.text = if (s.option == "frozen") "Kapalı" else fmt(currentTargetOf(s))

        optionButtons.forEach { (k, b) -> b.background = bgShape(if (k == s.option) accent else card, 12) }
        modeButtons.forEach { (k, b) -> b.background = bgShape(if (k == s.mode) accent else card, 12) }
        tvMsg.text = "Güncellendi: " + java.text.SimpleDateFormat("HH:mm:ss", Locale("tr")).format(java.util.Date())
    }

    private fun changeTarget(delta: Double) {
        val s = status ?: return
        if (s.option !in listOf("home", "away", "sleep", "custom")) {
            showMsg("Önce Evde / Dışarıda / Uyku / Özel seç"); return
        }
        val base = pendingTarget ?: currentTargetOf(s) ?: 20.0
        val v = (base + delta).coerceIn(5.0, 32.0)
        pendingTarget = v
        tvTarget.text = fmt(v)
        ui.removeCallbacks(sendTarget)
        ui.postDelayed(sendTarget, 1200)   // art arda basışları tek istekte gönder
    }

    private fun applyTarget() {
        val s = status ?: return; val ep = current ?: return
        val v = pendingTarget ?: return
        val targets = JSONObject(s.targets.toString()).put(s.option, v)
        showMsg("Gönderiliyor…")
        bgTask({
            api.setTargetTemperatures(ep.id, targets)
            ui.post { pendingTarget = null; showMsg("Hedef ${fmt(v)} yapıldı"); refresh() }
        }, { m -> pendingTarget = null; showMsg(m); refresh() })
    }

    private fun setOption(option: String) {
        val ep = current ?: return
        showMsg("Gönderiliyor…")
        bgTask({ api.setMode(ep.id, "manual", option); ui.post { refresh() } })
    }

    private fun setMode(mode: String) {
        val ep = current ?: return
        val s = status
        showMsg("Gönderiliyor…")
        bgTask({
            if (mode == "manual") api.setMode(ep.id, "manual", s?.option?.takeIf { it.isNotEmpty() } ?: "home")
            else api.setMode(ep.id, mode)
            ui.post { refresh() }
        })
    }
}
