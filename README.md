# 闪电传 LanFlash

局域网直连的极速文件传输工具。手机对手机、手机对电脑，不经过云端，同一 Wi-Fi 下扫码即传。

本轮交付 **Android APK** 与 **iOS IPA**（由 GitHub Actions 云端构建），iOS 支持带证书签名或输出未签名 IPA 自行签名。

## 特性

- **4 路并发 TCP**：单文件也会被切成块并分散到多条连接，避免单连接跑不满带宽
- **4 MB 分块 + CRC32 校验**：任一连接中断，接收端可要求补齐缺失分块
- **二维码配对**：接收端展示二维码（内含 IP、端口、一次性会话密钥），发送端扫码直连
- **零云端**：文件只在局域网内点对点传输
- **联网激活**：激活码绑定设备，签发 HMAC 签名令牌，激活后可离线使用至令牌过期

## 目录结构

```
lanflash/                    # Gradle 根目录 = 仓库根目录
├── app/                     # Android APK（Kotlin + Jetpack Compose）
├── settings.gradle.kts
├── gradle/wrapper
├── ios/                     # SwiftUI + Network.framework
│   ├── project.yml          # XcodeGen 工程描述（CI 生成 .xcodeproj）
│   └── Sources/
├── server/                  # Node 激活服务（零第三方依赖，内置 SQLite）
├── docs/PROTOCOL.md         # 传输协议与激活令牌格式
└── .github/workflows/       # APK / IPA 自动构建
```

## 一、部署激活服务

要求 Node.js **>= 22.5**（使用内置 `node:sqlite`）。

```bash
cd server
npm run keygen
# 输出 LICENSE_SECRET / ADMIN_KEY，并写入 server/data/secret.env

export LICENSE_SECRET='<keygen 输出的值>'
export ADMIN_KEY='<keygen 输出的值>'
npm start                      # 监听 :8080

# 另开终端生成激活码
npm run gen -- --count 20 --days 365 --devices 2
npm run list
```

Docker：

```bash
docker build -t lanflash-license ./server
docker run -d -p 8080:8080 \
  -e LICENSE_SECRET='...' -e ADMIN_KEY='...' \
  -v lanflash-data:/data lanflash-license
```

接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/activate` | `{code, deviceId, platform}` → `{token, exp, plan}` |
| POST | `/api/v1/verify` | `{token, deviceId}` → `{ok, exp}` |
| GET | `/health` | 健康检查 |
| POST | `/api/v1/admin/codes` | 批量生成（需 `X-Admin-Key`） |
| GET | `/api/v1/admin/codes` | 查看激活码与已绑定设备数 |
| POST | `/api/v1/admin/disable` | 停用/恢复激活码 |

> `LICENSE_SECRET` 会同时编进 App，用来在本地校验令牌；它必须与构建 APK/IPA 时使用的值**完全一致**，否则激活成功也无法通过校验。

## 二、GitHub Actions 自动构建

推送到 `main` 或打 `v*` 标签即可触发；标签构建会自动把产物附加到 GitHub Release。

需要配置的 Secrets（`Settings → Secrets and variables → Actions`）：

| Secret | 必填 | 说明 |
| --- | --- | --- |
| `API_BASE_URL` | 是 | 激活服务地址，如 `https://license.example.com`，不带结尾 `/` |
| `LICENSE_SECRET` | 是 | 与服务器一致的签名密钥 |
| `ANDROID_KEYSTORE_BASE64` | 否 | `base64 -w0 release.jks`；不配则输出 debug 签名 APK |
| `ANDROID_STORE_PASSWORD` | 否 | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 否 | 签名别名 |
| `ANDROID_KEY_PASSWORD` | 否 | 签名密码 |
| `IOS_CERT_P12_BASE64` | 否 | 开发者证书 `.p12` 的 base64；不配则输出未签名 IPA |
| `IOS_CERT_PASSWORD` | 否 | p12 密码 |
| `IOS_PROVISION_BASE64` | 否 | `.mobileprovision` 的 base64 |
| `IOS_PROFILE_NAME` | 否 | 描述文件名称（Xcode 里显示的名称） |
| `IOS_TEAM_ID` | 否 | Apple Team ID |
| `IOS_BUNDLE_ID` | 否 | 必须与描述文件里的 App ID 一致，默认 `com.lanflash.app` |
| `IOS_EXPORT_METHOD` | 否 | `development`（默认）/ `ad-hoc` / `app-store` |

产物：

- Actions → `LanFlash-APK` / `LanFlash-IPA`
- 打标签时自动出现在 Release 附件

### iOS 未签名 IPA 的安装

未签名 IPA 无法直接双击安装，需要用 **Sideloadly / AltStore / TrollStore** 自签：

1. 电脑安装 Sideloadly，连接 iPhone
2. 拖入 `LanFlash-iOS-unsigned.ipa`
3. 填入自己的 Apple ID 或开发者证书后开始签名
4. 首次运行需在「设置 → 通用 → VPN 与设备管理」信任证书

未签名自签的免费账号有效期 7 天，到期重签即可；使用付费开发者证书（`IOS_CERT_P12_BASE64`）可一年有效。

## 三、本地构建

### Android

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug \
  -PapiBaseUrl=https://license.example.com \
  -PlicenseSecret='<LICENSE_SECRET>'
# app/build/outputs/apk/debug/app-debug.apk
```

正式签名再追加：

```bash
-PreleaseStoreFile=/abs/release.jks \
-PreleaseStorePassword=xxx \
-PreleaseKeyAlias=lanflash \
-PreleaseKeyPassword=xxx
```

### iOS

```bash
brew install xcodegen
cd ios && xcodegen generate
open LanFlash.xcodeproj
```

在 Xcode 里选择自己的 Team，或命令行：

```bash
xcodebuild -project LanFlash.xcodeproj -scheme LanFlash \
  -configuration Release -sdk iphoneos \
  -archivePath build/LanFlash.xcarchive archive \
  DEVELOPMENT_TEAM=XXXXXXXXXX \
  PRODUCT_BUNDLE_IDENTIFIER=com.your.bundle \
  CODE_SIGN_STYLE=Manual \
  CODE_SIGN_IDENTITY="Apple Development" \
  PROVISIONING_PROFILE_SPECIFIER="你的描述文件名" \
  API_BASE_URL=https://license.example.com \
  LICENSE_SECRET='<LICENSE_SECRET>'
```

## 四、使用

1. 两台设备连同一个 Wi-Fi（建议 5 GHz）
2. 接收端打开「接收文件」，保持页面在前台
3. 发送端打开「发送文件」→ 扫码 → 选择文件 → 开始发送
4. 接收端文件保存到 `Download/LanFlash`（iOS 为「文件」App 内的闪电传目录）

激活：首次启动输入激活码；同一激活码默认最多绑定 2 台设备（生成时用 `--devices` 调整）。

## 五、速度说明与调优

理论上限取决于链路：千兆有线 ≈ 118 MB/s，Wi-Fi 6 实测常见 60–110 MB/s，Wi-Fi 5 约 30–60 MB/s。

- 默认 4 条 TCP、4 MB 分块，可在 `Proto.DEFAULT_STREAMS` / `Proto.CHUNK_SIZE` 调整
- 尽量使用 5 GHz / Wi-Fi 6，避免 2.4 GHz 与蓝牙干扰
- 传输时保持 App 在前台（两端都做了常亮处理）
- 关闭 VPN / 代理类 App，它们会把局域网流量绕到隧道
- iOS 首次连接会弹「本地网络」权限，必须允许，否则无法发现设备

## 六、已知限制

- iOS 不允许普通 App 长期后台传输，退到后台可能中断；请保持前台
- 局域网传输未启用 TLS，依赖二维码中的一次性会话密钥；请仅在可信网络使用
- 激活令牌使用 HMAC 对称密钥并在客户端校验，属于「防普通用户」级别；如需强防破解，应改为服务端签发非对称签名并缩短令牌有效期
- 本轮未包含 Windows EXE，协议见 `docs/PROTOCOL.md`，可据此实现 C#/Rust 客户端
- 未做断点续传（已收到分块位图仅在单次会话内用于重传）

## 七、故障排查

| 现象 | 排查 |
| --- | --- |
| 扫不到 / 连不上 | 是否同网段；路由器是否开了 AP 隔离；iOS 是否拒绝本地网络权限 |
| 激活失败 HTTP 404 | `API_BASE_URL` 是否指向服务端且已启动 |
| 激活后提示令牌校验失败 | APK/IPA 的 `LICENSE_SECRET` 与服务端不一致 |
| 传输速度只有几 MB/s | 是否 2.4 GHz、是否开了 VPN、是否跨 VLAN |
