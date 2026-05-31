package io.github.dovecoteescapee.byedpi.utility

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.github.dovecoteescapee.byedpi.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object UpdateManager {
    // TODO: The user needs to replace this URL with the actual link to their raw update.json file
    private const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/jamesgpt143-arch/FlowVPN/master/update.json"

    fun checkForUpdates(context: Context, showToastOnLatest: Boolean = true) {
        thread {
            try {
                val url = URL(UPDATE_JSON_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val json = JSONObject(response.toString())
                    val latestVersionCode = json.getInt("versionCode")
                    val latestVersionName = json.getString("versionName")
                    val apkUrl = json.getString("apkUrl")
                    val releaseNotes = json.optString("releaseNotes", "Bug fixes and improvements")

                    Handler(Looper.getMainLooper()).post {
                        if (latestVersionCode > BuildConfig.VERSION_CODE) {
                            showUpdateDialog(context, latestVersionName, releaseNotes, apkUrl)
                        } else if (showToastOnLatest) {
                            Toast.makeText(context, "App is already up-to-date!", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    showError(context, "Failed to check for updates: HTTP ${connection.responseCode}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showError(context, "Error checking for updates: ${e.message}")
            }
        }
    }

    private fun showError(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUpdateDialog(context: Context, versionName: String, releaseNotes: String, apkUrl: String) {
        AlertDialog.Builder(context)
            .setTitle("New Update Available! (v$versionName)")
            .setMessage(releaseNotes)
            .setPositiveButton("Update") { _, _ ->
                downloadApk(context, apkUrl, "FlowVPN_v$versionName.apk")
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadApk(context: Context, apkUrl: String, fileName: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Downloading FlowVPN Update")
                .setDescription("Downloading version $fileName")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Toast.makeText(context, "Download started...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to start download: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
