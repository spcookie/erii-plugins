# erii-plugins

PF4J插件框架，4种扩展类型: AgentExtension, RouteExtension, CmdExtension, PassiveExtension

## 插件列表
| 插件 | 类型 | 说明 |
|------|------|------|
| speech | AgentExtension | MiniMax TTS语音合成 |
| lolisuki | RouteExtension | 二次元图片 |
| net-ease-music | PassiveExtension | 网易云音乐 |
| qq-face | PassiveExtension | QQ表情匹配 |
| seeddream | RouteExtension | 文生图/图生图 |
| animal | AgentExtension | 虚拟宠物养成 |

## 约束
- 插件资源: `src/main/resources/`
- 使用`@PluginDefinition`注解
- 禁止绕过扩展点接口直接调用