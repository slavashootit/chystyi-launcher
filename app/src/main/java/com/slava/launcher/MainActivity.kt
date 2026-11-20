package com.slava.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var rootLayout: GestureRootLayout
    private lateinit var scrimView: WallpaperScrimView
    private lateinit var homeContainer: LinearLayout
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var defaultHomeBanner: TextView
    private lateinit var updateBanner: TextView
    private lateinit var favoritesContainer: LinearLayout
    private lateinit var swipeHintText: TextView

    private lateinit var drawerContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var clearSearchBtn: TextView
    private lateinit var appsListView: ListView
    private lateinit var alphabetSidebar: AlphabetSidebarView
    private lateinit var emptySearchText: TextView

    private var allApps: List<AppEntry> = emptyList()
    private var visibleDrawerApps: List<AppEntry> = emptyList()
    private lateinit var drawerAdapter: DrawerAppAdapter

    private var isDrawerOpen = false
    private var lastSearchLength = 0
    private var suppressAutoLaunch = false
    private var drawerAnimator: ValueAnimator? = null

    private val timeFormat = SimpleDateFormat("HH:mm", Locale("uk", "UA"))
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale("uk", "UA"))

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateClockAndDate()
        }
    }

    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = reloadApps()
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = reloadApps()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = reloadApps()
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reloadApps()
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reloadApps()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        window.setDecorFitsSystemWindows(false)
        buildUi()
        setupWindowInsets()
        setupBackHandling()

        getSystemService(LauncherApps::class.java)?.registerCallback(launcherAppsCallback)
        reloadApps()
        updateClockAndDate()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        registerReceiver(timeReceiver, filter)
        updateClockAndDate()
        updateDefaultLauncherBanner()
        reloadApps()
        checkUpdates(manual = false)
    }

    private fun showUpdateBanner(rel: Updater.Release?) {
        updateBanner.visibility = if (rel == null) View.GONE else View.VISIBLE
        if (rel != null) updateBanner.text = "Є оновлення ${rel.version}  ·  Оновити  →"
    }

    private fun checkUpdates(manual: Boolean) {
        if (manual) Toast.makeText(this, "Перевіряю оновлення…", Toast.LENGTH_SHORT).show()
        Updater.check(this, force = manual) { rel, err ->
            if (isDestroyed) return@check
            showUpdateBanner(rel)
            if (!manual) return@check
            when {
                err != null -> Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                rel == null -> Toast.makeText(this, "У тебе найновіша версія", Toast.LENGTH_SHORT).show()
                else -> Updater.offer(this, rel)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(timeReceiver)
        } catch (_: Throwable) {}
        hideKeyboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            getSystemService(LauncherApps::class.java)?.unregisterCallback(launcherAppsCallback)
        } catch (_: Throwable) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Натискання кнопки/жесту Home завжди повертає на чистий головний екран
        if (isDrawerOpen) {
            closeDrawer(animated = true)
        }
    }

    private fun setupBackHandling() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback {
                    if (isDrawerOpen) {
                        closeDrawer(animated = true)
                    }
                    // На головному екрані Back нічого не закриває
                }
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isDrawerOpen) {
            closeDrawer(animated = true)
        }
    }

    private fun buildUi() {
        rootLayout = GestureRootLayout(this)

        // 1. Скрім поверх системних шпалер
        scrimView = WallpaperScrimView(this).apply {
            scrimMode = prefs.scrimMode
        }
        rootLayout.addView(
            scrimView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 2. Головний екран (Home)
        homeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28f), dp(64f), dp(28f), dp(36f))
            isClickable = false
        }

        // Верхній блок: Годинник і дата
        val clockBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18f), 0, dp(12f))
        }

        clockText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 62f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            letterSpacing = -0.02f
            setShadowLayer(dpF(12f), 0f, dpF(2f), Color.argb(110, 0, 0, 0))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val custom = findAppByKey(prefs.clockTapKey)
                AppRepository.openClockOrAlarm(this@MainActivity, custom)
            }
            setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showLauncherSettingsDialog()
                true
            }
        }
        clockBlock.addView(
            clockText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        dateText = TextView(this).apply {
            setTextColor(Color.argb(215, 240, 244, 250))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            letterSpacing = 0.01f
            setPadding(dp(2f), dp(2f), dp(8f), dp(6f))
            setShadowLayer(dpF(8f), 0f, dpF(1f), Color.argb(120, 0, 0, 0))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val custom = findAppByKey(prefs.dateTapKey)
                AppRepository.openCalendar(this@MainActivity, custom)
            }
            setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showLauncherSettingsDialog()
                true
            }
        }
        clockBlock.addView(
            dateText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        defaultHomeBanner = TextView(this).apply {
            text = "Зробити основним екраном  →"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(20f)
                setColor(Color.argb(135, 24, 28, 38))
                setStroke(dp(1f), Color.argb(90, 255, 255, 255))
            }
            visibility = View.GONE
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                promptSetDefaultLauncher()
            }
        }
        val bannerLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10f)
        }
        clockBlock.addView(defaultHomeBanner, bannerLp)

        updateBanner = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(20f)
                setColor(Color.argb(170, 34, 96, 64))
                setStroke(dp(1f), Color.argb(120, 160, 255, 200))
            }
            visibility = View.GONE
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                Updater.cachedUpdate(this@MainActivity)?.let { rel -> Updater.offer(this@MainActivity, rel) }
            }
        }
        clockBlock.addView(updateBanner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10f) })

        homeContainer.addView(
            clockBlock,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // Розпірка, що притискає головні програми в нижню половину під великий палець S25 Ultra
        val spacer = View(this)
        homeContainer.addView(
            spacer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // Контейнер для 5–8 головних програм
        favoritesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f), 0, dp(18f))
        }
        homeContainer.addView(
            favoritesContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // Тонка підказка швидких свайпів унизу
        swipeHintText = TextView(this).apply {
            setTextColor(Color.argb(115, 235, 240, 248))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            letterSpacing = 0.03f
            setPadding(dp(2f), dp(8f), dp(2f), dp(4f))
            setOnClickListener {
                showLauncherSettingsDialog()
            }
        }
        homeContainer.addView(
            swipeHintText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        rootLayout.addView(
            homeContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 3. Шар усіх програм (App Drawer + Пошук)
        drawerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(54f), dp(12f), dp(16f))
            visibility = View.GONE
            alpha = 0f
        }

        val searchBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(6f), dp(10f), dp(6f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(26f)
                setColor(Color.argb(185, 22, 25, 34))
                setStroke(dp(1f), Color.argb(65, 255, 255, 255))
            }
        }

        val searchIcon = TextView(this).apply {
            text = "⌕"
            setTextColor(Color.argb(165, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, 0, dp(10f), 0)
        }
        searchBarRow.addView(searchIcon)

        searchInput = EditText(this).apply {
            hint = "Пошук програм…"
            setHintTextColor(Color.argb(110, 255, 255, 255))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(0, dp(8f), 0, dp(8f))

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString() ?: ""
                    clearSearchBtn.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                    val isTypingForward = q.length > lastSearchLength
                    lastSearchLength = q.length
                    filterDrawerApps(q, allowAutoLaunch = isTypingForward && !suppressAutoLaunch)
                }
            })

            setOnEditorActionListener { _, actionId, event ->
                val isEnter = actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (isEnter) {
                    val first = visibleDrawerApps.firstOrNull()
                    if (first != null) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        launchFromDrawer(first)
                    }
                    true
                } else {
                    false
                }
            }
        }
        searchBarRow.addView(
            searchInput,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        clearSearchBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.argb(180, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            visibility = View.GONE
            setOnClickListener {
                suppressAutoLaunch = true
                searchInput.setText("")
                suppressAutoLaunch = false
            }
        }
        searchBarRow.addView(clearSearchBtn)

        val settingsBtn = TextView(this).apply {
            text = "⚙"
            setTextColor(Color.argb(180, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(10f), dp(6f), dp(8f), dp(6f))
            setOnClickListener {
                hideKeyboard()
                showLauncherSettingsDialog()
            }
        }
        searchBarRow.addView(settingsBtn)

        val searchBarLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            rightMargin = dp(8f)
            bottomMargin = dp(10f)
        }
        drawerContainer.addView(searchBarRow, searchBarLp)

        // Контейнер списку + алфавітного індексу
        val listFrame = FrameLayout(this)

        appsListView = ListView(this).apply {
            divider = null
            dividerHeight = 0
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, dp(4f), dp(28f), dp(36f))

            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
                        hideKeyboard()
                    }
                }
                override fun onScroll(
                    view: AbsListView?,
                    firstVisibleItem: Int,
                    visibleItemCount: Int,
                    totalItemCount: Int
                ) {}
            })
        }
        drawerAdapter = DrawerAppAdapter()
        appsListView.adapter = drawerAdapter

        listFrame.addView(
            appsListView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        alphabetSidebar = AlphabetSidebarView(this) { letter ->
            hideKeyboard()
            val idx = visibleDrawerApps.indexOfFirst { it.firstLetter.equals(letter, ignoreCase = true) }
            if (idx >= 0) {
                appsListView.setSelectionFromTop(idx, dp(12f))
            }
        }
        val sidebarLp = FrameLayout.LayoutParams(
            dp(28f),
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.END
        )
        listFrame.addView(alphabetSidebar, sidebarLp)

        emptySearchText = TextView(this).apply {
            text = "Нічого не знайдено"
            setTextColor(Color.argb(140, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(48f), 0, 0)
        }
        listFrame.addView(
            emptySearchText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        drawerContainer.addView(
            listFrame,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        rootLayout.addView(
            drawerContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(rootLayout)
    }

    private fun setupWindowInsets() {
        rootLayout.setOnApplyWindowInsetsListener { _, insets ->
            val sysBars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            val bottomInset = maxOf(sysBars.bottom, ime.bottom)

            homeContainer.setPadding(
                dp(28f),
                sysBars.top + dp(28f),
                dp(28f),
                sysBars.bottom + dp(28f)
            )
            drawerContainer.setPadding(
                dp(20f),
                sysBars.top + dp(14f),
                dp(12f),
                bottomInset + dp(8f)
            )
            insets
        }
    }

    private fun reloadApps() {
        allApps = AppRepository.loadAllApps(this, prefs)
        prefs.ensureSmartDefaults(allApps)
        renderHomeFavorites()
        filterDrawerApps(searchInput.text?.toString() ?: "", allowAutoLaunch = false)
    }

    private fun findAppByKey(key: String?): AppEntry? {
        if (key.isNullOrBlank()) return null
        return allApps.firstOrNull { it.componentKey == key }
            ?: allApps.firstOrNull { key.startsWith("${it.packageName}/${it.className}") }
    }

    private fun getAlignGravity(): Int {
        return when (prefs.alignment) {
            Prefs.ALIGN_CENTER -> Gravity.CENTER_HORIZONTAL
            Prefs.ALIGN_RIGHT -> Gravity.END
            else -> Gravity.START
        }
    }

    private fun renderHomeFavorites() {
        val gravity = getAlignGravity()
        clockText.gravity = gravity
        dateText.gravity = gravity
        (clockText.parent as? LinearLayout)?.gravity = gravity

        favoritesContainer.removeAllViews()
        val favKeys = prefs.getFavoriteKeys()
        val favApps = favKeys.mapNotNull { findAppByKey(it) }

        for (app in favApps) {
            val row = TextView(this).apply {
                text = app.displayLabel
                setTextColor(Color.argb(248, 250, 252, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                letterSpacing = 0.01f
                this.gravity = gravity or Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4f), dp(11f), dp(4f), dp(11f))
                setShadowLayer(dpF(10f), 0f, dpF(2f), Color.argb(140, 0, 0, 0))

                setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.75f).setDuration(90).start()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .alpha(1f)
                                .setInterpolator(OvershootInterpolator(1.6f))
                                .setDuration(180)
                                .start()
                        }
                    }
                    false
                }

                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    AppRepository.launchApp(this@MainActivity, app, v)
                }

                setOnLongClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showHomeAppContextMenu(app)
                    true
                }
            }
            favoritesContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // Якщо список порожній — підказка додати програми
        if (favApps.isEmpty()) {
            val emptyHint = TextView(this).apply {
                text = "+ Додати програми на головний екран"
                setTextColor(Color.argb(180, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                this.gravity = gravity
                setPadding(dp(4f), dp(14f), dp(4f), dp(14f))
                setOnClickListener { showAddFavoriteDialog() }
            }
            favoritesContainer.addView(emptyHint)
        }

        // Оновлюємо підказку швидких свайпів
        if (prefs.showSwipeHint) {
            val rightApp = findAppByKey(prefs.swipeRightKey)?.displayLabel ?: "Телефон"
            val leftApp = findAppByKey(prefs.swipeLeftKey)?.displayLabel ?: "Камера"
            swipeHintText.visibility = View.VISIBLE
            swipeHintText.gravity = gravity
            swipeHintText.text = "→ $rightApp    ·    $leftApp ←"
        } else {
            swipeHintText.visibility = View.GONE
        }
    }

    private fun updateClockAndDate() {
        val now = Date()
        clockText.text = timeFormat.format(now)
        val rawDate = dateFormat.format(now).replaceFirstChar { it.lowercase(Locale("uk", "UA")) }
        val bm = getSystemService(BatteryManager::class.java)
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        dateText.text = if (battery in 1..100) "$rawDate  ·  $battery%" else rawDate
    }

    private fun updateDefaultLauncherBanner() {
        val isDef = isDefaultLauncher()
        defaultHomeBanner.visibility = if (isDef) View.GONE else View.VISIBLE
    }

    private fun isDefaultLauncher(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return rm.isRoleHeld(RoleManager.ROLE_HOME)
            }
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return res?.activityInfo?.packageName == packageName
    }

    private fun promptSetDefaultLauncher() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 1001)
                    return
                }
            }
        } catch (_: Throwable) {}

        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Throwable) {}
        }
    }

    private fun filterDrawerApps(query: String, allowAutoLaunch: Boolean) {
        val hidden = prefs.getHiddenKeys()
        val q = query.trim()

        val filtered = if (q.isEmpty()) {
            allApps.filter { !hidden.contains(it.componentKey) }
        } else {
            // При прямому пошуку показуємо навіть приховані, якщо збіг точний, або спершу неприховані
            val base = allApps.filter { !hidden.contains(it.componentKey) && SearchNormalizer.matches(it, q) }
            val listToSort = if (base.isNotEmpty()) base else allApps.filter { SearchNormalizer.matches(it, q) }
            listToSort.sortedBy { SearchNormalizer.scoreMatch(it, q) }
        }

        visibleDrawerApps = filtered
        drawerAdapter.notifyDataSetChanged()

        emptySearchText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        alphabetSidebar.visibility = if (q.isEmpty() && filtered.size > 8) View.VISIBLE else View.GONE

        if (q.isEmpty()) {
            val letters = filtered.map { it.firstLetter }.distinct()
            alphabetSidebar.setAvailableLetters(letters)
        }

        // Автозапуск, якщо при наборі в пошуку залишився рівно 1 збіг
        if (allowAutoLaunch && prefs.autoLaunchSingle && q.isNotEmpty() && filtered.size == 1 && isDrawerOpen) {
            val singleApp = filtered[0]
            searchInput.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            launchFromDrawer(singleApp)
        }
    }

    private fun launchFromDrawer(app: AppEntry) {
        hideKeyboard()
        AppRepository.launchApp(this, app)
        closeDrawer(animated = false)
    }

    private fun openDrawer(showKeyboard: Boolean = true) {
        if (isDrawerOpen) return
        isDrawerOpen = true

        suppressAutoLaunch = true
        searchInput.setText("")
        lastSearchLength = 0
        suppressAutoLaunch = false
        filterDrawerApps("", allowAutoLaunch = false)
        appsListView.setSelection(0)

        drawerContainer.visibility = View.VISIBLE
        drawerAnimator?.cancel()
        val startProgress = scrimView.drawerProgress
        drawerAnimator = ValueAnimator.ofFloat(startProgress, 1f).apply {
            duration = 230L
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                applyDrawerTransition(p)
            }
            start()
        }

        if (showKeyboard) {
            searchInput.requestFocus()
            searchInput.postDelayed({
                if (isDrawerOpen) {
                    showKeyboard(searchInput)
                }
            }, 60L)
        }
    }

    private fun closeDrawer(animated: Boolean = true) {
        if (!isDrawerOpen && scrimView.drawerProgress == 0f) return
        isDrawerOpen = false
        hideKeyboard()

        drawerAnimator?.cancel()
        if (!animated) {
            applyDrawerTransition(0f)
            drawerContainer.visibility = View.GONE
            return
        }

        val startProgress = scrimView.drawerProgress
        drawerAnimator = ValueAnimator.ofFloat(startProgress, 0f).apply {
            duration = 200L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                applyDrawerTransition(p)
                if (p <= 0.001f) {
                    drawerContainer.visibility = View.GONE
                }
            }
            start()
        }
    }

    private fun applyDrawerTransition(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        scrimView.drawerProgress = p

        // Механіка 60fps-play-swipe: головний екран плавно від'їжджає вглиб
        val scale = 1f - 0.06f * p
        homeContainer.scaleX = scale
        homeContainer.scaleY = scale
        homeContainer.alpha = (1f - p * 1.25f).coerceIn(0f, 1f)

        drawerContainer.alpha = p
        drawerContainer.translationY = (1f - p) * dpF(72f)
    }

    private fun showKeyboard(target: View) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(WindowInsets.Type.ime())
            }
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Throwable) {}
    }

    private fun hideKeyboard() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.hide(WindowInsets.Type.ime())
            }
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(rootLayout.windowToken, 0)
        } catch (_: Throwable) {}
    }

    // --- Контекстні меню та Налаштування ---

    private fun showHomeAppContextMenu(app: AppEntry) {
        val favKeys = prefs.getFavoriteKeys()
        val idx = favKeys.indexOf(app.componentKey)

        val options = ArrayList<Pair<String, () -> Unit>>()
        options.add("Перейменувати" to { showRenameDialog(app) })
        options.add("Замінити програму" to { showReplaceFavoriteDialog(app) })
        if (idx > 0) {
            options.add("Перемістити вище  ↑" to {
                prefs.moveFavorite(app.componentKey, -1)
                renderHomeFavorites()
            })
        }
        if (idx in 0 until favKeys.lastIndex) {
            options.add("Перемістити нижче  ↓" to {
                prefs.moveFavorite(app.componentKey, +1)
                renderHomeFavorites()
            })
        }
        if (favKeys.size < Prefs.MAX_FAVORITES) {
            options.add("Додати ще програму на головний" to { showAddFavoriteDialog() })
        }
        options.add("Прибрати з головного екрана" to {
            prefs.removeFavorite(app.componentKey)
            renderHomeFavorites()
        })
        options.add("Про програму (системні налаштування)" to {
            AppRepository.openAppInfo(this, app)
        })

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.displayLabel)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second.invoke()
            }
            .show()
    }

    private fun showDrawerAppContextMenu(app: AppEntry) {
        val isFav = prefs.getFavoriteKeys().contains(app.componentKey)
        val isHidden = prefs.getHiddenKeys().contains(app.componentKey)

        val options = ArrayList<Pair<String, () -> Unit>>()
        if (isFav) {
            options.add("Прибрати з головного екрана" to {
                prefs.removeFavorite(app.componentKey)
                renderHomeFavorites()
            })
        } else if (prefs.getFavoriteKeys().size < Prefs.MAX_FAVORITES) {
            options.add("Додати на головний екран" to {
                prefs.addFavorite(app.componentKey)
                renderHomeFavorites()
            })
        }
        options.add("Перейменувати" to { showRenameDialog(app) })
        options.add(
            (if (isHidden) "Повернути в загальний список" else "Приховати зі списку") to {
                prefs.setHidden(app.componentKey, !isHidden)
                reloadApps()
            }
        )
        options.add("Призначити на свайп вправо (→)" to {
            prefs.swipeRightKey = app.componentKey
            renderHomeFavorites()
        })
        options.add("Призначити на свайп вліво (←)" to {
            prefs.swipeLeftKey = app.componentKey
            renderHomeFavorites()
        })
        options.add("Про програму / Видалити" to {
            AppRepository.openAppInfo(this, app)
        })

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.displayLabel)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second.invoke()
            }
            .show()
    }

    private fun showRenameDialog(app: AppEntry) {
        val input = EditText(this).apply {
            setText(app.displayLabel)
            hint = app.originalLabel
            setSelection(text.length)
            isSingleLine = true
            setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Перейменувати «${app.originalLabel}»")
            .setView(input)
            .setPositiveButton("Зберегти") { _, _ ->
                val newName = input.text?.toString()?.trim()
                if (newName.isNullOrEmpty() || newName == app.originalLabel) {
                    prefs.setCustomLabel(app.componentKey, null)
                } else {
                    prefs.setCustomLabel(app.componentKey, newName)
                }
                reloadApps()
            }
            .setNeutralButton("Скинути") { _, _ ->
                prefs.setCustomLabel(app.componentKey, null)
                reloadApps()
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun showAddFavoriteDialog() {
        val currentFavs = prefs.getFavoriteKeys().toSet()
        val candidates = allApps.filter { !currentFavs.contains(it.componentKey) }
        if (candidates.isEmpty()) return

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Додати на головний екран (${currentFavs.size}/${Prefs.MAX_FAVORITES})")
            .setItems(candidates.map { it.displayLabel }.toTypedArray()) { _, which ->
                prefs.addFavorite(candidates[which].componentKey)
                renderHomeFavorites()
            }
            .show()
    }

    private fun showReplaceFavoriteDialog(oldApp: AppEntry) {
        val candidates = allApps.filter { it.componentKey != oldApp.componentKey }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Замінити «${oldApp.displayLabel}» на:")
            .setItems(candidates.map { it.displayLabel }.toTypedArray()) { _, which ->
                prefs.replaceFavorite(oldApp.componentKey, candidates[which].componentKey)
                renderHomeFavorites()
            }
            .show()
    }

    private fun showPickAppDialog(title: String, allowSystemDefault: Boolean = false, onPicked: (AppEntry?) -> Unit) {
        val names = ArrayList<String>()
        if (allowSystemDefault) {
            names.add("Системний за замовчуванням")
        }
        names.addAll(allApps.map { it.displayLabel })

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setItems(names.toTypedArray()) { _, which ->
                if (allowSystemDefault) {
                    if (which == 0) onPicked(null) else onPicked(allApps[which - 1])
                } else {
                    onPicked(allApps[which])
                }
            }
            .show()
    }

    private fun showLauncherSettingsDialog() {
        val scrimLabel = when (prefs.scrimMode) {
            Prefs.SCRIM_LIGHT -> "Легке (30%)"
            Prefs.SCRIM_DEEP -> "Глибоке (75%)"
            Prefs.SCRIM_AMOLED -> "Чистий AMOLED (#000)"
            else -> "Середнє (50%)"
        }
        val alignLabel = when (prefs.alignment) {
            Prefs.ALIGN_CENTER -> "По центру"
            Prefs.ALIGN_RIGHT -> "Праворуч (під праву руку)"
            else -> "Ліворуч"
        }
        val rightAppLabel = findAppByKey(prefs.swipeRightKey)?.displayLabel ?: "Не вибрано"
        val leftAppLabel = findAppByKey(prefs.swipeLeftKey)?.displayLabel ?: "Не вибрано"
        val clockAppLabel = findAppByKey(prefs.clockTapKey)?.displayLabel ?: "Будильник (системний)"
        val dateAppLabel = findAppByKey(prefs.dateTapKey)?.displayLabel ?: "Календар (системний)"
        val favCount = prefs.getFavoriteKeys().size
        val hiddenCount = prefs.getHiddenKeys().size

        val items = ArrayList<Pair<String, () -> Unit>>()

        if (!isDefaultLauncher()) {
            items.add("★ Зробити основним ланчером" to { promptSetDefaultLauncher() })
        }

        if (favCount < Prefs.MAX_FAVORITES) {
            items.add("+ Додати програму на головний ($favCount/${Prefs.MAX_FAVORITES})" to {
                showAddFavoriteDialog()
            })
        }

        items.add("Затемнення шпалер: $scrimLabel" to {
            prefs.scrimMode = (prefs.scrimMode + 1) % 4
            scrimView.scrimMode = prefs.scrimMode
        })

        items.add("Вирівнювання тексту: $alignLabel" to {
            prefs.alignment = (prefs.alignment + 1) % 3
            renderHomeFavorites()
        })

        items.add("Свайп вправо (→): $rightAppLabel" to {
            showPickAppDialog("Програма на свайп вправо (→)") { picked ->
                if (picked != null) {
                    prefs.swipeRightKey = picked.componentKey
                    renderHomeFavorites()
                }
            }
        })

        items.add("Свайп вліво (←): $leftAppLabel" to {
            showPickAppDialog("Програма на свайп вліво (←)") { picked ->
                if (picked != null) {
                    prefs.swipeLeftKey = picked.componentKey
                    renderHomeFavorites()
                }
            }
        })

        items.add("Тап на годинник: $clockAppLabel" to {
            showPickAppDialog("Тап на годинник відкриває:", allowSystemDefault = true) { picked ->
                prefs.clockTapKey = picked?.componentKey
            }
        })

        items.add("Тап на дату: $dateAppLabel" to {
            showPickAppDialog("Тап на дату відкриває:", allowSystemDefault = true) { picked ->
                prefs.dateTapKey = picked?.componentKey
            }
        })

        items.add("Автозапуск при 1 збігу: ${if (prefs.autoLaunchSingle) "Увімкнено" else "Вимкнено"}" to {
            prefs.autoLaunchSingle = !prefs.autoLaunchSingle
        })

        items.add("Підказка свайпів унизу: ${if (prefs.showSwipeHint) "Увімкнено" else "Вимкнено"}" to {
            prefs.showSwipeHint = !prefs.showSwipeHint
            renderHomeFavorites()
        })

        if (hiddenCount > 0) {
            items.add("Приховані програми ($hiddenCount)" to {
                showHiddenAppsDialog()
            })
        }

        val pendingUpdate = Updater.cachedUpdate(this)
        if (pendingUpdate != null) {
            items.add(0, "⬆ Оновити до ${pendingUpdate.version}" to { Updater.offer(this, pendingUpdate) })
        }

        items.add("Перемкнути інший ланчер (системні налаштування)" to {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (_: Throwable) {}
        })

        items.add("Перевірити оновлення (версія ${Updater.currentVersion(this)})" to { checkUpdates(manual = true) })

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Чистий · налаштування")
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                items[which].second.invoke()
            }
            .show()
    }

    private fun showHiddenAppsDialog() {
        val hiddenKeys = prefs.getHiddenKeys()
        val hiddenApps = allApps.filter { hiddenKeys.contains(it.componentKey) }
        if (hiddenApps.isEmpty()) return

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Натисни, щоб повернути в список:")
            .setItems(hiddenApps.map { it.displayLabel }.toTypedArray()) { _, which ->
                prefs.setHidden(hiddenApps[which].componentKey, false)
                reloadApps()
            }
            .setNeutralButton("Повернути всі") { _, _ ->
                for (a in hiddenApps) prefs.setHidden(a.componentKey, false)
                reloadApps()
            }
            .setNegativeButton("Закрити", null)
            .show()
    }

    // --- Адаптер списку всіх програм ---

    private inner class DrawerAppAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleDrawerApps.size
        override fun getItem(position: Int): AppEntry = visibleDrawerApps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), dp(13f), dp(12f), dp(13f))

                val title = TextView(this@MainActivity).apply {
                    id = android.R.id.text1
                    setTextColor(Color.argb(242, 248, 250, 255))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                val badge = TextView(this@MainActivity).apply {
                    id = android.R.id.text2
                    setTextColor(Color.argb(130, 255, 255, 255))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(8f), 0, 0, 0)
                }
                addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            val app = visibleDrawerApps[position]
            val titleView = row.findViewById<TextView>(android.R.id.text1)
            val badgeView = row.findViewById<TextView>(android.R.id.text2)

            titleView.text = app.displayLabel

            val hasQuery = !searchInput.text.isNullOrBlank()
            if (hasQuery && position == 0) {
                badgeView.visibility = View.VISIBLE
                badgeView.text = "↵"
            } else if (!hasQuery && (position == 0 || visibleDrawerApps[position - 1].firstLetter != app.firstLetter)) {
                badgeView.visibility = View.VISIBLE
                badgeView.text = app.firstLetter
            } else {
                badgeView.visibility = View.GONE
            }

            row.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                launchFromDrawer(app)
            }
            row.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                hideKeyboard()
                showDrawerAppContextMenu(app)
                true
            }

            return row
        }
    }

    // --- Кореневий Layout із перехопленням жестів на головному екрані ---

    private inner class GestureRootLayout(context: Context) : FrameLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var isTrackingHomeSwipe = false
        private var swipeAxis = 0 // 1 = vertical, 2 = horizontal
        private var thresholdHapticFired = false
        private var velocityTracker: VelocityTracker? = null
        private var longPressPending = false

        private val longPressRunnable = Runnable {
            if (longPressPending && !isDrawerOpen && !isTrackingHomeSwipe) {
                longPressPending = false
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showLauncherSettingsDialog()
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (isDrawerOpen) return super.onInterceptTouchEvent(ev)

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    isTrackingHomeSwipe = false
                    swipeAxis = 0
                    thresholdHapticFired = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
                    longPressPending = true
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(ev)
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        longPressPending = false
                        removeCallbacks(longPressRunnable)
                        isTrackingHomeSwipe = true
                        swipeAxis = if (abs(dy) >= abs(dx) * 0.9f) 1 else 2
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressPending = false
                    removeCallbacks(longPressRunnable)
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (isDrawerOpen) return super.onTouchEvent(ev)
            velocityTracker?.addMovement(ev)

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!isTrackingHomeSwipe && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        longPressPending = false
                        removeCallbacks(longPressRunnable)
                        isTrackingHomeSwipe = true
                        swipeAxis = if (abs(dy) >= abs(dx) * 0.9f) 1 else 2
                    }

                    if (isTrackingHomeSwipe) {
                        if (swipeAxis == 1) {
                            // Вертикальний жест: вгору -> відкриваємо шторку програм наживо за пальцем
                            if (dy < 0) {
                                val progress = (-dy / dpF(210f)).coerceIn(0f, 1f)
                                drawerContainer.visibility = View.VISIBLE
                                applyDrawerTransition(progress)
                            }
                        } else if (swipeAxis == 2) {
                            // Горизонтальний жест: вліво або вправо
                            val targetApp = if (dx > 0) {
                                findAppByKey(prefs.swipeRightKey)
                            } else {
                                findAppByKey(prefs.swipeLeftKey)
                            }
                            scrimView.activeSwipeLabel = targetApp?.displayLabel ?: ""
                            val progress = (dx / dpF(96f)).coerceIn(-1.25f, 1.25f)
                            scrimView.horizontalSwipeProgress = progress
                            homeContainer.translationX = dx * 0.22f

                            if (abs(progress) >= 1f && !thresholdHapticFired) {
                                thresholdHapticFired = true
                                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            } else if (abs(progress) < 0.9f && thresholdHapticFired) {
                                thresholdHapticFired = false
                            }
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressPending = false
                    removeCallbacks(longPressRunnable)

                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val vy = velocityTracker?.yVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null

                    if (isTrackingHomeSwipe) {
                        if (swipeAxis == 1) {
                            if (dy < -dpF(56f) || vy < -650f) {
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                openDrawer(showKeyboard = true)
                            } else if (dy > dpF(56f) || vy > 650f) {
                                // Свайп униз будь-де -> шторка сповіщень!
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                applyDrawerTransition(0f)
                                drawerContainer.visibility = View.GONE
                                AppRepository.expandNotificationShade(this@MainActivity)
                            } else {
                                applyDrawerTransition(0f)
                                drawerContainer.visibility = View.GONE
                            }
                        } else if (swipeAxis == 2) {
                            val triggeredRight = dx > dpF(92f) || (dx > dpF(45f) && vx > 800f)
                            val triggeredLeft = dx < -dpF(92f) || (dx < -dpF(45f) && vx < -800f)

                            homeContainer.animate()
                                .translationX(0f)
                                .setInterpolator(OvershootInterpolator(1.2f))
                                .setDuration(200L)
                                .start()
                            scrimView.horizontalSwipeProgress = 0f

                            if (triggeredRight) {
                                val app = findAppByKey(prefs.swipeRightKey)
                                if (app != null) AppRepository.launchApp(this@MainActivity, app)
                            } else if (triggeredLeft) {
                                val app = findAppByKey(prefs.swipeLeftKey)
                                if (app != null) AppRepository.launchApp(this@MainActivity, app)
                            }
                        }
                    }
                    isTrackingHomeSwipe = false
                    swipeAxis = 0
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }
    }
}
