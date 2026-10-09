package com.atxx.jhmcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Lifecycle & introspection tools: status, load_apk, unload_apk. */
internal fun Server.registerSessionTools(holder: SessionHolder) {
    addTool(
        name = "status",
        description = "Report whether an APK is loaded and basic info (path, class count, resource count, load duration).",
        inputSchema = ToolSchema(properties = buildJsonObject {})
    ) { _: CallToolRequest ->
        val snap = holder.snapshot()
        okJson(buildJsonObject {
            put("state", snap.state)
            snap.projectPath?.let { put("project_path", it) }
            put("project_loaded", snap.projectLoaded)
            put("rename_count", snap.renameCount)
            put("comment_count", snap.commentCount)
            snap.apkPath?.let { put("apk_path", it) }
            snap.classCount?.let { put("class_count", it) }
            snap.rawClassCount?.let { put("raw_class_count", it) }
            snap.resourceCount?.let { put("resource_count", it) }
            snap.loadDurationMs?.let { put("load_duration_ms", it) }
            snap.loadedAtEpochMs?.let { put("loaded_at_epoch_ms", it) }
            snap.decompileTimeoutMs?.let { put("decompile_timeout_ms", it) }
            snap.threads?.let { put("threads", it) }
            snap.codeCacheSize?.let { put("code_cache_size", it) }
            snap.resourceMode?.let { put("resources", it) }
            snap.includePackages?.takeIf { it.isNotEmpty() }?.let {
                put("include_packages", buildJsonArray { it.forEach { p -> add(p) } })
            }
            snap.excludePackages?.takeIf { it.isNotEmpty() }?.let {
                put("exclude_packages", buildJsonArray { it.forEach { p -> add(p) } })
            }
        })
    }

    addTool(
        name = "load_apk",
        description = "Load an APK / DEX / JAR or a .jadx project, replacing the current session and indexing its inputs. Reads renames/comments from the explicit project or default .jadx sidecar; include_packages/exclude_packages, threads, code_cache_size and resources override CLI defaults.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("path") { put("type", "string"); put("description", "Absolute path to the APK / DEX / JAR or .jadx project.") }
                putJsonObject("project") { put("type", "string"); put("description", "Optional .jadx path; with an explicit APK only its codeData is loaded. Defaults to <input-name>.jadx unless --no-auto-project is set.") }
                putJsonObject("include_packages") {
                    put("type", "string")
                    put("description", "Comma-separated package/FQN prefixes to KEEP (e.g. com.gcash,com.mynt). Overrides CLI --include-packages for this load.")
                }
                putJsonObject("exclude_packages") {
                    put("type", "string")
                    put("description", "Comma-separated package prefixes to DROP (e.g. androidx,kotlin). Overrides CLI --exclude-packages for this load.")
                }
                putJsonObject("threads") {
                    put("type", "integer")
                    put("description", "jadx pre-decompile workers. 0 = all cores. Omit to keep the server default (min(CPU,4)).")
                }
                putJsonObject("code_cache_size") {
                    put("type", "integer")
                    put("description", "Max decompiled top-level classes kept in RAM (LRU). 0 = unlimited. Omit to keep the server default (64).")
                }
                putJsonObject("resources") {
                    put("type", "string")
                    put("description", "full | lite | none. lite keeps Manifest+strings+arsc; none hides non-manifest resources. jadx 1.5.6 still parses arsc at load.")
                }
            },
            required = listOf("path")
        )
    ) { req: CallToolRequest ->
        val path = req.arguments.strArg("path") ?: return@addTool errorResult("path is required")
        val options = try {
            holder.defaults.withLoadOverrides(
                includePackages = req.arguments.strArg("include_packages"),
                excludePackages = req.arguments.strArg("exclude_packages"),
                threads = req.arguments.intArg("threads"),
                codeCacheSize = req.arguments.intArg("code_cache_size"),
                resourceMode = req.arguments.strArg("resources"),
            )
        } catch (e: IllegalArgumentException) {
            return@addTool errorResult(e.message ?: "invalid load options")
        }
        runCatching { holder.load(path, options, req.arguments.strArg("project")) }.fold(
            onSuccess = { r ->
                okJson(buildJsonObject {
                    put("state", "LOADED")
                    r.projectPath?.let { put("project_path", it) }
                    put("project_loaded", r.projectLoaded)
                    put("rename_count", r.renameCount)
                    put("comment_count", r.commentCount)
                    put("apk_path", r.apkPath)
                    put("class_count", r.classCount)
                    put("raw_class_count", r.rawClassCount)
                    put("resource_count", r.resourceCount)
                    put("load_duration_ms", r.loadDurationMs)
                    put("decompile_timeout_ms", r.decompileTimeoutMs)
                    put("threads", r.threads)
                    put("code_cache_size", r.codeCacheSize)
                    put("resources", r.resourceMode)
                    if (r.includePackages.isNotEmpty()) {
                        put("include_packages", buildJsonArray { r.includePackages.forEach { add(it) } })
                    }
                    if (r.excludePackages.isNotEmpty()) {
                        put("exclude_packages", buildJsonArray { r.excludePackages.forEach { add(it) } })
                    }
                })
            },
            onFailure = { e -> errorResult("load failed: ${e.message}") }
        )
    }

    addTool(
        name = "unload_apk",
        description = "Release the currently-loaded APK and free memory. No-op if nothing is loaded.",
        inputSchema = ToolSchema(properties = buildJsonObject {})
    ) { _: CallToolRequest ->
        val wasLoaded = holder.unload()
        okJson(buildJsonObject {
            put("state", "EMPTY")
            put("was_loaded", wasLoaded)
        })
    }
}
