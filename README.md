# 知白 ZhiBai

> 所见，皆可问。  
> 为小米 MiMo 大模型而生的第三方 Android 客户端。图片、视频、语音、文件，一个输入框全部搞定。  
> 知其白，守其黑。整套界面只有黑与白，没有一处多余的彩色。

## 功能

### 对话

- **多模态理解** — 图片、视频、音频直连 MiMo 接口；非视觉模型自动先提取图片文字再提交
- **回答分支** — 点「重新回答」为同一回答生成新版本，`‹ N/M ›` 翻页切换；后续对话跟随所选分支，切走的分支连同其对话自动隐藏，切回即恢复
- **多模型切换** — 启动自动拉取官方模型列表，会话记住选过的模型；液态玻璃胶囊选择器（Kyant0/AndroidLiquidGlass）
- **十国语言** — 简体中文、繁体（台湾／香港）、英、日、韩、法、德、西、俄、葡，界面与回复语言联动
- **多模态附件** — 图片编辑器（裁剪、画笔、箭头、圈选、马赛克、文字），附件数量可解锁上限

### Agent 模式

Agent 拥有 60+ 工具，覆盖文件、网络、设备、应用、通信、脚本运行与多智能体协作：

| 类别 | 工具 |
|---|---|
| 文件 | `read_file` `write_file` `edit_file` `list_files` `delete_file` `move_file` `copy_file` `search_files` `grep` `get_file_info` `extract_archive` `show_file` `open_file` |
| 网络 | `fetch_url` `download_file` `open_url` |
| 终端 | `run_shell`（内置 Linux rootfs，可 `install_rootfs` 解锁 apt/python3）`run_python` `run_node` `run_java` `run_adb`（无线 ADB 真 shell） |
| 设备 | `get_device_info` `get_device_details` `get_location` `get_network_info` `get_battery_info` `get_storage_usage` `get_screen_state` `set_screen_timeout` `set_brightness` `set_volume` `get_volume` `set_sound_mode` `get_sound_mode` `toggle_flashlight` `vibrate` `clipboard_read` `clipboard_write` |
| 应用 | `open_app` `list_apps` `close_app` `get_app_info` `install_apk` `uninstall_app` `download_and_install` `open_settings` |
| 通信 | `make_call` `search_contacts` `share_text` `set_alarm` `set_timer` `get_alarms` `get_step_count` `open_file` `view_media` |
| 协作 | `spawn_subagent` `run_subagent`（并行子智能体）`add_task` `update_task` `strike_task`（任务面板）`create_questionnaire`（问卷卡）`switch_phase`（PLAN/BUILD 切换） |
| 技能 | `save_skill` `read_skill` `list_skills` — 可复用工作流 |
| 手机助手 | `execute_phone_action`（AutoGLM 自然语言操作手机） |

- **权限分档** — Ask All / 默认（低风险放行）/ 全自动 / AI 审批 四级；删除、移动等破坏性操作永远弹确认，文件工具默认锁死在工作区内，越界必须确认
- **工具调用折叠展示** — 步骤时间线（思考/搜索/浏览）+ 工具卡片实时输出，思考过程半展开，点击全展开/收起
- **工作区** — `Documents/ZhiBai`（可见于文件管理器、不进相册），IDENTITY/SOUL/TOOLS/MEMORY 等工作文件自动初始化

### 超级岛（Super Island）

实时活动胶囊，全程跟随一次运行的生命周期：

- 阶段状态一目了然：等待响应 → 思考中 → 输出中 → 运行中 → 完成
- **岛上直接审批** — 需要授权/需要答题时在岛上点允许/拒绝，不用切回 App
- 手机助手运行时岛上可「终止」或「暂时接管」
- 完成后岛显示回答摘要，点击回 App 查看
- 生成期间前台保活——切走再从岛回来，任务继续、终端环境不丢

### 语音

- **ASR 语音识别** — 长按说话，转文字发送
- **TTS 语音合成** — 流式朗读，多种音色（含克隆/设计音色），回复可自动念出

### 安全与密钥

- **mm- 外层包装** — 密钥支持 `mm-...` 形式粘贴（自动解开存为明文 sk）、复制永远输出 mm- 形式（`tools/mm_encode.ps1` 离线打包工具）；保存后密钥不可再次查看，可复制/清空/修改
- **本地优先** — API Key 只存在手机里，直连小米官方接口，无中转服务器
- **小米负面前提防护** — 可开关的系统提示词，隐藏设置内可关

## 环境要求

- Android 8.0+（API 26+），推荐 Android 13+（液态玻璃完整效果）
- 小米账号 + MiMo API Key（[开放平台](https://platform.xiaomimimo.com) 申请）
- 开发：Android Studio + JDK 17

## 构建

```bash
# Android Studio 打开项目直接构建，或命令行（Gradle 8.9）：
gradle assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

安装后在「设置」中填入 API Key 即可使用。隐藏设置：设置 → 关于 → 长按版本号。

## 项目结构

```
app/src/main/java/com/zhibai/app/
├── MainActivity.kt          # 入口、路由、启动检查
├── data/api/                # MiMo API 客户端（对话/ASR/TTS/模型列表）+ 超级岛
├── agent/                   # Agent 引擎、工具注册、工作区、ADB、AutoGLM
├── security/                # mm- 密钥外层编解码（mm.transmute.v1）
├── viewmodel/               # ChatViewModel（会话、生成、分支、记忆）
├── ui/screens/              # 聊天、设置、引导、TTS、ASR、权限、崩溃页等
├── ui/components/           # 气泡、工具卡、胶囊模型栏、超级岛面板等
├── ui/editor/               # 图片编辑器
└── i18n/                    # LangManager + assets/locales（11 个语言包）
```

## 宣传页

`website/zhibai.html` — 单文件落地页，纯静态，无构建步骤。直接用浏览器打开，或扔到任意静态托管。

## 说明

- 非小米官方产品，与小米公司无隶属关系
- 接入的 MiMo API 按官方定价计费，与本应用无关
- MIT License

## 版本

当前：V2.6.0
