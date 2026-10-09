package com.atxx.jhmcp

import jadx.api.data.CommentStyle
import jadx.api.data.impl.JadxCodeData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CodeDataTest {
    @TempDir
    lateinit var tempDir: Path

    private val options = SessionConfig(threads = 1, autoProject = false)

    private fun fixture(): Path = compileJavaFixture(tempDir, mapOf(
        "fixture/User.java" to """
            package fixture;
            public class User {
                public int b;
                public int a(int value) { return value + b; }
            }
        """.trimIndent(),
        "fixture/Caller.java" to """
            package fixture;
            public class Caller {
                public int call(User user) { return user.a(user.b); }
            }
        """.trimIndent(),
    ))

    @Test
    fun `renames and declaration comments refresh both target and previously decompiled caller`() {
        JadxSession.open(fixture().toString(), options).use { s ->
            val user = assertNotNull(s.resolveClass("fixture.User"))
            val caller = assertNotNull(s.resolveClass("fixture.Caller"))
            assertTrue(s.getClassSource(caller).contains(".a("))
            assertTrue(s.getClassSource(user).contains("class User"))
            assertEquals("a", s.searchMethods("a", 10).single().name)
            assertTrue("fixture.User" in s.classFqns)

            s.renameClass(user, "Account")
            s.renameMethod(assertNotNull(s.findMethod("fixture.User", "a")), "calculate")
            s.renameField(assertNotNull(s.findField("fixture.User", "b")), "balance")
            val comment = "计算账户余额"
            s.setComment(assertNotNull(s.findMethod("fixture.User", "a")), comment, CommentStyle.JAVADOC)

            assertSame(user, s.resolveClass("fixture.Account"))
            assertSame(user, s.resolveClass("fixture.User"))
            assertEquals("calculate", s.findMethod("fixture.User", "a")?.name)
            assertEquals("calculate", s.findMethods("fixture.Account", "calculate").single().name)
            assertEquals("balance", s.findField("fixture.User", "b")?.name)
            assertEquals("balance", s.findField("fixture.Account", "balance")?.name)
            assertEquals("calculate", s.searchMethods("a", 10).single().name)
            assertEquals("calculate", s.searchMethods("calculate", 10).single().name)
            assertTrue("fixture.Account" in s.classFqns)
            assertFalse("fixture.User" in s.classFqns)
            val source = s.getClassSource(user)
            listOf("class Account", "calculate(", "balance", comment).forEach { assertTrue(it in source, source) }
            val callerSource = s.getClassSource(caller)
            assertTrue(".calculate(" in callerSource, callerSource)
            assertTrue(".balance" in callerSource, callerSource)
            assertEquals(3, s.codeDataSnapshot().renames.size)
            assertEquals(CommentStyle.JAVADOC, s.codeDataSnapshot().comments.single().style)
        }
    }

    @Test
    fun `empty names and comments restore originals`() {
        JadxSession.open(fixture().toString(), options).use { s ->
            val user = assertNotNull(s.resolveClass("fixture.User"))
            s.renameClass(user, "moved.Account")
            s.renameMethod(assertNotNull(s.findMethod("fixture.User", "a")), "calculate")
            s.renameField(assertNotNull(s.findField("fixture.User", "b")), "balance")
            s.setComment(user, "Remove this comment")
            assertTrue("class Account" in s.getClassSource(user))

            s.renameMethod(assertNotNull(s.findMethod("moved.Account", "calculate")), "")
            s.renameField(assertNotNull(s.findField("moved.Account", "balance")), "")
            s.renameClass(user, "")
            s.setComment(user, "")
            assertEquals("fixture.User", user.fullName)
            s.renamePackage("fixture", "renamed")
            assertSame(user, s.resolveClass("renamed.User"))
            s.renamePackage("renamed", "")
            assertEquals("fixture.User", user.fullName)
            assertTrue(s.codeDataSnapshot().isEmpty)
            val source = s.getClassSource(user)
            listOf("class User", " a(", " b").forEach { assertTrue(it in source, source) }
            assertFalse("Remove this comment" in source)
        }
    }

    @Test
    fun `saved project applies renames before a new session loads`() {
        val jar = fixture()
        val path = tempDir.resolve("annotations.jadx")
        JadxSession.open(jar.toString(), options).use { s ->
            val user = assertNotNull(s.resolveClass("fixture.User"))
            s.renameClass(user, "Account")
            s.renameMethod(assertNotNull(s.findMethod("fixture.User", "a")), "calculate")
            s.renameField(assertNotNull(s.findField("fixture.User", "b")), "balance")
            s.setComment(assertNotNull(s.findMethod("fixture.User", "a")), "Persisted comment")
            assertEquals(path, s.saveProject(path))
            assertEquals(path, s.projectPath)
            assertTrue(s.setComment(user, "Saved automatically").projectSaved)
        }
        JadxSession.open(jar.toString(), options, path.toString()).use { s ->
            assertTrue(s.projectLoaded)
            val user = assertNotNull(s.resolveClass("fixture.Account"))
            assertSame(user, s.resolveClass("fixture.User"))
            val source = s.getClassSource(user)
            listOf("class Account", "calculate(", "balance", "Persisted comment", "Saved automatically")
                .forEach { assertTrue(it in source, source) }
            assertEquals(3, s.codeDataSnapshot().renames.size)
            assertEquals(2, s.codeDataSnapshot().comments.size)
        }
    }

    @Test
    fun `project write preserves unknown keys and stores relative input paths`() {
        val path = tempDir.resolve("existing.jadx")
        val original = """{"projectVersion":2,"files":[],"openTabs":[{"tab":"keep","position":42}],"pluginOptions":{"custom":"value"}}"""
        Files.writeString(path, original)
        val input = tempDir.resolve("fixture.jar")
        ProjectFile.write(path, listOf(input), JadxCodeData())
        val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
        val before = Json.parseToJsonElement(original).jsonObject
        assertEquals(before["openTabs"], root["openTabs"])
        assertEquals(before["pluginOptions"], root["pluginOptions"])
        assertEquals("fixture.jar", root.getValue("files").jsonArray.single().jsonPrimitive.content)
        val project = ProjectFile.read(path)
        assertEquals(listOf(input), project.files)
        assertTrue(project.codeData.isEmpty)
    }
}
