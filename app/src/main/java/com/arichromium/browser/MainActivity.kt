package com.arichromium.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
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
import android.widget.ImageView
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
    private lateinit var tabSwitcherButton: TextView
    private lateinit var backButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var reloadButton: TextView
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var pageHost: FrameLayout
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
        // Chrome-inspired phone toolbar: address field first, then tab count and menu on the right.
        nav.layoutDirection = View.LAYOUT_DIRECTION_RTL
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
        backButton = button("→") { if (web.canGoBack()) web.goBack() }.apply {
            contentDescription = "חזרה"; textSize = 21f
        }
        nav.addView(backButton, LinearLayout.LayoutParams(dp(34), dp(42)))
        forwardButton = button("←") { if (web.canGoForward()) web.goForward() }.apply {
            contentDescription = "קדימה"; textSize = 21f
        }
        nav.addView(forwardButton, LinearLayout.LayoutParams(dp(34), dp(42)))
        reloadButton = button("↻") { web.reload() }.apply {
            contentDescription = "רענון"; textSize = 22f
        }
        nav.addView(reloadButton, LinearLayout.LayoutParams(dp(34), dp(42)))
        nav.addView(address, LinearLayout.LayoutParams(0, dp(42), 1f))
        nav.addView(button("+") { addTab("https://duckduckgo.com") }, LinearLayout.LayoutParams(dp(34), dp(42)))
        tabSwitcherButton = button("1") { showTabs() }.apply {
            textSize = 14f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.rgb(218, 220, 224))
                cornerRadius = dp(8).toFloat()
            }
            contentDescription = "כרטיסיות פתוחות"
        }
        nav.addView(tabSwitcherButton, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        })
        nav.addView(button("⋮") { showMenu() }, LinearLayout.LayoutParams(dp(38), dp(42)))
        root.addView(nav)

        progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(blue)
            visibility = View.GONE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(-1, dp(2)))

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
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val target = request.url
                val scheme = target.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    return false
                }
                if (scheme == "mailto" || scheme == "tel") {
                    try {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, target))
                    } catch (_: Exception) {
                        toast("לא ניתן לפתוח את הקישור הזה")
                    }
                    return true
                }
                return true
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val target = Uri.parse(url)
                val scheme = target.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    return false
                }
                if (scheme == "mailto" || scheme == "tel") {
                    try {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, target))
                    } catch (_: Exception) {
                        toast("לא ניתן לפתוח את הקישור הזה")
                    }
                    return true
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (view === web) {
                    address.setText(url)
                    updateNavigationButtons()
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (view === web) address.setText(url)
                if (url.startsWith("http")) {
                    val title = view.title?.takeIf { it.isNotBlank() } ?: url
                    addHistory(title, url)
                    if (activeTab in tabUrls.indices) tabUrls[activeTab] = url
                }
                if (view === web) updateNavigationButtons()
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view === web) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }
        }
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
        updateTabSwitcherCount()
        pageHost.removeAllViews(); pageHost.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.loadUrl(url)
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        activeTab = index; pageHost.removeAllViews()
        pageHost.addView(web, FrameLayout.LayoutParams(-1, -1))
        address.setText(web.url ?: "")
        updateTabSwitcherCount()
    }

    private fun updateTabSwitcherCount() {
        if (::tabSwitcherButton.isInitialized) {
            tabSwitcherButton.text = tabs.size.toString()
            tabSwitcherButton.contentDescription = "כרטיסיות פתוחות: " + tabs.size
        }
        updateNavigationButtons()
    }

    private fun updateNavigationButtons() {
        if (tabs.isEmpty()) return
        if (::backButton.isInitialized) {
            backButton.isEnabled = web.canGoBack()
            backButton.alpha = if (backButton.isEnabled) 1f else 0.38f
        }
        if (::forwardButton.isInitialized) {
            forwardButton.isEnabled = web.canGoForward()
            forwardButton.alpha = if (forwardButton.isEnabled) 1f else 0.38f
        }
        if (::reloadButton.isInitialized) {
            reloadButton.isEnabled = true
            reloadButton.alpha = 1f
        }
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
        // Chrome-like tab switcher: neutral canvas, compact header, and 2-column tab cards.
        val canvas = Color.rgb(241, 243, 244)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            setBackgroundColor(canvas)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "כרטיסיות פתוחות"; textSize = 22f; setTextColor(ink)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(button("＋") {
            tabsDialog?.dismiss()
            addTab("https://duckduckgo.com")
            showTabs()
        }.apply {
            textSize = 25f; setTextColor(blue)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(24).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(header)
        val scroll = android.widget.ScrollView(this)
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 2
            useDefaultMargins = false
        }
        val available = resources.displayMetrics.widthPixels - dp(44)
        val cardWidth = (available / 2).coerceAtLeast(dp(120))
        tabs.forEachIndexed { index, view ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.WHITE)
                    setStroke(dp(1), if (index == activeTab) blue else Color.rgb(218, 220, 224))
                    cornerRadius = dp(14).toFloat()
                }
                clipToOutline = true
            }
            val cardHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(6), dp(4))
            }
            cardHeader.addView(TextView(this).apply {
                text = view.title?.takeIf { it.isNotBlank() } ?: "דף חדש"
                textSize = 12f; setTextColor(ink); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, dp(32), 1f))
            cardHeader.addView(button("×") {
                if (tabs.size > 1) {
                    val wasActive = index == activeTab
                    view.stopLoading(); view.destroy()
                    tabs.removeAt(index); tabUrls.removeAt(index)
                    updateTabSwitcherCount()
                    if (wasActive) activeTab = index.coerceAtMost(tabs.lastIndex)
                    else if (index < activeTab) activeTab--
                    switchTab(activeTab)
                    tabsDialog?.dismiss()
                    showTabs()
                } else {
                    view.loadUrl("https://duckduckgo.com")
                    tabsDialog?.dismiss()
                }
            }.apply {
                textSize = 19f
                setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(dp(30), dp(32)))
            card.addView(cardHeader)
            val preview = FrameLayout(this).apply {
                setBackgroundColor(Color.WHITE)
                setOnClickListener { switchTab(index); tabsDialog?.dismiss() }
            }
            // Chrome's tab grid uses a snapshot of each page, not a generic globe.
            val thumbnail = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "תצוגה מקדימה של הכרטיסייה"
                setBackgroundColor(Color.WHITE)
                try {
                    val sourceWidth = view.width.coerceAtLeast(dp(160))
                    val sourceHeight = view.height.coerceAtLeast(dp(240))
                    val bitmap = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    setImageBitmap(Bitmap.createScaledBitmap(bitmap, dp(240), dp(150), true))
                    bitmap.recycle()
                } catch (_: Exception) {
                    setBackgroundColor(Color.rgb(248, 250, 253))
                }
                setOnClickListener { switchTab(index); tabsDialog?.dismiss() }
            }
            preview.addView(thumbnail, FrameLayout.LayoutParams(-1, -1))
            card.addView(preview, LinearLayout.LayoutParams(-1, dp(108)))
            card.setOnClickListener { switchTab(index); tabsDialog?.dismiss() }
            val params = android.widget.GridLayout.LayoutParams().apply {
                width = cardWidth
                height = dp(154)
                setMargins(dp(4), dp(5), dp(4), dp(9))
            }
            grid.addView(card, params)
        }
        scroll.addView(grid)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        bottom.addView(button("＋ כרטיסייה חדשה") {
            tabsDialog?.dismiss()
            addTab("https://duckduckgo.com")
            showTabs()
        }.apply {
            textSize = 14f; setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(blue); cornerRadius = dp(24).toFloat()
            }
        }, LinearLayout.LayoutParams(-1, dp(46)))
        panel.addView(bottom)
        tabsDialog = AlertDialog.Builder(this).setView(panel).create()
        tabsDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        tabsDialog?.show()
        tabsDialog?.window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.88f).toInt())
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
