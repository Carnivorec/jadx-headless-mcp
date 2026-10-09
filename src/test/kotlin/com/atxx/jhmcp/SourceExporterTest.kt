package com.atxx.jhmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceExporterTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `exports top-level sources with inner classes in their outer source`() {
        val jarPath = compileJavaFixture(tempDir, mapOf(
            "fixture/Outer.java" to """
                package fixture;
                public class Outer {
                    public int value() { return 1; }
                    public static class Inner {
                        public int value() { return 2; }
                    }
                }
            """.trimIndent(),
            "fixture/Other.java" to """
                package fixture;
                public class Other {
                    public int value() { return 3; }
                }
            """.trimIndent(),
            "fixture/Third.java" to """
                package fixture;
                public class Third {
                    public int value() { return 4; }
                }
            """.trimIndent(),
        ))
        val outDir = tempDir.resolve("export")
        JadxSession.open(jarPath.toString(), SessionConfig(threads = 2, autoProject = false)).use { session ->
            session.sourceExporter.start(outDir.toFile(), waitSeconds = 0)
            val status = session.sourceExporter.status(waitSeconds = 30)
            assertEquals("done", status.state)
            assertEquals(3, status.totalClasses)
            assertEquals(3, status.doneClasses)
            assertEquals(0, status.failedCount)
            assertEquals(0, status.timedOutCount)
            for (name in listOf("Outer", "Other", "Third")) {
                val source = outDir.resolve("sources/fixture/$name.java")
                assertTrue(Files.exists(source))
                assertTrue(Files.readString(source).contains("class $name"))
            }
            assertTrue(Files.readString(outDir.resolve("sources/fixture/Outer.java")).contains("class Inner"))
            Files.walk(outDir.resolve("sources")).use { paths ->
                assertEquals(3L, paths.filter { Files.isRegularFile(it) }.count())
            }
            assertFalse(Files.exists(outDir.resolve("sources/fixture/Outer_Inner.java")))
        }
    }
}
