package uesugi.plugin.animal.gif

import uesugi.plugin.animal.domain.User
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AnimalGifGeneratorTest {

    @Test
    fun `generate creates non-empty gif file`() {
        val user = User.newUser(
            id = 1L,
            name = "Test",
            contributions = mapOf(2026 to 100)
        )
        val svg = user.createFarmAnimation()
        val html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0">$svg</body>
            </html>
        """.trimIndent()

        val output = File.createTempFile("test-farm", ".gif")
        output.deleteOnExit()

        val chromePath = when {
            System.getProperty("os.name").contains("Windows", ignoreCase = true) ->
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"
            else -> System.getenv("CHROME_PATH")
        }

        val config = GifConfig(
            fps = 10,
            gifDurationSeconds = 1,
            animationSampleSeconds = 2,
            viewportWidth = 600,
            viewportHeight = 300,
            browserExecutablePath = chromePath
        )

        val bytes = AnimalGifGenerator(config).generate(html)
        output.writeBytes(bytes)

        assertTrue(output.exists(), "GIF file should exist")
        assertTrue(bytes.isNotEmpty(), "GIF bytes should not be empty")
    }
}
