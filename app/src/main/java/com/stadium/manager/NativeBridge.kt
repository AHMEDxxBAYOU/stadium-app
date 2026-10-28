package com.stadium.manager

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/** Everything the web page can ask the phone to do (window.AndroidNative in JavaScript). */
class NativeBridge(private val act: MainActivity) {

    private val bio by lazy { BiometricHelper(act) }

    /** Sends a JSON result back to the page: window.__nativeCb(id, jsonText). */
    fun callJs(cbId: String, json: String) {
        act.runOnUiThread {
            val js = "try{window.__nativeCb&&window.__nativeCb(" + JSONObject.quote(cbId) + "," + JSONObject.quote(json) + ")}catch(e){}"
            act.webView.evaluateJavascript(js, null)
        }
    }

    @JavascriptInterface fun isNative(): Boolean = true

    @JavascriptInterface fun appVersion(): String = try {
        act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    // ---------------- Fingerprint ----------------
    @JavascriptInterface fun bioStatus(): String = bio.status().toString()

    @JavascriptInterface fun bioEnroll(cbId: String, username: String, password: String) {
        act.runOnUiThread { bio.enroll(username, password) { callJs(cbId, it.toString()) } }
    }

    @JavascriptInterface fun bioLogin(cbId: String) {
        act.runOnUiThread { bio.login { callJs(cbId, it.toString()) } }
    }

    @JavascriptInterface fun bioClear() = bio.clear()

    @JavascriptInterface fun openSecuritySettings() {
        act.runOnUiThread {
            val intents = mutableListOf<Intent>()
            if (Build.VERSION.SDK_INT >= 30) intents.add(Intent(Settings.ACTION_BIOMETRIC_ENROLL))
            if (Build.VERSION.SDK_INT >= 28) intents.add(Intent(Settings.ACTION_FINGERPRINT_ENROLL))
            intents.add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            for (i in intents) {
                try { act.startActivity(i); return@runOnUiThread } catch (e: Exception) { /* try next */ }
            }
        }
    }

    // ---------------- Notifications ----------------
    @JavascriptInterface fun notifPermission(): String = Notifier.permissionState(act)

    @JavascriptInterface fun requestNotifPermission(cbId: String) {
        act.runOnUiThread { act.requestNotificationPermission(cbId) }
    }

    @JavascriptInterface fun openNotificationSettings() {
        act.runOnUiThread {
            try {
                val i = if (Build.VERSION.SDK_INT >= 26)
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, act.packageName)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.packageName))
                act.startActivity(i)
            } catch (e: Exception) {
                act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.packageName)))
            }
        }
    }

    /** channel: bookings | reminders | finance | general */
    @JavascriptInterface fun notify(title: String, body: String, tag: String, channel: String): Boolean =
        Notifier.show(act, if (tag.isEmpty()) System.currentTimeMillis().toInt() else tag.hashCode(), channel, title, body)

    /** JSON array: [{at: epochMillis, title, body, id}] - replaces all previously scheduled reminders. */
    @JavascriptInterface fun scheduleReminders(json: String) = Reminders.schedule(act, json)

    @JavascriptInterface fun setKeepAlive(on: Boolean) {
        act.runOnUiThread { if (on) KeepAliveService.start(act) else KeepAliveService.stop(act, forget = true) }
    }

    @JavascriptInterface fun isKeepAlive(): Boolean = KeepAliveService.isWanted(act)

    // ---------------- Misc ----------------
    @JavascriptInterface fun toast(msg: String) {
        act.runOnUiThread { Toast.makeText(act, msg, Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface fun setDarkBars(dark: Boolean) {
        act.runOnUiThread { act.setBars(dark) }
    }

    @JavascriptInterface fun print() {
        act.runOnUiThread {
            try {
                val pm = act.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val adapter = act.webView.createPrintDocumentAdapter("receipt")
                pm.print("إدارة الملعب", adapter, PrintAttributes.Builder().build())
            } catch (e: Exception) {
                Toast.makeText(act, "تعذّرت الطباعة على هذا الجهاز", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Saves an exported file (PDF / Excel / JSON / CSV) to the phone's Downloads folder and opens it. */
    @JavascriptInterface fun saveFile(name: String, mimeIn: String, b64: String): Boolean {
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
            val ext = safe.substringAfterLast('.', "").lowercase()
            val mime = mimeIn.ifBlank { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream" }
            val uri: Uri
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StadiumManager")
                }
                uri = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert failed")
                act.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            } else {
                val dir = File(act.getExternalFilesDir(null), "exports").apply { mkdirs() }
                val f = File(dir, safe)
                f.writeBytes(bytes)
                uri = FileProvider.getUriForFile(act, act.packageName + ".files", f)
            }
            act.runOnUiThread {
                Toast.makeText(act, "تم حفظ الملف في التنزيلات: $safe", Toast.LENGTH_LONG).show()
                try {
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    act.startActivity(Intent.createChooser(view, "فتح الملف"))
                } catch (e: Exception) { /* saved anyway */ }
            }
            true
        } catch (e: Exception) {
            act.runOnUiThread { Toast.makeText(act, "تعذّر حفظ الملف", Toast.LENGTH_SHORT).show() }
            false
        }
    }
}
