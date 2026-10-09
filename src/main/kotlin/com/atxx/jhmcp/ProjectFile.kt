package com.atxx.jhmcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import jadx.api.data.ICodeComment
import jadx.api.data.ICodeRename
import jadx.api.data.IJavaCodeRef
import jadx.api.data.IJavaNodeRef
import jadx.api.data.impl.JadxCodeComment
import jadx.api.data.impl.JadxCodeData
import jadx.api.data.impl.JadxCodeRef
import jadx.api.data.impl.JadxCodeRename
import jadx.api.data.impl.JadxNodeRef
import jadx.core.utils.GsonUtils
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Collections

/**
 * jadx-gui compatible `.jadx` project file (ProjectData, projectVersion 2). Only `files` and
 * `codeData` are owned here; every other key (open tabs, tree state, cache dir, …) written by
 * jadx-gui is preserved on save.
 */
data class ProjectFile(
    val files: List<Path>,
    val codeData: JadxCodeData,
    val path: Path,
    val loaded: Boolean = false,
) {
    companion object {
        // Same builder and interface adapters as jadx-gui's JadxProject.buildGson.
        private val gson = GsonUtils.defaultGsonBuilder()
            .registerTypeAdapter(ICodeComment::class.java, GsonUtils.interfaceReplace(JadxCodeComment::class.java))
            .registerTypeAdapter(ICodeRename::class.java, GsonUtils.interfaceReplace(JadxCodeRename::class.java))
            .registerTypeAdapter(IJavaNodeRef::class.java, GsonUtils.interfaceReplace(JadxNodeRef::class.java))
            .registerTypeAdapter(IJavaCodeRef::class.java, GsonUtils.interfaceReplace(JadxCodeRef::class.java))
            .create()

        fun read(path: Path): ProjectFile {
            val absolute = path.toAbsolutePath().normalize()
            val root = readRoot(absolute)
            val files = root.getAsJsonArray("files")?.map { absolute.parent.resolve(it.asString).normalize() }.orEmpty()
            val codeData = root.get("codeData")?.let { gson.fromJson(it, JadxCodeData::class.java) } ?: JadxCodeData()
            return ProjectFile(files, codeData, absolute, loaded = true)
        }

        fun write(path: Path, files: List<Path>, codeData: JadxCodeData) {
            val absolute = path.toAbsolutePath().normalize()
            val root = if (Files.exists(absolute)) readRoot(absolute) else JsonObject().apply { addProperty("projectVersion", 2) }
            root.add("files", JsonArray().apply {
                files.forEach { file ->
                    // Relative to the project dir like jadx-gui's RelativePathTypeAdapter; absolute across drives.
                    val input = file.toAbsolutePath().normalize()
                    add(runCatching { absolute.parent.relativize(input) }.getOrDefault(input).toString())
                }
            })
            val sorted = JadxCodeData().apply {
                renames = codeData.renames.toMutableList().also { Collections.sort(it) }
                comments = codeData.comments
            }
            root.add("codeData", gson.toJsonTree(sorted))
            val temp = Files.createTempFile(absolute.parent, ".jhmcp-", ".tmp")
            try {
                Files.writeString(temp, gson.toJson(root), Charsets.UTF_8)
                Files.move(temp, absolute, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
        }

        private fun readRoot(path: Path): JsonObject =
            JsonParser.parseString(Files.readString(path, Charsets.UTF_8)).asJsonObject

        internal fun copyCodeData(data: JadxCodeData): JadxCodeData =
            gson.fromJson(gson.toJsonTree(data), JadxCodeData::class.java)
    }
}
