# Erii Plugins

[Erii](https://github.com/spcookie/erii) 的插件集合，基于 PF4J 框架和 `uesugi.erii-plugin` Gradle 插件构建。

## 插件列表

| 插件 | 扩展类型 | 说明 |
|------|----------|------|
| **speech** | AgentExtension | MiniMax TTS 语音合成，将文字转为语音发送 |
| **lolisuki** | RouteExtension | 二次元涩图插件，从 lolisuki.cn 获取图片 |
| **net-ease-music** | PassiveExtension | 网易云音乐插件，搜索音乐并发送音乐卡片 |
| **qq-face** | PassiveExtension | QQ 表情匹配，语义分析发送合适的表情 |
| **seeddream** | RouteExtension | AI 图片生成，支持文生图和图生图 |
| **rollpig** | AgentExtension | 抽小猪游戏插件 |
| **animal** | AgentExtension | 虚拟宠物养成，角色扮演与好感度系统 |

## 构建

```bash
./gradlew build
```

产出 zip 包位于各子模块的 `build/plugins/` 目录下，可直接放入 Erii 的 `plugins/` 目录加载。

## 开发新插件

1. 在 `erii-plugins/` 下创建新目录，如 `my-plugin/`
2. 在 `settings.gradle.kts` 中添加 `include("my-plugin")`
3. 编写 `build.gradle.kts`：

```kotlin
plugins {
    id("uesugi.erii-plugin")
}

version = "0.0.1"
```

4. 实现插件：

```kotlin
@PluginDefinition(pluginId = "my-plugin", version = "0.0.1", description = "我的插件")
class MyPlugin : AgentPlugin() {
    // 实现扩展接口
}
```

## 技术栈

- **Kotlin** + Gradle
- **PF4J** 插件框架
- **uesugi.erii-plugin** Gradle 约定插件
