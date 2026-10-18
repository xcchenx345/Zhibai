# 知白 ZhiBai

> 所见，皆可问。

为小米 MiMo 大模型而生的第三方 Android 客户端。图片、视频、语音、文件，一个输入框全部搞定。

知其白，守其黑。整套界面只有黑与白，没有一处多余的彩色。

## 功能

- **多模态理解** — 图片、视频、音频直连 MiMo 接口；非视觉模型自动先提取图片文字再提交
- **Agent 模式** — 读写工作目录、内容搜索、精确编辑、URL 下载、多步任务规划，工具调用折叠展示，支持实时旁白
- **语音对话** — 内置 ASR 识别与 TTS 流式朗读，长按说话，回复自动念出来
- **图片编辑器** — 裁剪、画笔、箭头、圈选、马赛克、文字，发出去之前先把话说清楚
- **多模型切换** — 启动时自动拉取官方模型列表，会话记住你选过的模型
- **权限分档** — Ask All / 默认 / 自动 / AI 审批四档，敏感操作可控
- **本地优先** — API Key 只存在手机里，直连小米官方接口，无中转服务器

## 环境要求

- Android 8.0+（API 26+）
- 小米账号 + MiMo API Key（[开放平台](https://platform.xiaomimimo.com)申请）
- 开发：Android Studio + JDK 17

## 构建

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装后在「设置」中填入 API Key 即可使用。

## 项目结构

```
app/src/main/java/com/mimo/app/
├── MainActivity.kt          # 入口与路由
├── MiMoApplication.kt
├── api/                     # MiMo API 客户端（对话/ASR/TTS/模型列表）
├── agent/                   # Agent 引擎、工具注册、工作目录
├── viewmodel/               # ChatViewModel 等
├── ui/
│   ├── screens/             # 聊天、设置、TTS、ASR、工具权限等
│   ├── components/          # 消息气泡、剪贴板等
│   ├── editor/              # 图片编辑器
│   └── theme/               # 纯黑白主题
└── util/                    # 权限、媒体、剪贴板辅助
```

## 宣传页

`website/zhibai.html` — 单文件落地页，纯静态，无构建步骤。直接用浏览器打开，或扔到任意静态托管。

## 说明

- 非小米官方产品，与小米公司无隶属关系
- 接入的 MiMo API 按官方定价计费，与本应用无关
- MIT License

## 版本

当前：v2.5.0
