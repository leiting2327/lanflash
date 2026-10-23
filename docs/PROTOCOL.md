# LanFlash 传输协议 v1

目标：在可信局域网内，用多路 TCP 把一批文件从发送端推到接收端，接近链路带宽上限，并对每个分块做完整性校验。

## 1. 配对

接收端启动监听后生成：

- 随机 16 字节 `token`（16 进制 32 字符），作为本次会话的共享密钥
- 监听端口 `port`
- 本机所有局域网 IPv4 地址 `ips`

展示二维码，内容为 UTF-8 JSON：

```json
{"v":1,"ips":["192.168.1.8"],"port":47821,"token":"3f9c...","name":"iPhone"}
```

发送端扫码后，依次尝试 `ips` 中的地址连接 `port`。所有连接都必须先发 `HELLO` 并携带 `token`，否则接收端回 `ERROR` 并断开。

## 2. 帧格式

所有整数为大端序。

```
+--------+----------------+---------------------+
| type   | payload length | payload             |
| 1 byte | 4 bytes (u32)  | length bytes        |
+--------+----------------+---------------------+
```

单帧 payload 上限 64 MB。类型：

| 值 | 名称 | 方向 | payload |
| --- | --- | --- | --- |
| 1 | `HELLO` | S→R | JSON `{v, token, stream, streams, name}` |
| 2 | `MANIFEST` | S→R（仅 stream 0） | JSON `{chunkSize, files:[{id,name,size}]}` |
| 3 | `DATA` | S→R | 20 字节头 + 数据 |
| 4 | `DONE` | S→R（仅 stream 0） | JSON `{total}` |
| 5 | `COMPLETE` | R→S（仅 stream 0） | JSON `{ok, missing:[int]}` |
| 6 | `ERROR` | 双向 | UTF-8 文本 |

`DATA` 头：

```
fileId   u32
offset   u64   # 文件内绝对偏移
crc32    u32   # 数据段的 CRC32（IEEE 多项式，与 zlib 一致）
dataLen  u32
data     bytes
```

## 3. 分块与并发

- 分块大小 `chunkSize` 默认 4 MiB
- 每个文件独立从 0 开始按 `chunkSize` 切块
- 全局块序号：按 `MANIFEST` 中文件顺序累加，第 0 个文件的块为 `0..n0-1`，第 1 个文件接着编号
- 连接数 `N` 默认 4（不超过总块数）
- 第 `i` 条连接负责全局序号 `k % N == i` 的所有块，按序号递增发送
- 接收端按 `fileId + offset` 定位写入，因此多条连接写同一文件的不同偏移互不干扰

接收端维护一个全局 `bitmap`：收到并通过 CRC 校验的块置位，同时累加已接收字节。重复块不会重复计数。

## 4. 会话流程

```
发送端                                         接收端
  |-- TCP connect × N --------------------------->|
  |-- HELLO(stream=i) × N ---------------------->| 校验 token
  |-- MANIFEST (stream 0) ---------------------->| 创建空文件、计算 bitmap
  |-- DATA × 全部块（分散在各连接） ------------>| 按 offset 写入 + CRC 校验
  |-- DONE (stream 0) -------------------------->| 检查 bitmap
  |<---------------------------- COMPLETE{ok:false, missing:[...]} （可选）
  |-- DATA(缺失块, stream 0) ------------------->|
  |-- DONE (stream 0) -------------------------->|
  |<---------------------------- COMPLETE{ok:true}
```

- `DONE` 最多重试 3 轮；接收端每次最多返回 128 个缺失块序号
- 任一条连接异常断开不影响其余连接，缺失块最终由 stream 0 补齐
- 接收端在 `COMPLETE{ok:true}` 之后关闭监听并落盘

## 5. 文件名与安全

- 接收端只取路径最后一段，去除控制字符；`/`、`\`、`..` 不能穿越目录
- 同名文件自动追加 ` (1)`、` (2)`
- 文件写入 App 私有目录（Android `Download/LanFlash`，iOS Documents/LanFlash）
- 协议本身不加密，安全性依赖二维码中的随机 token 与可信局域网

## 6. 激活令牌

服务端签发、客户端本地校验：

```
LF1.<base64url(payload JSON)>.<base64url(HMAC-SHA256)>
签名输入 = "LF1." + base64url(payload)
```

payload：

```json
{"did":"<设备标识>","plan":"pro","code":"LF-XXXX-XXXX-XXXX","exp":1767225600,"iat":1735689600}
```

- `did` 必须与当前设备指纹一致
- `exp` 为 Unix 秒，过期后本地判定失效
- 设备指纹：Android 为 `SHA256(ANDROID_ID|packageName)[:32]`；iOS 为 `SHA256(identifierForVendor|bundleId)[:32]`
- 令牌有效期由服务端 `TOKEN_DAYS` 控制，默认 365 天
- 激活码本身可单独设置过期时间与最大设备数；停用后新激活会被拒绝
