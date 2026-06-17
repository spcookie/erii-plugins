package uesugi.plugin.animal.gif

import uesugi.plugin.animal.domain.User

class FarmGifRenderer(private val config: GifConfig = GifConfig()) {

    fun render(user: User): ByteArray {
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
        return AnimalGifGenerator(config).generate(html)
    }
}
