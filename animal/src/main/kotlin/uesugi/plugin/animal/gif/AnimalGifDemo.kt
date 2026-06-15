package uesugi.plugin.animal.gif

import uesugi.plugin.animal.domain.User
import java.io.File

fun main() {
    val user = User.newUser(
        id = 1L,
        name = "Demo",
        contributions = mapOf(2026 to 3000)
    )

    val svg = user.createFarmAnimation()

    val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <style>
                body { margin: 0; padding: 0; background: #ffffff; }
                svg { display: block; }
            </style>
        </head>
        <body>
        $svg
        </body>
        </html>
    """.trimIndent()

    val config = GifConfig(
        fps = 30,
        gifDurationSeconds = 10,
        animationSampleSeconds = 60,
        viewportWidth = 600,
        viewportHeight = 300,
        outputPath = "animal-farm.gif"
    )
    val output = File(config.outputPath)

    AnimalGifGenerator(config).generate(html, output)
    println("GIF generated: ${output.absolutePath}")
}
