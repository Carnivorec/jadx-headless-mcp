package com.atxx.jhmcp

import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.core.xmlgen.ResourcesSaver
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read

internal class SourceExporter(
    private val session: JadxSession,
    private val decompiler: JadxDecompiler,
    private val codeDataLock: ReentrantReadWriteLock,
) {
    @Volatile
    private var job: ExportJob? = null

    fun start(
        outDir: File,
        packagePrefix: String? = null,
        includeResources: Boolean = false,
        waitSeconds: Int = 30,
    ): Status {
        val next = synchronized(this) {
            check(job?.finished?.count != 1L) { "An export is already running" }
            require(outDir.isAbsolute) { "out_dir must be an absolute path" }
            Files.createDirectories(File(outDir, "sources").toPath())
            val classes = codeDataLock.read {
                session.classes.filter {
                    packagePrefix == null || it.fullName == packagePrefix || it.fullName.startsWith("$packagePrefix.")
                }
            }
            ExportJob(outDir, classes, includeResources).also {
                job = it
                it.worker.start()
            }
        }
        return next.await(waitSeconds)
    }

    fun status(waitSeconds: Int = 0, cancel: Boolean = false): Status {
        val current = job ?: error("No export has been started")
        if (cancel) current.cancel()
        return current.await(waitSeconds)
    }

    fun cancel() {
        job?.cancel()
    }

    data class Status(
        val state: String,
        val outDir: String,
        val totalClasses: Int,
        val doneClasses: Int,
        val failedCount: Int,
        val timedOutCount: Int,
        val failed: List<String>,
        val timedOut: List<String>,
        val renamedFiles: Int,
        val resourcesWritten: Int?,
        val elapsedMs: Long,
    )

    private inner class ExportJob(
        private val outDir: File,
        private val classes: List<JavaClass>,
        private val includeResources: Boolean,
    ) {
        val finished = CountDownLatch(1)
        private val state = AtomicReference("running")
        private val cancelled = AtomicBoolean()
        private val nextClass = AtomicInteger()
        private val doneClasses = AtomicInteger()
        private val failedCount = AtomicInteger()
        private val timedOutCount = AtomicInteger()
        private val renamedFiles = AtomicInteger()
        private val resourcesWritten = AtomicInteger()
        private val failed = ArrayList<String>()
        private val timedOut = ArrayList<String>()
        private val writtenPaths = HashSet<String>()
        private val started = System.nanoTime()
        private val elapsedMs = AtomicLong(-1)
        private val lanes = List(session.threads) { Lane(it) }
        val worker = Thread({ run() }, "jhmcp-export").apply { isDaemon = true }

        fun cancel() {
            if (state.compareAndSet("running", "cancelled")) {
                cancelled.set(true)
                lanes.forEach { it.thread.interrupt() }
            }
        }

        fun await(waitSeconds: Int): Status {
            finished.await(waitSeconds.coerceIn(0, 300).toLong(), TimeUnit.SECONDS)
            return Status(
                state.get(), outDir.path, classes.size, doneClasses.get(), failedCount.get(), timedOutCount.get(),
                synchronized(failed) { failed.toList() }, synchronized(timedOut) { timedOut.toList() },
                renamedFiles.get(), resourcesWritten.get().takeIf { includeResources },
                elapsedMs.get().takeIf { it >= 0 } ?: elapsed(),
            )
        }

        private fun elapsed(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        private fun run() {
            System.err.println("[jhmcp] export started: ${outDir.path} (classes=${classes.size}, threads=${session.threads})")
            try {
                if (includeResources) {
                    Files.createDirectories(File(outDir, "resources").toPath())
                    val resources = session.resources.sortedBy { !JadxSession.isManifestResource(it) }
                    for (resource in resources) {
                        if (cancelled.get()) break
                        ResourcesSaver(decompiler, File(outDir, "resources"), resource).run()
                        resourcesWritten.incrementAndGet()
                    }
                }
                if (!cancelled.get()) {
                    lanes.forEach { it.thread.start() }
                    lanes.forEach { it.thread.join() }
                }
            } catch (_: Exception) {
                state.compareAndSet("running", "failed")
            } finally {
                lanes.forEach { it.thread.interrupt() }
                lanes.forEach { it.thread.join() }
                elapsedMs.set(elapsed())
                if (cancelled.get()) state.set("cancelled")
                else state.compareAndSet("running", "done")
                System.err.println("[jhmcp] export ${state.get()}: ${outDir.path} (${elapsedMs.get()}ms)")
                finished.countDown()
            }
        }

        private fun sourceFile(aliasPath: String): File = synchronized(writtenPaths) {
            var path = "$aliasPath.java"
            var suffix = 1
            while (!writtenPaths.add(path.lowercase())) {
                path = "${aliasPath}_${suffix++}.java"
            }
            if (suffix > 1) renamedFiles.incrementAndGet()
            File(File(outDir, "sources"), path)
        }

        private fun record(names: MutableList<String>, name: String) = synchronized(names) {
            if (names.size < 20) names.add(name)
        }

        private inner class Lane(private val index: Int) {
            val thread = Thread({ run() }, "jhmcp-export-lane-$index").apply { isDaemon = true }

            private fun newExecutor() = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "jhmcp-export-decompile-$index").apply { isDaemon = true }
            }

            private fun run() {
                var executor = newExecutor()
                try {
                    while (!cancelled.get()) {
                        val cls = classes.getOrNull(nextClass.getAndIncrement()) ?: break
                        val name = cls.fullName
                        val writable = AtomicBoolean(true)
                        val future = executor.submit(Callable {
                            codeDataLock.read {
                                try {
                                    val code = cls.classNode.decompile().codeStr
                                    if (writable.get() && !cancelled.get()) {
                                        val file = sourceFile(cls.classNode.classInfo.aliasFullPath)
                                        Files.createDirectories(file.parentFile.toPath())
                                        file.writeText(code, Charsets.UTF_8)
                                    }
                                } finally {
                                    cls.unload()
                                }
                            }
                        })
                        try {
                            future.get(session.decompileTimeoutMs, TimeUnit.MILLISECONDS)
                        } catch (_: TimeoutException) {
                            writable.set(false)
                            future.cancel(true)
                            executor.shutdownNow()
                            executor = newExecutor()
                            timedOutCount.incrementAndGet()
                            record(timedOut, name)
                        } catch (_: ExecutionException) {
                            failedCount.incrementAndGet()
                            record(failed, name)
                        } catch (_: InterruptedException) {
                            writable.set(false)
                            future.cancel(true)
                            Thread.currentThread().interrupt()
                            break
                        } finally {
                            if (!cancelled.get()) doneClasses.incrementAndGet()
                        }
                    }
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }
}
