package uesugi.plugin

internal fun buildSeedDreamRequestBody(
    model: String,
    prompt: String,
    images: List<String>,
    imageCount: Int
): Map<String, Any> = linkedMapOf<String, Any>(
    "model" to model,
    "prompt" to prompt
).apply {
    when (images.size) {
        0 -> Unit
        1 -> put("image", images.first())
        else -> put("image", images)
    }
    if (imageCount > 1) {
        put(
            "sequential_image_generation_options",
            mapOf("max_images" to imageCount.coerceAtMost(4))
        )
    }
    put("response_format", "url")
    put("size", "2K")
    put("stream", false)
    put("watermark", false)
}
