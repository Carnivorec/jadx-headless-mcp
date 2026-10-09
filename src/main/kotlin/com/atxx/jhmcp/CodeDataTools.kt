package com.atxx.jhmcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import jadx.api.JavaClass
import jadx.api.JavaMethod
import jadx.api.JavaNode
import jadx.api.data.CommentStyle
import jadx.api.data.IJavaNodeRef
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path

internal fun Server.registerCodeDataTools(holder: SessionHolder) {
    addTool(
        name = "rename_class",
        description = "Rename a class (short name or full name for top-level classes); empty new_name resets it. Names apply to all later outputs and are saved into the .jadx project when one is set.",
        inputSchema = codeDataSchema(listOf("class_name", "new_name"))
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val (cls, error) = s.resolveClassArg(fqn)
        if (cls == null) return@addTool error!!
        val name = req.arguments.strArg("new_name") ?: return@addTool errorResult("new_name is required")
        codeDataResult { s.renameClass(cls, name) }
    }

    addTool(
        name = "rename_method",
        description = "Rename a method, using short_id to select an overload; empty new_name resets it and constructors cannot be renamed. Names apply to all later outputs and are saved into the .jadx project when one is set.",
        inputSchema = codeDataSchema(listOf("class_name", "method_name", "new_name"), listOf("short_id"))
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val (cls, error) = s.resolveClassArg(fqn)
        if (cls == null) return@addTool error!!
        val methodName = req.arguments.strArg("method_name") ?: return@addTool errorResult("method_name is required")
        val name = req.arguments.strArg("new_name") ?: return@addTool errorResult("new_name is required")
        val (method, methodError) = s.codeDataMethod(cls, methodName, req.arguments.strArg("short_id"))
        if (method == null) return@addTool methodError!!
        codeDataResult { s.renameMethod(method, name) }
    }

    addTool(
        name = "rename_field",
        description = "Rename a field; empty new_name resets it. Names apply to all later outputs and are saved into the .jadx project when one is set.",
        inputSchema = codeDataSchema(listOf("class_name", "field_name", "new_name"))
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val (cls, error) = s.resolveClassArg(fqn)
        if (cls == null) return@addTool error!!
        val fieldName = req.arguments.strArg("field_name") ?: return@addTool errorResult("field_name is required")
        val field = s.findField(cls.fullName, fieldName) ?: return@addTool errorResult("field not found: $fqn.$fieldName")
        val name = req.arguments.strArg("new_name") ?: return@addTool errorResult("new_name is required")
        codeDataResult { s.renameField(field, name) }
    }

    addTool(
        name = "rename_package",
        description = "Rename a package addressed by its original or current full name; empty new_name resets it. Names apply to all later outputs and are saved into the .jadx project when one is set.",
        inputSchema = codeDataSchema(listOf("package_name", "new_name"))
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val pkg = req.arguments.strArg("package_name") ?: return@addTool errorResult("package_name is required")
        val name = req.arguments.strArg("new_name") ?: return@addTool errorResult("new_name is required")
        codeDataResult { s.renamePackage(pkg, name) }
    }

    addTool(
        name = "set_comment",
        description = "Set a class, method or field declaration comment (default style: line); empty comment removes it. Comments appear in later source outputs and are saved into the .jadx project when one is set.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                listOf("class_name", "method_name", "short_id", "field_name", "comment").forEach { name ->
                    putJsonObject(name) { put("type", "string") }
                }
                putJsonObject("style") {
                    put("type", "string")
                    put("enum", buildJsonArray { CommentStyle.entries.forEach { add(it.name.lowercase()) } })
                    put("default", "line")
                }
            },
            required = listOf("class_name", "comment")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val (cls, error) = s.resolveClassArg(fqn)
        if (cls == null) return@addTool error!!
        val methodName = req.arguments.strArg("method_name")
        val fieldName = req.arguments.strArg("field_name")
        if (methodName != null && fieldName != null) return@addTool errorResult("Choose method_name or field_name, not both")
        val node: JavaNode = when {
            methodName != null -> {
                val (method, methodError) = s.codeDataMethod(cls, methodName, req.arguments.strArg("short_id"))
                method ?: return@addTool methodError!!
            }
            fieldName != null -> s.findField(cls.fullName, fieldName)
                ?: return@addTool errorResult("field not found: $fqn.$fieldName")
            else -> cls
        }
        val comment = req.arguments.strArg("comment") ?: return@addTool errorResult("comment is required")
        val styleName = req.arguments.strArg("style") ?: "line"
        val style = CommentStyle.entries.firstOrNull { it.name.lowercase() == styleName }
            ?: return@addTool errorResult("Invalid comment style '$styleName': expected line|block|block_condensed|javadoc|javadoc_condensed")
        codeDataResult { s.setComment(node, comment, style) }
    }

    addTool(
        name = "get_code_data",
        description = "List the session's user renames and comments with original node references and its .jadx project path, when set.",
        inputSchema = codeDataSchema(emptyList())
    ) { _: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val data = s.codeDataSnapshot()
        okJson(buildJsonObject {
            s.projectPath?.let { put("project_path", it.toString()) }
            put("renames", buildJsonArray {
                data.renames.forEach { rename -> addJsonObject {
                    putNodeRef(rename.nodeRef)
                    put("new_name", rename.newName)
                } }
            })
            put("comments", buildJsonArray {
                data.comments.forEach { comment -> addJsonObject {
                    putNodeRef(comment.nodeRef)
                    put("comment", comment.comment)
                    put("style", comment.style.name.lowercase())
                } }
            })
        })
    }

    addTool(
        name = "save_project",
        description = "Save inputs, renames and comments to a jadx-gui compatible .jadx project, preserving other existing project settings. An optional path becomes the session project path for all later changes; omit it to save to the current path.",
        inputSchema = codeDataSchema(emptyList(), listOf("path"))
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        runCatching { s.saveProject(req.arguments.strArg("path")?.let { Path.of(it) }) }.fold(
            onSuccess = { path -> okJson(buildJsonObject { put("project_path", path.toString()); put("project_saved", true) }) },
            onFailure = { errorResult(it.message ?: "Project save failed") }
        )
    }
}

private fun codeDataSchema(required: List<String>, optional: List<String> = emptyList()): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        (required + optional).forEach { name -> putJsonObject(name) { put("type", "string") } }
    },
    required = required,
)

private fun codeDataResult(block: () -> JadxSession.CodeDataChange): CallToolResult = runCatching(block).fold(
    onSuccess = { change -> okJson(buildJsonObject {
        put("old_name", change.oldName)
        put("new_name", change.newName)
        put("project_saved", change.projectSaved)
    }) },
    onFailure = { errorResult(it.message ?: "Code data change failed") }
)

private fun JadxSession.codeDataMethod(cls: JavaClass, name: String, shortId: String?): Pair<JavaMethod?, CallToolResult?> {
    val methods = findMethods(cls.fullName, name)
    if (methods.isEmpty()) return null to methodNotFound(this, cls.fullName, name)
    if (shortId != null) {
        val method = methods.firstOrNull { it.methodNode.methodInfo.shortId == shortId }
        return if (method != null) method to null
        else null to errorResult("short_id not found: $shortId; available: ${methods.joinToString { it.methodNode.methodInfo.shortId }}")
    }
    if (methods.size > 1) return null to errorResult(
        "Method is overloaded; provide short_id: ${methods.joinToString { it.methodNode.methodInfo.shortId }}"
    )
    return methods.single() to null
}

private fun JsonObjectBuilder.putNodeRef(ref: IJavaNodeRef) {
    put("type", ref.type.name.lowercase())
    put("declaring_class", ref.declaringClass)
    put("short_id", ref.shortId)
}
