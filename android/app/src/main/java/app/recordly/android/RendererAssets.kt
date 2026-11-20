package app.recordly.android

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Installs the built renderer from APK assets onto disk, with the JS bridge
 * already inlined into `index.html`.
 *
 * Two distinct reasons for the copy:
 *
 *   1. **Seeking.** An AssetManager stream is forward-only, so it cannot answer
 *      a Range request. Video scrubbing needs random access.
 *   2. **Inlining.** The bridge must exist before React mounts. Loading it as
 *      `<script src="/bridge-host/bridge.js">` makes boot depend on two more HTTP
 *      requests; if either fails, `window.electronAPI` is undefined and the
 *      renderer dies with "Cannot read properties of undefined". Inlining the
 *      source into the document removes the request, the 404, and the ordering
 *      question in one step.
 *
 * If the shell cannot be prepared, [install] throws. A loud failure at startup
 * beats a silent white-screen later.
 */
object RendererAssets {
    private const val TAG = "RendererAssets"
    private const val ASSET_ROOT = "renderer"
    private const val STAMP = ".installed"
    /**
     * Where `scripts/build-renderer.mjs` drops the two bridge sources.
     *
     * Deliberately not `__bridge`: AAPT drops asset entries whose names start
     * with an underscore, so that folder never ships and install fails on device
     * with "android.js is missing from the asset copy".
     */
    private const val BRIDGE_DIR = "bridge-host"

    fun install(context: Context): File {
        val target = File(context.filesDir, "renderer")
        val stamp = File(target, STAMP)
        val wanted = installStamp(context)

        if (stamp.isFile && runCatching { stamp.readText().trim() }.getOrNull() == wanted) {
            return target
        }

        Log.i(TAG, "installing renderer $wanted")
        target.deleteRecursively()
        if (!target.mkdirs() && !target.isDirectory) {
            throw IOException("cannot create ${target.path}")
        }

        copyTree(context.assets, ASSET_ROOT, target)
        prepareShell(target)
        stamp.writeText(wanted)
        Log.i(TAG, "renderer installed at ${target.path}")
        return target
    }

    /**
     * Identifies *this installation*, so a rebuild always reinstalls the
     * renderer.
     *
     * `lastUpdateTime` is part of the stamp on purpose: during development the
     * version name and code are unchanged between builds, and stamping only
     * those would keep serving yesterday's renderer after every rebuild — the
     * most confusing failure this class could have.
     */
    @Suppress("DEPRECATION")
    private fun installStamp(context: Context): String {
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        return "${installed?.versionName.orEmpty()}@${installed?.lastUpdateTime ?: 0L}"
    }

    /**
     * Inlines `bridge-host/android.js` and `bridge-host/bridge.js` into index.html.
     *
     * Both are classic scripts placed in `<head>`, so they execute in document
     * order, before the deferred module script that mounts React. `android.js`
     * comes first because `bridge.js` snapshots `window.__recordlyNative` while
     * it parses.
     */
    private fun prepareShell(root: File) {
        val index = File(root, "index.html")
        if (!index.isFile) {
            throw IOException("renderer/index.html is missing after the asset copy")
        }

        val html = index.readText()
        if (html.contains(BRIDGE_MARKER)) return

        val host = readBridgeFile(root, "android.js")
        val bridge = readBridgeFile(root, "bridge.js")

        val inline = buildString {
            append("<script>/* recordly-android-host */")
            append(escape(host))
            append("</script>\n")
            append("<script>").append(BRIDGE_MARKER)
            append(escape(bridge))
            append("</script>\n")
        }

        val headEnd = html.indexOf("</head>", ignoreCase = true)
        if (headEnd < 0) {
            throw IOException("renderer/index.html has no </head>; cannot inject the bridge")
        }

        val prepared = html.substring(0, headEnd) + inline + html.substring(headEnd)
        if (!prepared.contains(BRIDGE_MARKER)) {
            throw IOException("bridge injection produced no marker")
        }

        index.writeText(prepared)
        Log.i(TAG, "bridge inlined: ${host.length + bridge.length} bytes")
    }

    private fun readBridgeFile(root: File, name: String): String {
        val file = File(File(root, BRIDGE_DIR), name)
        if (!file.isFile) {
            throw IOException("renderer/$BRIDGE_DIR/$name is missing from the asset copy")
        }
        return file.readText()
    }

    /** A `</script` inside inlined source would close the element early. */
    private fun escape(source: String): String = source.replace("</script", "<\\/script")

    private fun copyTree(assets: AssetManager, assetPath: String, dir: File) {
        val children = try {
            assets.list(assetPath)
        } catch (error: IOException) {
            Log.w(TAG, "cannot list $assetPath", error)
            null
        } ?: return

        if (children.isEmpty()) {
            // AssetManager reports no children for a file. An empty directory is
            // indistinguishable, so a failed open is logged rather than fatal.
            try {
                assets.open(assetPath).use { input ->
                    dir.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (error: IOException) {
                Log.w(TAG, "cannot copy $assetPath", error)
            }
            return
        }

        dir.mkdirs()
        for (child in children) {
            copyTree(assets, "$assetPath/$child", File(dir, child))
        }
    }
}
