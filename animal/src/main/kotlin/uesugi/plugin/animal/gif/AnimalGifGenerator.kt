package uesugi.plugin.animal.gif

import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ScreenshotType
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

    fun generate(html: String): ByteArray {
        val tempDir = Files.createTempDirectory("animal-gif-")
        log.info { "Generating GIF, temp dir: $tempDir" }

        return try {
            Playwright.create().use { playwright ->
                val launchOptions = BrowserType.LaunchOptions()
                    .setHeadless(true)
                config.browserExecutablePath?.let {
                    launchOptions.setExecutablePath(java.nio.file.Paths.get(it))
                }
                playwright.chromium().launch(launchOptions).use { browser ->
                    val frames = captureFrames(browser, html, tempDir)
                    encodeGif(frames)
                }
            }
        } finally {
            if (!config.keepTempFrames) {
                tempDir.toFile().deleteRecursively()
            } else {
                log.info { "Kept temp dir: $tempDir" }
            }
        }
    }

    private fun captureFrames(browser: Browser, html: String, tempDir: Path): List<ByteArray> {
        val context = browser.newContext(
            Browser.NewContextOptions()
                .setViewportSize(config.viewportWidth, config.viewportHeight)
        )

        return context.use {
            val page = context.newPage()
            val htmlFile = tempDir.resolve("input.html").toFile()
            htmlFile.writeText(html, Charsets.UTF_8)

            page.navigate("file://${htmlFile.absolutePath}")
            page.waitForLoadState(LoadState.NETWORKIDLE)

            page.evaluate("() => { document.getAnimations().forEach(a => a.pause()); }")

            val totalFrames = config.fps * config.gifDurationSeconds
            val sampleDurationMs = config.animationSampleSeconds * 1000
            val stepMs = sampleDurationMs / totalFrames
            val frames = mutableListOf<ByteArray>()

            for (i in 0 until totalFrames) {
                val timeMs = i * stepMs
                page.evaluate(
                    "time => { document.getAnimations().forEach(a => a.currentTime = time); }",
                    timeMs
                )
                val screenshotOptions = Page.ScreenshotOptions()
                    .setType(config.screenshotType)
                    .setFullPage(false)
                if (config.screenshotType == ScreenshotType.JPEG) {
                    screenshotOptions.setQuality(config.screenshotQuality)
                }
                frames.add(page.screenshot(screenshotOptions))
                if (i % 30 == 0) {
                    log.info { "Rendered frame $i / $totalFrames" }
                }
            }

            frames
        }
    }

    private fun encodeGif(frames: List<ByteArray>): ByteArray {
        val filter = "fps=${config.fps},scale=${config.viewportWidth}:-1:flags=lanczos," +
                "split[s0][s1];[s0]palettegen=max_colors=${config.maxColors}[p];" +
                "[s1][p]paletteuse=dither=bayer"

        val command = listOf(
            "docker", "run", "--rm", "-i",
            config.ffmpegDockerImage
        ) + listOf(
            "-y",
            "-f", "image2pipe",
            "-framerate", config.fps.toString(),
            "-i", "-",
            "-vf", filter,
            "-f", "gif",
            "pipe:1"
        )

        log.info { "Running: ${command.joinToString(" ")}" }

        val process = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        process.outputStream.use { stdin ->
            frames.forEach { frame ->
                stdin.write(frame)
            }
        }

        val outputBytes = process.inputStream.use { it.readBytes() }

        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("FFmpeg process timed out")
        }

        if (process.exitValue() != 0) {
            throw RuntimeException("FFmpeg failed with exit code ${process.exitValue()}")
        }

        return outputBytes
    }
}
