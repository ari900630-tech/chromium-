package com.arichromium.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class MainActivity : Activity() {
    private val blue = Color.rgb(26, 115, 232)
    private val ink = Color.rgb(32, 33, 36)
    private val pale = Color.rgb(241, 243, 244)
    private lateinit var root: LinearLayout
    private lateinit var address: EditText
    private lateinit var pageHost: FrameLayout
    private lateinit var tabLabel: TextView
    private val tabs = mutableListOf<WebView>()
    private val tabUrls = mutableListOf<String>()
    private var activeTab = 0
    private var tabsDialog: AlertDialog? = null
    private val prefs by lazy { getSharedPreferences("browser", Context.MODE_PRIVATE) }
    private val history = mutableListOf<Pair<String, String>>()
    private val downloads = mutableListOf<String>()
    private val web: WebView get() = tabs[activeTab]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        loadSavedData()
        buildChromeUi()
        addTab("https://duckduckgo.com")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 20f; gravity = Gravity.CENTER; setTextColor(ink)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setOnClickListener { action() }
    }

    private fun buildChromeUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4)); setBackgroundColor(Color.WHITE)
        }
        // Browser actions stay on the physical left; the address field fills the remaining space.
        nav.layoutDirection = View.LAYOUT_DIRECTION_LTR
        nav.addView(button("⋮") { showMenu() }, LinearLayout.LayoutParams(dp(40), dp(42)))
        nav.addView(button("+") { addTab("https://duckduckgo.com"); showTabs() }, LinearLayout.LayoutParams(dp(40), dp(42)))
        nav.addView(button("›") { if (web.canGoForward()) web.goForward() }, LinearLayout.LayoutParams(dp(36), dp(42)))
        nav.addView(button("‹") { if (web.canGoBack()) web.goBack() }, LinearLayout.LayoutParams(dp(36), dp(42)))
        nav.addView(button("↻") { web.reload() }, LinearLayout.LayoutParams(dp(36), dp(42)))
        address = EditText(this).apply {
            textSize = 14f; isSingleLine = true; hint = "חיפוש או הקלדת כתובת"
            setTextColor(ink); setHintTextColor(Color.GRAY); setPadding(dp(12), 0, dp(12), 0)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(pale); cornerRadius = dp(24).toFloat() }
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                    navigate(address.text.toString())
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
                    true
                } else false
            }
        }
        nav.addView(address, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(nav)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(8), 0, dp(8), dp(3)); setBackgroundColor(Color.WHITE)
        }
        tabLabel = TextView(this).apply {
            text = "1"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(ink)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(dp(1), ink); cornerRadius = dp(5).toFloat()
            }
            setOnClickListener { showTabs() }
        }
        tabLabel.setOnClickListener { showTabs() }
        actions.addView(tabLabel, LinearLayout.LayoutParams(dp(42), dp(34)))
        actions.addView(TextView(this).apply {
            text = "כרטיסיות פתוחות"; textSize = 12f; setTextColor(Color.GRAY)
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(-2, dp(34)))
        root.addView(actions)
        pageHost = FrameLayout(this)
        root.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun createWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        CookieManager.getInstance().setAcceptCookie(true)
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (view === web) address.setText(url)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (view === web) address.setText(url)
                if (url.startsWith("http")) {
                    val title = view.title?.takeIf { it.isNotBlank() } ?: url
                    addHistory(title, url)
                    if (activeTab in tabUrls.indices) tabUrls[activeTab] = url
                }
            }
        }
        webChromeClient = WebChromeClient()
        setDownloadListener { url, userAgent, _, mimeType, _ ->
            try {
                val fileName = Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() } ?: "download"
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .addRequestHeader("User-Agent", userAgent ?: "")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setTitle(fileName)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                downloads.add(url); saveDownloads(); toast("ההורדה התחילה")
            } catch (_: Exception) { toast("לא ניתן להתחיל הורדה זו") }
        }
    }

    private fun addTab(url: String) {
        val view = createWebView()
        tabs.add(view); tabUrls.add(url); activeTab = tabs.lastIndex
        pageHost.removeAllViews(); pageHost.addView(view, FrameLayout.LayoutParams(-1, -1))
        tabLabel.text = tabs.size.toString(); view.loadUrl(url)
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        activeTab = index; pageHost.removeAllViews()
        pageHost.addView(web, FrameLayout.LayoutParams(-1, -1))
        address.setText(web.url ?: ""); tabLabel.text = tabs.size.toString()
    }

    private fun navigate(input: String) {
        val value = input.trim()
        if (value.isEmpty()) return
        val url = when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.contains(".") && !value.contains(" ") -> "https://" + value
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(value, "UTF-8")
        }
        web.loadUrl(url)
    }

    private fun showHome() = web.loadUrl("https://duckduckgo.com")

    private fun showMenu() {
        val options = arrayOf("כרטיסייה חדשה", "היסטוריה", "הורדות", "תרגם לעברית", "סימניות", "הגדרות")
        AlertDialog.Builder(this).setItems(options) { _, which ->
            when (which) {
                0 -> addTab("https://duckduckgo.com")
                1 -> showHistory()
                2 -> showDownloads()
                3 -> translatePage()
                4 -> showBookmarks()
                5 -> showSettings()
            }
        }.show()
    }

    private fun translatePage() {
        val url = web.url ?: return
        web.loadUrl("https://translate.google.com/translate?sl=auto&tl=iw&u=" + URLEncoder.encode(url, "UTF-8"))
    }

    private fun showTabs() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.rgb(241, 243, 244))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "כרטיסיות"; textSize = 22f; setTextColor(ink)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(button("+ כרטיסייה חדשה") {
            addTab("https://duckduckgo.com")
            showTabs()
        }.apply {
            textSize = 14f; setTextColor(blue)
        }, LinearLayout.LayoutParams(-2, dp(48)))
        panel.addView(header)
        val scroll = android.widget.ScrollView(this)
        val cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tabs.forEachIndexed { index, view ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.WHITE)
                    setStroke(dp(1), if (index == activeTab) blue else Color.rgb(218, 220, 224))
                    cornerRadius = dp(16).toFloat()
                }
                setOnClickListener { switchTab(index); tabsDialog?.dismiss() }
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(this).apply {
                text = (view.title?.takeIf { it.isNotBlank() } ?: tabUrls.getOrElse(index) { "כרטיסייה חדשה" })
                textSize = 15f; setTextColor(ink); maxLines = 1
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
            top.addView(button("×") {
                if (tabs.size > 1) {
                    val wasActive = index == activeTab
                    view.stopLoading(); view.destroy()
                    tabs.removeAt(index); tabUrls.removeAt(index)
                    if (wasActive) activeTab = index.coerceAtMost(tabs.lastIndex)
                    else if (index < activeTab) activeTab--
                    switchTab(activeTab)
                    showTabs()
                } else {
                    view.loadUrl("https://duckduckgo.com")
                    tabsDialog?.dismiss()
                }
            }, LinearLayout.LayoutParams(dp(40), dp(38)))
            card.addView(top)
            card.addView(TextView(this).apply {
                text = tabUrls.getOrElse(index) { view.url ?: "" }
                textSize = 12f; setTextColor(Color.GRAY); maxLines = 1
                setPadding(0, dp(3), 0, dp(3))
            })
            cards.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        scroll.addView(cards)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        tabsDialog = AlertDialog.Builder(this).setView(panel).create()
        tabsDialog?.show()
        tabsDialog?.window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.85f).toInt())
    }

    private fun showHistory() {
        if (history.isEmpty()) { toast("אין היסטוריה עדיין"); return }
        val entries = history.take(100)
        AlertDialog.Builder(this).setTitle("היסטוריה")
            .setItems(entries.map { it.first + "\n" + it.second }.toTypedArray()) { _, i -> navigate(entries[i].second) }
            .setNeutralButton("נקה היסטוריה") { _, _ -> history.clear(); saveHistory(); toast("ההיסטוריה נמחקה") }.show()
    }

    private fun showDownloads() {
        if (downloads.isEmpty()) { toast("אין הורדות שנרשמו עדיין"); return }
        AlertDialog.Builder(this).setTitle("הורדות").setItems(downloads.toTypedArray()) { _, i -> navigate(downloads[i]) }
            .setPositiveButton("סיום", null).show()
    }

    private fun showBookmarks() {
        val saved = prefs.getString("bookmarks", "[]") ?: "[]"
        val array = JSONArray(saved)
        val entries = (0 until array.length()).map { array.getString(it) }
        val choices = entries + listOf("★ הוסף את האתר הנוכחי")
        AlertDialog.Builder(this).setTitle("סימניות").setItems(choices.toTypedArray()) { _, which ->
            if (which == entries.size) {
                val url = web.url ?: return@setItems
                prefs.edit().putString("bookmarks", JSONArray(saved).put(url).toString()).apply()
                toast("נוספה סימנייה")
            } else navigate(entries[which])
        }.show()
    }

    private fun showSettings() {
        val choices = arrayOf("נקה נתוני גלישה", "פתח את מנוע החיפוש", "מידע על הדפדפן")
        AlertDialog.Builder(this).setTitle("הגדרות").setItems(choices) { _, which ->
            when (which) {
                0 -> AlertDialog.Builder(this).setMessage("למחוק את היסטוריית הגלישה המקומית?")
                    .setNegativeButton("ביטול", null)
                    .setPositiveButton("מחק") { _, _ -> history.clear(); saveHistory(); web.clearCache(true); web.clearHistory(); toast("הנתונים המקומיים נוקו") }.show()
                1 -> showHome()
                2 -> AlertDialog.Builder(this).setMessage("Chromium Browser\nדפדפן Android קל עם ממשק בהשראת Chrome.")
                    .setPositiveButton("אישור", null).show()
            }
        }.show()
    }

    private fun addHistory(title: String, url: String) {
        if (history.firstOrNull()?.second == url) return
        history.add(0, title to url)
        while (history.size > 300) history.removeAt(history.lastIndex)
        saveHistory()
    }

    private fun loadSavedData() {
        try {
            val a = JSONArray(prefs.getString("history", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); history.add(o.optString("title") to o.optString("url"))
            }
            val d = JSONArray(prefs.getString("downloads", "[]"))
            for (i in 0 until d.length()) downloads.add(d.getString(i))
        } catch (_: Exception) {}
    }

    private fun saveHistory() {
        val a = JSONArray()
        history.forEach { (title, url) -> a.put(JSONObject().put("title", title).put("url", url)) }
        prefs.edit().putString("history", a.toString()).apply()
    }

    private fun saveDownloads() {
        val a = JSONArray(); downloads.forEach { a.put(it) }
        prefs.edit().putString("downloads", a.toString()).apply()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (tabs.isNotEmpty() && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        tabs.forEach { it.destroy() }
        super.onDestroy()
    }
}
