package com.atxx.jhmcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

internal fun Server.registerExportTools(holder: SessionHolder) {
    addTool(
        name = "export_sources",
        description = "Export decompiled Java sources in the background to out_dir/sources, optionally saving resources to out_dir/resources. Returns progress after waiting up to wait_seconds; poll export_status to continue.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("out_dir") { put("type", "string"); put("description", "Absolute output directory; created if missing") }
                putJsonObject("package") { put("type", "string"); put("description", "Optional current class/package FQN prefix; matches equal or dot-suffix") }
                putJsonObject("include_resources") { put("type", "boolean"); put("default", false) }
                putJsonObject("wait_seconds") { put("type", "integer"); put("default", 30); put("maximum", 300) }
            },
            required = listOf("out_dir"),
        )
    ) { req: CallToolRequest ->
        val session = holder.current() ?: return@addTool noApkLoaded()
        val outDir = req.arguments.strArg("out_dir") ?: return@addTool errorResult("out_dir is required")
        runCatching {
            session.sourceExporter.start(
                File(outDir),
                req.arguments.strArg("package")?.takeIf { it.isNotBlank() },
                req.arguments.boolArg("include_resources") ?: false,
                req.arguments.intArg("wait_seconds") ?: 30,
            )
        }.fold(
            onSuccess = { exportResult(it) },
            onFailure = { errorResult(it.message ?: "Export failed") },
        )
    }

    addTool(
        name = "export_status",
        description = "Return the current or last export's progress, optionally waiting for completion or cancelling a running export.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("wait_seconds") { put("type", "integer"); put("default", 0); put("maximum", 300) }
                putJsonObject("cancel") { put("type", "boolean"); put("default", false) }
            }
        )
    ) { req: CallToolRequest ->
        val session = holder.current() ?: return@addTool noApkLoaded()
        runCatching {
            session.sourceExporter.status(req.arguments.intArg("wait_seconds") ?: 0, req.arguments.boolArg("cancel") ?: false)
        }.fold(
            onSuccess = { exportResult(it) },
            onFailure = { errorResult(it.message ?: "Export status failed") },
        )
    }
}

private fun exportResult(status: SourceExporter.Status) = okJson(buildJsonObject {
    put("state", status.state)
    put("out_dir", status.outDir)
    put("total_classes", status.totalClasses)
    put("done_classes", status.doneClasses)
    put("failed_count", status.failedCount)
    put("timed_out_count", status.timedOutCount)
    put("failed", buildJsonArray { status.failed.forEach { add(it) } })
    put("timed_out", buildJsonArray { status.timedOut.forEach { add(it) } })
    put("renamed_files", status.renamedFiles)
    status.resourcesWritten?.let { put("resources_written", it) }
    put("elapsed_ms", status.elapsedMs)
})
