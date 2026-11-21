package app.cloude.browser

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.webkit.WebChromeClient
import android.widget.ProgressBar
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tek ekranlı tarayıcı. Arka planda aracı sunucuyu yoklar, Claude'dan gelen komutları
 * (aç, metin oku, tıkla, yaz, ekran görüntüsü...) WebView üzerinde çalıştırır.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var status: TextView
    private lateinit var dot: View
    private lateinit var progress: ProgressBar

    @Volatile private var running = true
    @Volatile private var navLatch: CountDownLatch? = null

    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        Thread { pollLoop() }.apply { isDaemon = true; start() }
        if (prefs.getString("relay", "").isNullOrEmpty()) showSettings()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun flatButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 20f
            setTextColor(INK)
            gravity = Gravity.CENTER
            minWidth = dp(40)
            minHeight = dp(40)
            setOnClickListener { onClick() }
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(6))
        }
        val back = flatButton("‹") { if (web.canGoBack()) web.goBack() }
        urlBox = EditText(this).apply {
            hint = "Adres yaz ya da ara"
            setHintTextColor(MUTED)
            setTextColor(INK)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 14f
            background = rounded(FIELD, 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    openFromBox(); true
                } else false
            }
        }
        val go = flatButton("→") { openFromBox() }
        val cfg = flatButton("⚙") { showSettings() }
        bar.addView(back)
        bar.addView(urlBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4); marginEnd = dp(4)
        })
        bar.addView(go)
        bar.addView(cfg)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.INVISIBLE
            progressDrawable.setColorFilter(ACCENT, android.graphics.PorterDuff.Mode.SRC_IN)
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(6))
        }
        dot = View(this).apply { background = rounded(MUTED, 4) }
        status = TextView(this).apply {
            text = "Bağlı değil"
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(8), 0, 0, 0)
        }
        statusRow.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))
        statusRow.addView(status)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val sc = request?.url?.scheme ?: return true
                return sc != "http" && sc != "https"
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                showUrl(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                showUrl(url)
                navLatch?.countDown()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) navLatch?.countDown()
            }
        }
        // Güvenlik: dosya indirmeleri engellenir.
        web.setDownloadListener { url, _, _, _, _ ->
            Toast.makeText(this, "İndirme engellendi: $url", Toast.LENGTH_LONG).show()
        }

        root.addView(bar)
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(statusRow)
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        web.loadDataWithBaseURL(null, WELCOME_HTML, "text/html", "utf-8", null)
    }

    private fun showUrl(url: String?) {
        urlBox.setText(if (url != null && url.startsWith("http")) url else "")
    }

    private fun openFromBox() {
        val v = urlBox.text.toString().trim()
        if (v.isEmpty()) return
        val target = when {
            v.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE)) -> v
            !v.contains(" ") && v.contains(".") -> "https://$v"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        web.loadUrl(target)
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), 0) }
        val relay = EditText(this).apply {
            hint = "Aracı sunucu adresi (https://...)"
            setText(prefs.getString("relay", ""))
            setSingleLine(true)
        }
        val token = EditText(this).apply {
            hint = "Gizli anahtar (TOKEN)"
            setText(prefs.getString("token", ""))
            setSingleLine(true)
        }
        box.addView(relay)
        box.addView(token)
        AlertDialog.Builder(this)
            .setTitle("Bağlantı ayarları")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                prefs.edit().putString("relay", relay.text.toString().trim()).putString("token", token.text.toString().trim()).apply()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun setStatus(t: String) {
        runOnUiThread {
            status.text = t
            val ok = t.startsWith("Bağlı") && !t.contains("değil")
            val warn = t.startsWith("Ayar")
            (dot.background as GradientDrawable).setColor(if (ok) GOOD else if (warn) ACCENT else BAD)
        }
    }

    private fun nap(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }

    // ---- Sunucu ile haberleşme ----

    private fun pollLoop() {
        while (running) {
            val base = (prefs.getString("relay", "") ?: "").trim().trimEnd('/')
            val token = (prefs.getString("token", "") ?: "").trim()
            if (base.isEmpty() || token.isEmpty()) {
                setStatus("Ayar gerekli (⚙)")
                nap(2000)
                continue
            }
            try {
                val c = URL("$base/device/$token/poll").openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 40000
                val code = c.responseCode
                if (code == 200) {
                    setStatus("Bağlı, komut çalışıyor")
                    val body = c.inputStream.bufferedReader().readText()
                    val res = runCommand(JSONObject(body))
                    postResult("$base/device/$token/result", res)
                    setStatus("Bağlı")
                } else if (code == 204) {
                    setStatus("Bağlı")
                } else {
                    setStatus("Sunucu hatası: $code")
                    nap(3000)
                }
                c.disconnect()
            } catch (e: Exception) {
                setStatus("Bağlantı yok")
                nap(3000)
            }
        }
    }

    private fun postResult(endpoint: String, res: JSONObject) {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(res.toString().toByteArray(Charsets.UTF_8)) }
        c.responseCode
        c.disconnect()
    }

    // ---- Komutlar ----

    private fun runCommand(cmd: JSONObject): JSONObject {
        val out = JSONObject().put("id", cmd.getString("id"))
        try {
            val args = cmd.optJSONObject("args") ?: JSONObject()
            val data: String = when (cmd.getString("action")) {
                "navigate" -> navigate(args.getString("url"))
                "info" -> onUi { "${web.title ?: ""} | ${web.url ?: ""}" }
                "text" -> evalJs(JS_TEXT, args)
                "links" -> evalJs(JS_LINKS, args)
                "report" -> evalJs(JS_REPORT, args)
                "click" -> evalJs(JS_CLICK, args)
                "type" -> evalJs(JS_TYPE, args)
                "scroll" -> evalJs("window.scrollBy(0, Number(A.dy)||0); return {scrollY: window.scrollY};", args)
                "eval" -> evalJs(args.getString("script"), JSONObject())
                "back" -> {
                    val moved = onUi { if (web.canGoBack()) { web.goBack(); true } else false }
                    nap(1500)
                    if (moved) "Geri gidildi" else "Geri gidilecek sayfa yok"
                }
                "screenshot" -> screenshot()
                else -> throw IllegalArgumentException("Bilinmeyen komut")
            }
            out.put("ok", true).put("data", data)
        } catch (e: Exception) {
            out.put("ok", false).put("error", e.message ?: e.toString())
        }
        return out
    }

    private fun <T> onUi(block: () -> T): T {
        val latch = CountDownLatch(1)
        var result: Any? = null
        var failure: Throwable? = null
        runOnUiThread {
            try { result = block() } catch (t: Throwable) { failure = t }
            latch.countDown()
        }
        if (!latch.await(15, TimeUnit.SECONDS)) throw RuntimeException("Arayüz yanıt vermedi")
        val f = failure
        if (f != null) throw f
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun navigate(raw: String): String {
        val u = when {
            raw.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE)) -> raw
            !raw.contains("://") -> "https://$raw"
            else -> throw IllegalArgumentException("Yalnızca http ve https adresleri açılır")
        }
        val latch = CountDownLatch(1)
        navLatch = latch
        runOnUiThread { web.loadUrl(u) }
        latch.await(25, TimeUnit.SECONDS)
        return onUi { "${web.title ?: ""} | ${web.url ?: ""}" }
    }

    private fun evalJs(body: String, args: JSONObject): String {
        val script = "(function(){try{var A=$args;var r=(function(){$body\n})();" +
            "return JSON.stringify(r===undefined?null:r);}catch(e){return JSON.stringify({error:String(e)});}})()"
        val latch = CountDownLatch(1)
        var raw: String? = null
        runOnUiThread {
            web.evaluateJavascript(script) { v -> raw = v; latch.countDown() }
        }
        if (!latch.await(15, TimeUnit.SECONDS)) throw RuntimeException("Sayfa betiği zaman aşımına uğradı")
        val v = raw
        if (v == null || v == "null") return "null"
        return JSONTokener(v).nextValue().toString()
    }

    private fun screenshot(): String = onUi {
        val w = web.width
        val h = web.height
        if (w <= 0 || h <= 0) throw RuntimeException("Görünüm boş")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        web.draw(Canvas(bmp))
        val scaled = if (w > 900) Bitmap.createScaledBitmap(bmp, 900, h * 900 / w, true) else bmp
        val bo = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 65, bo)
        Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private val BG = Color.parseColor("#FAF9F5")
        private val INK = Color.parseColor("#1F1E1D")
        private val MUTED = Color.parseColor("#6B6A68")
        private val FIELD = Color.parseColor("#EFEBE1")
        private val ACCENT = Color.parseColor("#C96442")
        private val GOOD = Color.parseColor("#2E7D4F")
        private val BAD = Color.parseColor("#B3261E")

        private const val WELCOME_HTML = """
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body{margin:0;padding:32px 24px;background:#FAF9F5;color:#1F1E1D;font-family:sans-serif;line-height:1.5}
              h1{font-size:22px;margin:0 0 8px} p{color:#6B6A68;margin:0 0 16px}
              .card{background:#fff;border:1px solid #E3DFD6;border-radius:14px;padding:16px;margin-top:20px}
              b{color:#C96442}
            </style></head><body>
              <h1>Cloude Browser</h1>
              <p>Claude'un senin adına açıp inceleyebildiği tarayıcı.</p>
              <div class="card"><b>1.</b> Sağ üstteki ⚙ düğmesinden sunucu adresini ve anahtarı gir.<br>
              <b>2.</b> Üstte yeşil nokta "Bağlı" göstermeli.<br>
              <b>3.</b> Claude'a "şu siteyi aç ve analiz et" de.</div>
              <p style="margin-top:24px;font-size:13px">İndirmeler engellenir, yalnızca http ve https adresleri açılır.</p>
            </body></html>
        """

        private const val JS_TEXT = """
            var m = A.max || 20000;
            var t = document.body ? document.body.innerText : '';
            return t.length > m ? t.slice(0, m) + '\n...[kesildi, toplam ' + t.length + ' karakter]' : t;
        """

        private const val JS_LINKS = """
            var o = [];
            var max = A.max || 200;
            document.querySelectorAll('a[href]').forEach(function (a) {
                if (o.length < max) o.push({ text: (a.innerText || a.title || '').trim().slice(0, 80), href: a.href });
            });
            return o;
        """

        private const val JS_CLICK = """
            var el = null;
            if (A.selector) {
                el = document.querySelector(A.selector);
            } else if (A.text) {
                var t = String(A.text).toLowerCase();
                var c = document.querySelectorAll('a,button,[role=button],input[type=submit],input[type=button],summary');
                for (var i = 0; i < c.length; i++) {
                    var s = ((c[i].innerText || c[i].value || c[i].title || '') + '').toLowerCase();
                    if (s.indexOf(t) > -1) { el = c[i]; break; }
                }
            }
            if (!el) return { error: 'Öğe bulunamadı' };
            el.scrollIntoView({ block: 'center' });
            el.click();
            return { clicked: (el.tagName + ' ' + (el.innerText || el.value || '')).slice(0, 80) };
        """

        private const val JS_TYPE = """
            var el = document.querySelector(A.selector);
            if (!el) return { error: 'Öğe bulunamadı' };
            el.focus();
            var isInput = el.tagName === 'INPUT' || el.tagName === 'TEXTAREA';
            if (isInput) {
                var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                var d = Object.getOwnPropertyDescriptor(proto, 'value');
                if (d && d.set) { d.set.call(el, A.text); } else { el.value = A.text; }
            } else {
                el.textContent = A.text;
            }
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            if (A.submit && el.form) {
                if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit();
            }
            return { typed: true };
        """

        private const val JS_REPORT = """
            var h = location.hostname;
            function host(u) { try { return new URL(u, location.href).hostname; } catch (e) { return ''; } }
            var forms = [].map.call(document.forms, function (f) {
                var types = [].map.call(f.elements, function (e) { return e.type || e.tagName; })
                    .filter(function (v, i, a) { return a.indexOf(v) === i; });
                return { action: f.action, method: f.method, fields: types, external: host(f.action) !== h };
            });
            var scripts = {};
            [].forEach.call(document.scripts, function (s) { if (s.src) scripts[host(s.src)] = 1; });
            var iframes = [].map.call(document.querySelectorAll('iframe'), function (f) { return f.src; }).slice(0, 20);
            var dl = [].filter.call(document.querySelectorAll('a[href]'), function (a) {
                return /\.(apk|exe|msi|zip|rar|7z|dmg|bat|scr|jar)(\?|${'$'})/i.test(a.href) || a.hasAttribute('download');
            }).map(function (a) { return a.href; }).slice(0, 20);
            var ext = {};
            [].forEach.call(document.querySelectorAll('a[href]'), function (a) {
                var x = host(a.href);
                if (x && x !== h) ext[x] = (ext[x] || 0) + 1;
            });
            return {
                url: location.href,
                title: document.title,
                https: location.protocol === 'https:',
                hasPasswordField: !!document.querySelector('input[type=password]'),
                forms: forms,
                scriptHosts: Object.keys(scripts),
                iframes: iframes,
                downloadLinks: dl,
                externalLinkHosts: ext,
                metaRefresh: !!document.querySelector('meta[http-equiv=refresh]'),
                textLength: document.body ? document.body.innerText.length : 0
            };
        """
    }
}
