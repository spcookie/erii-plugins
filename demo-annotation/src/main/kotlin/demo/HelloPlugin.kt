@file:uesugi.spi.annotation.Definition(pluginId = "demo.hello", version = "1.0.0")

package demo

import uesugi.spi.*
import uesugi.spi.annotation.*

@Plugin
object HelloPlugin : PluginDelegate()

@Cmd(name = "hello", alias = ["hi"], toolSets = ["default"])
suspend fun helloCmd(args: List<String>, meta: Meta) {
    meta.sendAgent("Hello ${args.firstOrNull() ?: "world"}!")
}
