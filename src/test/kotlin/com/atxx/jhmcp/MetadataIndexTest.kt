package com.atxx.jhmcp

import jadx.core.dex.nodes.ProcessState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetadataIndexTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `metadata navigation leaves classes unloaded and overloads stable`() {
        val sources = buildMap {
            put("fixture/Outer.java", """
                package fixture;
                public class Outer {
                    public int calcValue;
                    public int calc(int value) { return value + calcValue; }
                    public int calc(String value) { return value.length() + calcValue; }
                    public class Inner {
                        public int readValue() { return calcValue; }
                    }
                    public static class Companion {
                        public int twice(int value) { return value * 2; }
                    }
                }
            """.trimIndent())
            repeat(20) { index ->
                put("fixture/Other$index.java", """
                    package fixture;
                    public class Other$index {
                        public int value() { return $index; }
                    }
                """.trimIndent())
            }
        }
        val jarPath = compileJavaFixture(tempDir, sources)
        JadxSession.open(jarPath.toString(), SessionConfig(threads = 1)).use { session ->
            val outer = assertNotNull(session.resolveClass("fixture.Outer"))
            assertNotNull(session.resolveClass("fixture.Outer\$Inner"))
            assertNotNull(session.resolveClass("fixture.Outer.Companion"))
            val summary = session.summarizeClass(outer)
            val before = session.methodsOf(outer).filter { it.name == "calc" }
                .map { it.name to it.methodNode.methodInfo.shortId }
            assertEquals(listOf("calc" to "calc(I)I", "calc" to "calc(Ljava/lang/String;)I"), before)
            val summaryMethods = (summary["methods"] as List<*>).map { it as Map<*, *> }
            assertEquals(before.map { it.second }, summaryMethods.filter { it["name"] == "calc" }.map { it["short_id"] })
            assertEquals(listOf("calcValue"), session.fieldsOf(outer).map { it.name })
            assertEquals(2, session.innerClassesOf(outer).size)
            assertEquals(before, session.searchMethods("calc", 10).map { it.name to it.methodNode.methodInfo.shortId })
            val scopes = setOf(JadxSession.SearchScope.METHOD, JadxSession.SearchScope.FIELD)
            val hits = session.searchClassesAdvanced("calc", scopes).hits
            assertEquals(listOf("fixture.Outer"), hits.map { it.fqn })
            assertEquals(scopes, hits.single().matchedIn)
            session.allClasses.forEach { cls ->
                assertEquals(ProcessState.NOT_LOADED, cls.classNode.state, cls.fullName)
            }

            assertTrue(session.getClassSource(outer).contains("class Outer"))
            val after = session.methodsOf(outer).filter { it.name == "calc" }
                .map { it.name to it.methodNode.methodInfo.shortId }
            assertEquals(before, after)
            val others = session.allClasses.filter { it.fullName.startsWith("fixture.Other") }
            assertEquals(20, others.size)
            others.forEach { cls ->
                assertEquals(ProcessState.NOT_LOADED, cls.classNode.state, cls.fullName)
            }
        }
    }
}
