# 知白 · 雪 — debug2.6.999 尝鲜版 / Zhibai · Snow — debug2.6.999 Preview

**版本号 / Version:** `debug2.6.999`  
**性质 / Type:** 内测 Debug 包（带全屏水印，无源码） / Internal debug build (full-screen watermark, no source code)  
**基线 / Baseline:** v2.6 雪 / Snow

---

## 这是什么 / What is this

知白 MiMo 的 Android 尝鲜安装包。基于「雪」版本，叠加超级岛与 Agent 问卷等调试能力。仅附 APK，不含源码。

Android preview build of Zhibai MiMo, based on the “Snow” (v2.6) release, plus Super Island and Agent questionnaire work. APK only — no source code in this release.

### ⚠️ 重要 / Important

- 全屏水印：`测试版非正式产出结果 + 机型 + 公网 IP`  
  Full-screen watermark: `TEST BUILD · unofficial output · device model · public IP`
- 关闭水印：在应用私有目录创建空文件 `nowatermark`（应用内不可关）  
  Disable watermark: create empty file `nowatermark` in the app private files dir (cannot be turned off in-app)
- Debug 签名，可能需卸载后重装  
  Debug-signed; you may need to uninstall the previous build first
- 非正式产出，勿当日常主力  
  Not for daily-driver use

---

## 本次 debug 额外更新 / What’s new in this debug build

### 超级岛 / Super Island

- 设置合集：开关岛、流式输出、阶段切换是否展开、权限清单  
  Settings hub: master switch, streaming text, expand-on-phase-change, permission checklist
- 权限四项全绿才正式上岛，返回自动复查  
  Island only starts after all four permissions are granted; re-checked on return from system settings
- 流式输出：约 4 次/秒把最新片段滚到岛胶囊，不重置计时、不反复重开岛  
  Streaming: latest snippet pushed to the capsule ~4×/s without resetting the timer or re-expanding
- 三金刚强提醒：需要授权 / 需要答题 / 回答完成 —— 强制展开 + 悬浮响铃  
  Attention states: **Auth needed / Quiz needed / Answers done** — force-expand + heads-up alert
- 问卷等待时岛上提示「需要答题，请回到 App」  
  Capsule shows “Quiz waiting — please return to the app”

### Agent 问卷 / Agent questionnaire

- `create_questionnaire` 全链路：AI 发问卷卡，提交后续传  
  Full pipeline: AI posts a questionnaire card; the run continues after you submit

### 界面 / UI

- 输入框下方免责声明  
  Disclaimer line under the input bar
- 滚动到底按钮与输入栏避让  
  Scroll-to-bottom button clear of the raised input bar
- 全屏测试水印（密铺斜纹）  
  Full-screen diagonal test watermark (dense tile)

---

## v2.6 雪 · 核心更新 / Snow (v2.6) highlights

- 磨砂玻璃输入栏（颜色 / 圆角 / 磨砂度可调）  
  Frosted-glass input bar (color / corner / blur adjustable)
- Agent 任务系统（雪花指示，完成下雪庆祝）  
  Agent task system (snowflake indicator, completion snow celebration)
- 思考等级 Off~MAX  
  Thinking levels Off~MAX
- 超大上下文（单次 32K，思考 65K）  
  Large context (32K per turn, 65K with thinking)
- 代码块、表格网格、公式修复  
  Code blocks, table grid, LaTeX overflow fixes
- 无线调试 ADB  
  Wireless debugging ADB
- 超级岛五状态胶囊、走秒、岛内审批  
  Five-phase Super Island capsule, chronometer, in-island approval
- 深色模式与性能优化  
  Dark mode and performance polish

---

## 安装 / Install

1. 下载 APK / Download the APK  
2. 允许未知来源安装 / Allow install from unknown sources  
3. 填写年龄、配置 API / Set age and configure API key  

## 反馈 / Feedback

请附机型、系统版本、复现步骤。截图请注意水印中的公网 IP。  
Please include device model, OS version, and repro steps. Watch the public IP in the watermark when sharing screenshots.
