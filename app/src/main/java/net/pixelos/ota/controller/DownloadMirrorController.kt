package net.pixelos.ota.controller

import android.content.Context
import android.util.Log
import net.pixelos.ota.MirrorsDbHelper
import net.pixelos.ota.model.UpdateInfo
import org.jsoup.Jsoup
import java.io.IOException
import java.util.LinkedHashMap
import java.util.regex.Pattern

object DownloadMirrorController {

    private const val TAG = "DownloadMirrorController"

    private val SF_PROJECTS_PATTERN = Pattern.compile("sourceforge\\.net/projects/([^/]+)/files(/.+?)(?:/download)?$")
    private val SF_MIRROR_PATTERN = Pattern.compile("\\.dl\\.sourceforge\\.net/project/([^/]+)(/.+)")

    private var sMirrorLinks: MutableMap<String, String>? = null

    fun setMirror(updateInfo: UpdateInfo, context: Context, mirror: String) {
        val mirrorsDbHelper = MirrorsDbHelper.getInstance(context)
        var mirrorUrl = updateInfo.downloadUrl

        sMirrorLinks?.let { links ->
            if (links.isNotEmpty()) {
                for ((key, value) in links) {
                    if (mirror == key) {
                        mirrorUrl = value
                        mirrorsDbHelper.setMirrorUrl(value, updateInfo.downloadId)
                        break
                    }
                }
            }
        }

        mirrorsDbHelper.setMirrorName(mirror, updateInfo.downloadId)
        Log.d(TAG, "Mirror for: ${updateInfo.name} set to $mirrorUrl")
    }

    fun fetchMirrors(update: UpdateInfo): Map<String, String>? {
        sMirrorLinks = LinkedHashMap()

        val downloadUrl = update.downloadUrl
        if (downloadUrl.isNullOrEmpty()) {
            Log.e(TAG, "Cannot fetch mirrors: download URL is null or empty")
            return null
        }

        var projectName: String? = null
        var filepath: String? = null

        try {
            val projectsMatcher = SF_PROJECTS_PATTERN.matcher(downloadUrl)
            val mirrorMatcher = SF_MIRROR_PATTERN.matcher(downloadUrl)

            if (projectsMatcher.find()) {
                projectName = projectsMatcher.group(1)
                filepath = projectsMatcher.group(2)
            } else if (mirrorMatcher.find()) {
                projectName = mirrorMatcher.group(1)
                filepath = mirrorMatcher.group(2)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse URL: $downloadUrl", e)
            return null
        }

        if (projectName == null || filepath == null) {
            Log.e(TAG, "Could not extract project/filepath from URL: $downloadUrl")
            return null
        }

        Log.d(TAG, "Parsed from URL - Project: $projectName, Filepath: $filepath")

        val mirrorsUrl = "https://sourceforge.net/settings/mirror_choices?projectname=$projectName&filename=$filepath"
        Log.d(TAG, "Fetching mirrors from: $mirrorsUrl")

        val finalProjectName = projectName
        val finalFilepath = filepath
        var fetchException: Exception? = null

        val mirrorFetch = Thread {
            try {
                Log.d(TAG, "Starting mirror fetch request...")
                val doc = Jsoup.connect(mirrorsUrl)
                    .userAgent("Mozilla/5.0")
                    .timeout(15000)
                    .get()
                val links = doc.select("#mirrorList li")

                Log.d(TAG, "Found ${links.size} mirror elements")

                if (links.isEmpty()) {
                    Log.w(TAG, "No mirrors found in response.")
                }

                for (link in links) {
                    val mirrorName = link.attr("id")
                    var mirrorPlace = link.text()
                    if (mirrorName != "autoselect") {
                        try {
                            mirrorPlace = mirrorPlace.substring(
                                mirrorPlace.lastIndexOf("(") + 1,
                                mirrorPlace.lastIndexOf(")")
                            )
                                .split(",", limit = 2)[0]
                                .trim()
                            sMirrorLinks?.put(
                                mirrorPlace,
                                "https://$mirrorName.dl.sourceforge.net/project/$finalProjectName$finalFilepath"
                            )
                            Log.d(TAG, "Mirror: $mirrorName ($mirrorPlace)")
                        } catch (e: StringIndexOutOfBoundsException) {
                            Log.w(TAG, "Failed to parse mirror place for: $mirrorName, text: $mirrorPlace")
                        }
                    }
                }
                Log.d(TAG, "Successfully parsed ${sMirrorLinks?.size} mirrors")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to fetch mirrors: ${e.message}", e)
                fetchException = e
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error fetching mirrors: ${e.message}", e)
                fetchException = e
            }
        }

        return try {
            mirrorFetch.start()
            mirrorFetch.join()

            if (fetchException != null) {
                Log.e(TAG, "Mirror fetch failed with exception: ${fetchException!!.message}")
                return null
            }

            if (sMirrorLinks.isNullOrEmpty()) {
                Log.w(TAG, "No mirrors were found")
                return null
            }

            sMirrorLinks
        } catch (e: InterruptedException) {
            Log.e(TAG, "Mirror fetch thread interrupted!", e)
            null
        }
    }
}
