# Erii Plugins

[Erii](https://github.com/spcookie/erii) 的插件集合，基于 PF4J 框架和 `uesugi.erii-plugin` Gradle 插件构建。

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
