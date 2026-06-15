package uesugi.plugin.animal.gif

import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class AnimalGifGenerator(private val config: GifConfig = GifConfig()) {

    private val log = KotlinLogging.logger {}

    fun generate(html: String, output: File) {
        require(output.parentFile.exists() || output.parentFile.mkdirs()) {
            "Cannot create output directory: ${output.parentFile}"
        }

        val tempDir = Files.createTempDirectory("animal-gif-frames")
        log.info { "Generating GIF, temp frames: $tempDir" }

        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(
                    BrowserType.LaunchOptions().setHeadless(true)
                ).use { browser ->
                    generateFrames(browser, html, tempDir)
                    encodeGif(tempDir, output)
                }
            }
        } finally {
            if (!config.keepTempFrames) {
                tempDir.toFile().deleteRecursively()
            } else {
                log.info { "Kept temp frames: $tempDir" }
            }
        }

        log.info { "GIF generated: ${output.absolutePath} (${output.length()} bytes)" }
    }

    private fun generateFrames(browser: Browser, html: String, tempDir: Path) {
        val context = browser.newContext(
            Browser.NewContextOptions()
                .setViewportSize(config.viewportWidth, config.viewportHeight)
        )

        context.use {
            val page = context.newPage()
            val htmlFile = tempDir.resolve("input.html").toFile()
            htmlFile.writeText(html, Charsets.UTF_8)

            page.navigate("file://${htmlFile.absolutePath}")
            page.waitForLoadState(LoadState.NETWORKIDLE)

            page.evaluate("() => { document.getAnimations().forEach(a => a.pause()); }")

            val totalFrames = config.fps * config.gifDurationSeconds
            val sampleDurationMs = config.animationSampleSeconds * 1000
            val stepMs = sampleDurationMs / totalFrames

            for (i in 0 until totalFrames) {
                val timeMs = i * stepMs
                page.evaluate(
                    "time => { document.getAnimations().forEach(a => a.currentTime = time); }",
                    timeMs
                )
                val framePath = tempDir.resolve(String.format("frame_%04d.png", i))
                page.screenshot(
                    Page.ScreenshotOptions()
                        .setPath(framePath)
                        .setFullPage(false)
                )
                if (i % 30 == 0) {
                    log.info { "Rendered frame $i / $totalFrames" }
                }
            }
        }
    }

    private fun encodeGif(framesDir: Path, output: File) {
        val framePattern = framesDir.resolve("frame_%04d.png").toString()
        val filter = "fps=${config.fps},scale=${config.viewportWidth}:-1:flags=lanczos," +
                "split[s0][s1];[s0]palettegen=max_colors=${config.maxColors}[p];" +
                "[s1][p]paletteuse=dither=bayer"

        val command = listOf(
            "ffmpeg", "-y",
            "-framerate", config.fps.toString(),
            "-i", framePattern,
            "-vf", filter,
            output.absolutePath
        )

        log.info { "Running FFmpeg: ${command.joinToString(" ")}" }

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("FFmpeg process timed out")
        }

        if (process.exitValue() != 0) {
            val error = process.inputStream.bufferedReader().readText()
            throw RuntimeException("FFmpeg failed with exit code ${process.exitValue()}: $error")
        }
    }
}
