package uesugi.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SeedDreamRequestBodyTest {
    @Test
    fun `omits image and sequential options for one text-to-image result`() {
        val body = buildSeedDreamRequestBody(
            model = "seedream",
            prompt = "画一只猫",
            images = emptyList(),
            imageCount = 1
        )

        assertEquals("画一只猫", body["prompt"])
        assertFalse(body.containsKey("image"))
        assertFalse(body.containsKey("sequential_image_generation_options"))
    }

    @Test
    fun `uses a string for one reference image`() {
        val image = "data:image/png;base64,one"

        val body = buildSeedDreamRequestBody(
            model = "seedream",
            prompt = "参考这张图",
            images = listOf(image),
            imageCount = 1
        )

        assertEquals(image, body["image"])
        assertFalse(body.containsKey("sequential_image_generation_options"))
    }

    @Test
    fun `uses an array for multiple references and adds max images`() {
        val images = listOf(
            "data:image/png;base64,one",
            "data:image/png;base64,two"
        )

        val body = buildSeedDreamRequestBody(
            model = "seedream",
            prompt = "生成三张",
            images = images,
            imageCount = 3
        )

        assertEquals(images, body["image"])
        assertEquals(
            mapOf("max_images" to 3),
            body["sequential_image_generation_options"]
        )
    }
}
