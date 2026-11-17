# i18n 全量迁移规范（子代理必读）

目标：把 MiMoApp 所有**用户可见**的硬编码中文字符串迁入 locale 词条表，界面文字经 `LangManager.t()` 输出，使设置里的语言切换真正生效。

## 提取范围

**要提取**（用户看得见的文字）：
- `Text("...")`、`title = { Text("...") }`、`headlineContent`/`supportingContent`/`label`/`placeholder`/`message` 等 Compose 文本参数
- `contentDescription`（无障碍朗读）
- Toast 文本、通知栏正文（Notification 的 title/content）
- 传给 UI 显示的状态/错误文案（如 ViewModel 里的 "正在思考"、"搜索失败"）
- `onNavigate(page, "标题")` 这类导航标题参数

**不提取**：
- 注释、`Log.x(...)`、开发者异常信息（`throw`/`IOException` 消息）
- 发给模型的 prompt / system prompt / 工具 schema 文本
- 纯品牌/技术词：`API Key`、`MiMo`、`ADB`、`AutoGLM`、`TTS`、`ASR`、`OK`、模型名、URL、文件路径、纯数字
- 字体名（ui/theme/SystemFonts.kt 不碰）
- LangManager.kt 内部的 NAMES/PROMPTS 词条表（本来就是多语言数据）
- `strings.xml`（不碰 res）

拿不准是否用户可见 → 偏向提取（宁多勿漏，但注释/日志绝对不动）。

## Key 命名

- 全小写、点分层：`<区域>.<页面/组件>.<语义>`，例：`settings.apikey.title`、`chat.send`、`code.copy`、`onboarding.next`
- 区域前缀建议：`settings.` `chat.` `code.` `common.` `model.` `step.` `agent.` `perm.` `onboarding.` `prefs.` `crash.` `archive.` `artifacts.` `tts.` `asr.` `adb.` `glm.` `youth.` `phone.` `media.` `skills.` `voice.` `font.` `input.` `island.` `notif.` `debug.` `localai.`
- 同一语义共用一个 key（"取消"处处用 `common.cancel`）
- **先复用** `app/src/main/assets/locales/zh-cn.json` 里已有 key（文件里 57 个），值与语义都吻合时必须复用；拿不准就新建

## 调用写法

文件顶部加 `import com.zhibai.app.i18n.LangManager`（若已有 FQCN 用法则保持 FQCN 亦可）。**必须带兜底原文**：

```kotlin
Text(LangManager.t("chat.send", "发送"))
```

带参数的模板串 → 值里用 `{name}` 占位，调用处 `.replace()`：

```kotlin
// 原：Text("共 ${list.size} 条记忆")
Text(LangManager.t("archive.count", "共 {n} 条记忆").replace("{n}", list.size.toString()))
```

二选一：

```kotlin
// 原：Text(if (running) "停止" else "发送")
Text(LangManager.t(if (running) "chat.stop" else "chat.send", if (running) "停止" else "发送"))
```

值里禁止 emoji；保留原有省略号 `…`、全半角标点。

## 产出

1. 就地编辑你负责的文件（只改你负责的文件，**不碰** LangManager.kt、zh-cn.json、其他组文件）
2. 新建 `i18n/extracted/<组号>.json`，内容为扁平映射 `"key": "中文原文（占位符已转换）"`，UTF-8 无 BOM，`ensure_ascii=False`
3. 结束时报告：改动文件数、新增 key 数、复用 key 列表、可疑点

## 词条值示例

```json
{
  "settings.apikey.desc": "配置 MiMo API 访问",
  "archive.count": "共 {n} 条记忆",
  "settings.autoglm.title": "AutoGLM 手机助手"
}
```
