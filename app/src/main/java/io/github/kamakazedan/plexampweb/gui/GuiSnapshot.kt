package io.github.kamakazedan.plexampweb.gui

import org.json.JSONObject

/** One of the player's files, as saved: its contents (by their SHA-256), type, and what the server said of it. */
data class GuiFile(
    val blob: String,
    val type: String,
    val etag: String?,
    val lastModified: String?,
    /** The server's security headers for it (its Content-Security-Policy, say), served with it. */
    val headers: Map<String, String>,
)

/** A saved copy of the player's web app: every file it's made of, at one moment. */
data class GuiSnapshot(
    val id: String,
    val base: String,
    /** When this version was saved (an update that changed nothing keeps it). */
    val savedAt: Long,
    /** The web app's own version (APP_INFO), where it says. */
    val appVersion: String?,
    val files: Map<String, GuiFile>,
) {
    fun sameFilesAs(other: GuiSnapshot): Boolean =
        files.mapValues { it.value.blob } == other.files.mapValues { it.value.blob }

    fun toJson(): String {
        val list = JSONObject()
        for ((path, file) in files) {
            list.put(
                path,
                JSONObject()
                    .put("blob", file.blob)
                    .put("type", file.type)
                    .putOpt("etag", file.etag)
                    .putOpt("lastModified", file.lastModified)
                    .put("headers", JSONObject(file.headers)),
            )
        }
        return JSONObject()
            .put("version", 1)
            .put("id", id)
            .put("base", base)
            .put("savedAt", savedAt)
            .putOpt("appVersion", appVersion)
            .put("files", list)
            .toString()
    }

    companion object {
        fun fromJson(text: String): GuiSnapshot {
            val json = JSONObject(text)
            val list = json.getJSONObject("files")
            val files = LinkedHashMap<String, GuiFile>()
            for (path in list.keys()) {
                val file = list.getJSONObject(path)
                val headers = HashMap<String, String>()
                file.optJSONObject("headers")?.let { h -> for (name in h.keys()) headers[name] = h.getString(name) }
                files[path] = GuiFile(
                    blob = file.getString("blob"),
                    type = file.getString("type"),
                    etag = file.stringOrNull("etag"),
                    lastModified = file.stringOrNull("lastModified"),
                    headers = headers,
                )
            }
            return GuiSnapshot(
                id = json.getString("id"),
                base = json.getString("base"),
                savedAt = json.getLong("savedAt"),
                appVersion = json.stringOrNull("appVersion"),
                files = files,
            )
        }

        private fun JSONObject.stringOrNull(name: String): String? = if (!has(name) || isNull(name)) null else getString(name)
    }
}
