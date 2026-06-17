package uesugi.plugin.animal.gif

class FarmGifRenderer(private val config: GifConfig = GifConfig()) {

    suspend fun render(groupId: String, userId: Long, serverBaseUrl: String): ByteArray {
        val cfg = config.copy(serverBaseUrl = serverBaseUrl)
        return AnimalGifGenerator(cfg).generate(groupId, userId)
    }
}
