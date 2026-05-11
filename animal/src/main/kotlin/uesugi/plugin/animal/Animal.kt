package uesugi.plugin.animal

import uesugi.spi.AgentPlugin
import uesugi.spi.PluginDefinition


@PluginDefinition(pluginId = "animal", version = "0.0.1", description = "虚拟宠物养成插件")
class Animal : AgentPlugin()