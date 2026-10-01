# ImgInk Uploader（图床直传）

基于 [img.ink](https://img.ink/index/api.html) API 的安卓图床客户端：Token 登录 → 拍照/相册/系统分享上传 → 一键复制链接 → **自动推送链接到飞书群**。

为多设备协同开发场景设计：测试机（A）上传截图后，任何一台开发设备（B/C/D）从飞书群里取链接交给 AI agent 分析即可。

```
设备A 安卓机                飞书群                     设备B/C/D 开发机
┌─────────────┐   webhook  ┌──────────┐   人工/后续自动化  ┌──────────────┐
│ 截图 → 分享  │ ─────────> │ 卡片消息  │ ────────────────> │ agent 下载图片 │
│ → 自动上传   │  img.ink   │ 含可复制  │   复制代码块链接   │ → 分析 → 解决  │
│ → 自动推送   │  CDN 链接  │ 的 URL    │                  │               │
└─────────────┘            └──────────┘                  └──────────────┘
```

## 功能

- **Token 登录**：粘贴 img.ink API Token 即可，自动校验有效性
- **三种上传入口**：相册选择 / 拍照 / 系统分享（任意应用「分享 → ImgInk 图床」，截图工作流丝滑）
- **上传进度**、成功后链接自动复制到剪贴板，支持 链接 / Markdown / HTML 三种格式
- **飞书自动推送**：上传成功自动发卡片消息到指定飞书群（链接放在代码块里，桌面端一键复制）；支持签名校验
- **上传归档**：可配置目标文件夹（默认 `imgink`，仅限英文字母数字），App 上传的图在图床上按目录集中管理
- **热更新**：启动自动检查新版本，App 内一键下载（gh-proxy 国内加速 + GitHub 直连双通道、断点续传）、SHA-256 校验后调起系统安装器
- **上传历史**：分页浏览账号内所有图片，复制链接 / 浏览器打开 / 删除
- **API 站点可配置**：换图床站点时无需改代码

## 下载安装 / 热更新

- **首次安装**：[Releases](../../releases) 下载最新 `imgink-uploader-vX.Y.Z-时间戳.apk` 安装（Android 8.0+）
- **热更新**：装好后无需再来 GitHub——App 启动时自动检查新版本（设置里可关），发现新版本弹窗一键下载，SHA-256 校验通过后调起系统安装器
- 每次构建生成带时间戳的版本号（versionCode = 构建时刻的 Unix 秒），`latest.json` 清单随 Release 发布，App 优先走 gh-proxy 国内加速、失败自动切 GitHub 直连，再回退 GitHub API
- CI 自动清理旧的时间戳 Release（保留最近 10 个），人工维护的固定 tag 不受影响

## 开发与发布流程

版本号策略（参照 notion-app-android）：

- `APP_VERSION`：固定为 `0.1.0`，自用软件不递增，版本区分靠构建时间戳
- `versionCode`：CI 构建时间戳（秒），永远单调递增，App 以此判断是否需要更新
- 版本 tag：`v0.1.0-{yyyyMMddHHmmss}`，由 CI 自动创建，时间戳即版本标识

## 配置指南

### 1. 获取 img.ink Token

img.ink → API 文档 → `POST /api/token`（邮箱 + 密码换取），或直接使用已有 token。App 登录页粘贴即可。

### 2. 配置飞书群机器人

1. 飞书群 → 设置 → 群机器人 → 添加 **自定义机器人**
2. 复制 Webhook URL，填入 App「设置」页
3. 安全设置（三选一）：
   - **签名校验**（推荐）：把密钥同时填入 App 的「签名密钥」
   - **自定义关键词**：设为 `图床`（App 消息标题固定包含该词）
   - ~~IP 白名单~~：不要开，手机网络 IP 不固定
4. 点「发送测试消息」验证

## 技术栈

Kotlin · Jetpack Compose (Material 3) · Retrofit/OkHttp · DataStore · Coil · GitHub Actions CI

## 开发

本地无需任何构建环境，全部由 GitHub Actions 完成：

- push `main` → 自动构建 debug APK 并上传 Artifact
- push tag `v*` → 构建 release APK 并创建 GitHub Release

版本号在 `app/build.gradle.kts` 的 `defaultConfig` 中维护。

**签名**：release APK 使用正式密钥签名，密钥以 GitHub Secrets 注入（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`），Keystore 本体备份在项目所有者的 Notion 与本地 `signing/` 目录（均已排除在 Git 之外，见 `.gitignore`）。本地没有签名环境变量时自动回退 debug 签名。密钥丢失将无法向已安装用户推送更新，请勿清空 GitHub Secrets。

## 路线图

- [x] v0.1.0 Token 登录、三种上传入口、飞书自动推送、上传历史、CI/CD
- [x] v0.2.0 正式签名（GitHub Secrets）+ 热更新（自动检查/加速下载/SHA-256 校验/一键安装）
- [ ] v0.3 通知栏快捷磁贴、二维码展示、失败自动重试、folder 分类上传
- [ ] v0.4 全自动化：B 端监听飞书群消息，agent 自动取链接分析

## 许可

[MIT](LICENSE)
