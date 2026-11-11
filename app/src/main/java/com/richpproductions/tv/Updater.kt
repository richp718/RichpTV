package com.richpproductions.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Checks GitHub for a newer RichpTV build and installs it. */
object Updater {
    private const val API = "https://api.github.com/repos/richp718/RichpTV/releases/latest"

    data class Release(val build: Int, val apkUrl: String)

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(API)
                .header("User-Agent", "RichpTV-updater")
                .header("Accept", "application/vnd.github+json")
                .build()
            Repo.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val o = JSONObject(r.body?.string() ?: return@withContext null)
                val build = o.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return@withContext null
                val assets = o.optJSONArray("assets") ?: return@withContext null
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk", true))
                        return@withContext Release(build, a.optString("browser_download_url"))
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    fun currentBuild(ctx: Context): Int {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }

    private fun updateDir(ctx: Context): File {
        val base = if (Build.VERSION.SDK_INT >= 24) ctx.cacheDir else (ctx.externalCacheDir ?: ctx.cacheDir)
        return File(base, "updates").apply { mkdirs() }
    }

    suspend fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val f = File(updateDir(ctx), "RichpTV.apk")
        val req = Request.Builder().url(url).header("User-Agent", "RichpTV-updater").build()
        Repo.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
            val body = r.body ?: throw IOException("Download failed")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(f).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; withContext(Dispatchers.Main) { onProgress(pct) } }
                        }
                    }
                }
            }
        }
        f.setReadable(true, false)
        f
    }

    /** True if the installer opened; false if the user first has to allow installs. */
    fun install(act: Activity, f: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            try {
                act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName)))
            } catch (e: Exception) { }
            return false
        }
        val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(act, act.packageName + ".files", f)
                  else Uri.fromFile(f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        act.startActivity(i)
        return true
    }
}
