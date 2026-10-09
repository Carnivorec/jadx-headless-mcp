package com.atxx.jhmcp

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

internal fun compileJavaFixture(tempDir: Path, sources: Map<String, String>): Path {
    val sourceDir = Files.createDirectories(tempDir.resolve("sources"))
    val classesDir = Files.createDirectories(tempDir.resolve("classes"))
    val sourceFiles = sources.map { (name, source) ->
        sourceDir.resolve(name).toFile().apply {
            parentFile.mkdirs()
            writeText(source)
        }
    }
    val compiler = ToolProvider.getSystemJavaCompiler()
    compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8).use { fileManager ->
        val units = fileManager.getJavaFileObjectsFromFiles(sourceFiles)
        check(compiler.getTask(null, fileManager, null, listOf("--release", "17", "-d", classesDir.toString()), null, units).call())
    }
    val jarPath = tempDir.resolve("fixture.jar")
    JarOutputStream(Files.newOutputStream(jarPath)).use { output ->
        Files.walk(classesDir).use { paths ->
            paths.filter { Files.isRegularFile(it) }.sorted().forEach { path ->
                output.putNextEntry(JarEntry(classesDir.relativize(path).toString().replace('\\', '/')))
                Files.copy(path, output)
                output.closeEntry()
            }
        }
    }
    return jarPath
}
