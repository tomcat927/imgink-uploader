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
- **上传历史**：分页浏览账号内所有图片，复制链接 / 浏览器打开 / 删除
- **API 站点可配置**：换图床站点时无需改代码

## 下载安装

- **Release 版**：[Releases](../../releases) 页面下载 `app-release.apk`（tag 推送自动构建）
- **开发版**：Actions → 最近的 `Android Build` → Artifacts → `imgink-uploader-debug`（每次 push main 自动构建）

安装时允许"未知来源应用"即可。Android 8.0+（minSdk 26）。

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
- [ ] v0.2 通知栏快捷磁贴、二维码展示、失败自动重试
- [ ] v0.3 folder 分类上传、多账号管理
- [ ] v0.4 全自动化：B 端监听飞书群消息，agent 自动取链接分析

## 许可

[MIT](LICENSE)
