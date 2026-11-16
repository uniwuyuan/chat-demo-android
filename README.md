# chat-demo-android

把 [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo) 的 web 产物装进一个最小 Android 外壳，
用 WebView 跑成能装的 APK。外壳只做 web 做不到的事，其余全部留在 web 层。

## 仓库关系

```
chat-demo/           web 本体（fork 上游，可随时提 PR）
chat-demo-android/   本仓库：APK 外壳，消费 chat-demo/dist，不进上游
```

外壳与页面之间只有一个契约 `window.AndroidShell`：

```ts
type Bridge = {
  saveFile?(name: string, mime: string, base64: string): void
  listBackups?(): string                                  // JSON [{name,size,modified}]
  deleteBackups?(namesJson: string): number
  readBackupChunk?(name: string, offset: number, length: number): string | null
}
```

页面侧用 `hasNativeBackups()` 判定，浏览器里自动退化成「普通下载 + 本地索引」。

## 构建

```sh
# 1) 先生成 web 产物
cd ../chat-demo && pnpm install && pnpm build

# 2) 打 APK（本机 arm64 方案：apt 的 aapt + 官方 d8.jar + apksigner）
cd ../chat-demo-android && ./build.sh
```

产物：`dist/chat-demo-<version>.apk`，同时拷贝到 `/sdcard/Download/DSH-Go/`。

本机是 arm64，官方 build-tools 里的 aapt2 只有 x86_64，所以脚本用 apt 的 `aapt`/`zipalign`/`apksigner`
配合官方 `d8.jar`（纯 Java）。`PLATFORM`、`D8_JAR`、`WEB` 三个变量可用环境变量覆盖。

---

把 [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo) 的静态产物（`/root/workspace/chat-demo/dist`）装进一个最小 Android 工程，
用 WebView 跑成能装到手机桌面的 App。设置页底部多了一行「致谢 DeepSeek 的开源」。

## 产物

- `dist/chat-demo-0.2.2.apk`（已签名，v2/v3，versionCode 4）
- 手机里同一份：`/sdcard/Download/DSH-Go/chat-demo-0.2.2.apk`

## 安装

文件管理 → Download/DSH-Go/chat-demo-0.2.2.apk → 安装（首次会要求允许「未知来源」）。
装好后桌面图标名是 **DeepSeek Chat Demo**，首次进入在 设置 → 数据管理 里填 API Key。

## 斜杠命令（0.2.0 加入）

输入框输入 `/` 会出现命令面板，输入命令名即时过滤：

| 命令 | 作用 |
| --- | --- |
| `/compact` | 较早历史压成摘要，保留最近原文；调用一次 API 生成摘要 |
| `/new` | 回到欢迎页开新对话 |
| `/clear` | 确认后删除当前对话及其附件 |
| `/export` | 导出全部历史 ZIP（APK 内会落到系统下载目录） |
| `/help` | 提示输入 `/` 看命令 |

判定规则：只认「行首紧贴斜杠 + 已注册命令名」。`/ 内容`（斜杠后空格）、`／compact`（全角）、`` `/compact` ``、`/usr/bin` 都会当普通消息发给模型；`/compact 参数` 会提示用法错误。

实现参考 `@deepseek-ai/dsh-compaction-basic`：`thresholdRatio 0.8`、`retainRatio 0.16`，从尾部往前留原文，其余整段摘要后替换。区别是 token 用字符数/2 粗估、摘要走流式收集、没有检查点日志。

代码位置：`src/services/commands/index.ts`（命令表与解析）、`src/services/chat/compact.ts`（压缩）、`src/services/chat/store.ts`（`getVisibleSnapshot` / `applyCompaction`）、`src/components/chatInput/ChatInput.tsx`（拦截与面板）。

## 日志（0.2.2 加入）

设置 → 日志：顶部是「记录已开启 / 记录已关闭」开关（默认开启，存在 localStorage 的 `chat-demo:debug-log`），
下面可按级别（all/info/warn/error）过滤、按来源或内容搜索，右侧是导出 / 复制 / 清空。

记录范围：应用启动信息、未捕获异常与 Promise 拒绝、导入全链路（打开选择器 → 选中文件的名字/大小/类型 →
格式校验 → 逐条目读取 → 暂存 → 替换）、`/compact` 摘要请求，以及**原生外壳事件**（`onShowFileChooser`、
`onActivityResult` 拿到的文件数、录音授权）。原生事件经 `window.__chatDemoLog` 汇入同一份日志。

日志落 localStorage（上限 120 KB，超出丢最早的），刷新或崩溃后还能看到上一轮记录。

导入失败时，日志里这一组能直接定位卡点：

```
[import] 打开文件选择器
[shell]  原生文件选择器被调用
[shell]  文件选择返回 resultCode=-1 文件数=0      ← 直接取消了
[import] 已选择文件 {name,size,type}
[import] 开始校验归档：xxx.zip
[import] 格式校验失败 / 格式校验通过
[import] zip 读取完成 {entries,sessions,images}
[import] 已替换本地历史 {sessions}
```

## 为什么用 https://appassets.androidplatform.net

直接用 `file:///android_asset/index.html` 打开时，WebView 会禁掉 localStorage / IndexedDB / getUserMedia，
对话历史和语音都会坏。这里在 `shouldInterceptRequest` 里把这个虚拟 https 源的请求映射到 assets，
等于给网页一个正常的安全源：存储、录音、剪贴板都按线上站点的方式工作。

## 目录

```
app/src/main/AndroidManifest.xml      minSdk 24 / targetSdk 34，INTERNET + RECORD_AUDIO
app/src/main/java/.../MainActivity.java  WebView 外壳：资产映射、文件选择、录音授权、返回键、下载落盘
app/src/main/res/                     应用名、图标（PIL 生成）
app/src/main/assets/                  构建时从 chat-demo/dist 复制进来
app/patch.js                         注入脚本：把 blob: 下载转成 base64 交给 Java 存到「下载」
build.sh                              一键构建
keystore.jks                          自签名证书（口令 chatdemo）
```

## 外壳补齐的几件事

| 能力 | 做法 |
| --- | --- |
| 附件上传 | `onShowFileChooser` → 系统文件选择器，支持多选 |
| 语音输入 | `onPermissionRequest` 只放行 `RESOURCE_AUDIO_CAPTURE`，并申请 RECORD_AUDIO |
| 导出历史 ZIP | 页面里 `a[download]` 的 blob 被注入脚本读成 base64，走 `AndroidShell.saveFile` 写进系统下载目录 |
| 外链 | 非本应用源的链接交给系统浏览器 |
| 返回键 | 先给页面发 Esc（关设置等弹窗），再退历史，最后退出 |

## 重新构建

```sh
cd /root/workspace/chat-demo && pnpm build      # 网页产物 → dist
cd /root/workspace/chat-demo-android && ./build.sh
```

脚本用 apt 的 `aapt` / `zipalign` / `apksigner`（arm64）加官方 `d8.jar`（纯 Java），
因为官方 build-tools 里的 aapt2 只有 x86_64 版本，在这台 arm64 手机上跑不起来。
`PLATFORM`、`D8_JAR`、`WEB` 都可以用环境变量覆盖。

## 网页侧改动（chat-demo 仓库内）

- `src/i18n/zh.ts` / `en.ts`：新增 `settingsCreditDeepSeek`、`settingsCreditDeepSeekRepo`
- `src/components/settings/SettingsDialog.tsx`：给 `Modal` 传 `footer`，两个分组都显示致谢行
- `src/components/settings/Settings.module.css` + `.d.ts`：`.credit` / `.creditLink` 样式
