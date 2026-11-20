package com.slava.launcher

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.view.View
import java.text.Collator
import java.util.Locale

data class AppEntry(
    val packageName: String,
    val className: String,
    val originalLabel: String,
    val customLabel: String?,
    val userHandle: UserHandle,
    val userSerial: Long
) {
    val componentKey: String
        get() = "$packageName/$className#$userSerial"

    val displayLabel: String
        get() = if (!customLabel.isNullOrBlank()) customLabel else originalLabel

    val firstLetter: String
        get() {
            val ch = displayLabel.trim().firstOrNull()?.uppercaseChar() ?: '#'
            return if (ch.isLetter()) ch.toString() else "#"
        }

    val searchBlob: String by lazy {
        val base = "$displayLabel $originalLabel $packageName".lowercase(Locale.ROOT)
        val swapped1 = SearchNormalizer.swapKeyboardLayout(displayLabel.lowercase(Locale.ROOT))
        val swapped2 = SearchNormalizer.swapKeyboardLayout(originalLabel.lowercase(Locale.ROOT))
        val translit = SearchNormalizer.transliterateUkToLat(displayLabel.lowercase(Locale.ROOT))
        "$base $swapped1 $swapped2 $translit"
    }
}

object SearchNormalizer {
    private val enToUk = mapOf(
        'q' to 'й', 'w' to 'ц', 'e' to 'у', 'r' to 'к', 't' to 'е', 'y' to 'н', 'u' to 'г',
        'i' to 'ш', 'o' to 'щ', 'p' to 'з', '[' to 'х', ']' to 'ї', 'a' to 'ф', 's' to 'і',
        'd' to 'в', 'f' to 'а', 'g' to 'п', 'h' to 'р', 'j' to 'о', 'k' to 'л', 'l' to 'д',
        ';' to 'ж', '\'' to 'є', 'z' to 'я', 'x' to 'ч', 'c' to 'с', 'v' to 'м', 'b' to 'и',
        'n' to 'т', 'm' to 'ь', ',' to 'б', '.' to 'ю'
    )
    private val ukToEn = enToUk.entries.associate { (k, v) -> v to k }

    private val ukTranslit = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "h", 'ґ' to "g", 'д' to "d", 'е' to "e",
        'є' to "ye", 'ж' to "zh", 'з' to "z", 'и' to "y", 'і' to "i", 'ї' to "yi", 'й' to "y",
        'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'щ' to "shch", 'ь' to "", 'ю' to "yu", 'я' to "ya"
    )

    fun swapKeyboardLayout(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            sb.append(enToUk[c] ?: ukToEn[c] ?: c)
        }
        return sb.toString()
    }

    fun transliterateUkToLat(input: String): String {
        val sb = StringBuilder(input.length * 2)
        for (c in input) {
            sb.append(ukTranslit[c] ?: c)
        }
        return sb.toString()
    }

    fun matches(app: AppEntry, rawQuery: String): Boolean {
        val q = rawQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return true
        if (app.searchBlob.contains(q)) return true
        val swappedQ = swapKeyboardLayout(q)
        if (app.searchBlob.contains(swappedQ)) return true
        return false
    }

    fun scoreMatch(app: AppEntry, rawQuery: String): Int {
        val q = rawQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return 100
        val label = app.displayLabel.lowercase(Locale.ROOT)
        val swappedQ = swapKeyboardLayout(q)
        return when {
            label == q || label == swappedQ -> 0
            label.startsWith(q) || label.startsWith(swappedQ) -> 1
            app.originalLabel.lowercase(Locale.ROOT).startsWith(q) -> 2
            label.contains(q) || label.contains(swappedQ) -> 3
            else -> 4
        }
    }
}

object AppRepository {
    private val ukCollator: Collator = Collator.getInstance(Locale("uk", "UA")).apply {
        strength = Collator.PRIMARY
    }

    fun loadAllApps(context: Context, prefs: Prefs): List<AppEntry> {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val userManager = context.getSystemService(UserManager::class.java)
        val profiles = userManager?.userProfiles ?: listOf(Process.myUserHandle())
        val myPkg = context.packageName

        val result = ArrayList<AppEntry>(128)
        val seenKeys = HashSet<String>()

        for (profile in profiles) {
            val serial = userManager?.getSerialNumberForUser(profile) ?: 0L
            val activities = try {
                launcherApps.getActivityList(null, profile)
            } catch (_: Throwable) {
                emptyList()
            }
            for (info in activities) {
                val pkg = info.componentName.packageName
                val cls = info.componentName.className
                if (pkg == myPkg) continue
                val key = "$pkg/$cls#$serial"
                if (!seenKeys.add(key)) continue
                val orig = info.label?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: pkg
                val custom = prefs.getCustomLabel(key)
                result.add(
                    AppEntry(
                        packageName = pkg,
                        className = cls,
                        originalLabel = orig,
                        customLabel = custom,
                        userHandle = profile,
                        userSerial = serial
                    )
                )
            }
        }

        result.sortWith { a, b ->
            val aCyr = a.displayLabel.firstOrNull()?.let { it in '\u0400'..'\u04FF' } ?: false
            val bCyr = b.displayLabel.firstOrNull()?.let { it in '\u0400'..'\u04FF' } ?: false
            if (aCyr != bCyr) {
                if (aCyr) -1 else 1
            } else {
                ukCollator.compare(a.displayLabel, b.displayLabel)
            }
        }
        return result
    }

    fun launchApp(context: Context, app: AppEntry, sourceView: View? = null): Boolean {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
        return try {
            val component = ComponentName(app.packageName, app.className)
            val bounds = sourceView?.let {
                val loc = IntArray(2)
                it.getLocationOnScreen(loc)
                android.graphics.Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
            }
            val opts = if (sourceView != null) {
                ActivityOptions.makeScaleUpAnimation(
                    sourceView,
                    sourceView.width / 2,
                    sourceView.height / 2,
                    sourceView.width,
                    sourceView.height
                ).toBundle()
            } else null
            launcherApps.startMainActivity(component, app.userHandle, bounds, opts)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun openAppInfo(context: Context, app: AppEntry) {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        try {
            if (launcherApps != null) {
                launcherApps.startAppDetailsActivity(
                    ComponentName(app.packageName, app.className),
                    app.userHandle,
                    null,
                    null
                )
                return
            }
        } catch (_: Throwable) {}

        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", app.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {}
    }

    fun openClockOrAlarm(context: Context, customApp: AppEntry?) {
        if (customApp != null && launchApp(context, customApp)) return
        // Samsung Clock або стандартний інтент будильника
        val candidates = listOf(
            Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            context.packageManager.getLaunchIntentForPackage("com.sec.android.app.clockpackage"),
            context.packageManager.getLaunchIntentForPackage("com.google.android.deskclock")
        )
        for (intent in candidates) {
            if (intent != null) {
                try {
                    context.startActivity(intent)
                    return
                } catch (_: Throwable) {}
            }
        }
    }

    fun openCalendar(context: Context, customApp: AppEntry?) {
        if (customApp != null && launchApp(context, customApp)) return
        val candidates = listOf(
            context.packageManager.getLaunchIntentForPackage("com.samsung.android.calendar"),
            context.packageManager.getLaunchIntentForPackage("com.google.android.calendar"),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent(Intent.ACTION_VIEW).setData(CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        for (intent in candidates) {
            if (intent != null) {
                try {
                    context.startActivity(intent)
                    return
                } catch (_: Throwable) {}
            }
        }
    }

    fun expandNotificationShade(context: Context) {
        try {
            val service = context.getSystemService("statusbar") ?: return
            val clazz = Class.forName("android.app.StatusBarManager")
            val method = clazz.getMethod("expandNotificationsPanel")
            method.isAccessible = true
            method.invoke(service)
        } catch (_: Throwable) {}
    }
}
