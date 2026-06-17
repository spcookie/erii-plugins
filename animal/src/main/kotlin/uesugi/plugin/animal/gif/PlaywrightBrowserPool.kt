package uesugi.plugin.animal.gif

import com.microsoft.playwright.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uesugi.common.toolkit.ConfigHolder

object PlaywrightBrowserPool {

    private val log = KotlinLogging.logger {}
    private val mutex = Mutex()

    @Volatile
    private var browser: Browser? = null

    @Volatile
    private var playwright: Playwright? = null

    private fun ensureInitialized(config: GifConfig) {
        if (playwright != null) return
        playwright = Playwright.create(
            Playwright.CreateOptions().apply {
                setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to (!ConfigHolder.getBrowserDownload()).toString()))
            }
        )
        browser = if (ConfigHolder.getBrowserDownload()) {
            val launchOptions = BrowserType.LaunchOptions().setHeadless(true)
            config.browserExecutablePath?.let {
                launchOptions.setExecutablePath(java.nio.file.Paths.get(it))
            }
            playwright!!.chromium().launch(launchOptions)
        } else {
            playwright!!.chromium().connect(ConfigHolder.getPlaywrightUrl())
        }
        log.info { "Playwright browser initialized (local=${ConfigHolder.getBrowserDownload()})" }
    }

    suspend fun <R> useContext(config: GifConfig, block: (BrowserContext, Page) -> R): R {
        return mutex.withLock {
            ensureInitialized(config)
            val currentBrowser = browser!!
            val isConnected = runCatching { currentBrowser.isConnected }.getOrDefault(false)
            if (!isConnected) {
                log.warn { "Browser disconnected, reconnecting..." }
                closeInternal()
                ensureInitialized(config)
            }
            val ctx = currentBrowser.newContext(
                Browser.NewContextOptions()
                    .setViewportSize(config.viewportWidth, config.viewportHeight)
            )
            try {
                val page = ctx.newPage()
                try {
                    block(ctx, page)
                } finally {
                    runCatching { page.close() }
                }
            } finally {
                runCatching { ctx.close() }
            }
        }
    }

    @Synchronized
    private fun closeInternal() {
        runCatching { browser?.close() }
        runCatching { playwright?.close() }
        browser = null
        playwright = null
    }

    fun close() {
        closeInternal()
        log.info { "Playwright browser closed" }
    }
}
