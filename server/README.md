# 描述服务

Node.js 20.19+；使用环境代理启动命令时需要 24.5+。密钥只放服务端，不填写到手机。

## 启动

在项目根目录安装依赖，首次将 `.env.example` 复制为 `.env`，配置下表后运行：

```powershell
npm install
npm run server
```

| 配置 | 用途／默认值 |
| --- | --- |
| `AI_PROVIDER` | `openai`（默认）或 `gemini` |
| `OPENAI_API_KEY` / `GEMINI_API_KEY` | 所选提供方的密钥，也可从进程环境继承 |
| `OPENAI_MODEL` | 默认 `gpt-4.1-mini` |
| `GEMINI_MODEL` | 默认 `gemini-2.5-flash`；本机演示用 `gemini-3.8-flash` |
| `HOST` / `PORT` | 默认 `0.0.0.0:8787`；本机演示用 `127.0.0.1:8788` |
| `APP_ACCESS_TOKEN` | 可选连接口令；手机设置中填写同一口令 |
| `CORS_ORIGINS` | 允许的网页来源，以逗号分隔；原生 App 通常不发送 Origin |

普通进程用 `npm run server:start`。需要代理时，在 `.env` 设置 `HTTPS_PROXY`、`HTTP_PROXY` 和 `NO_PROXY=localhost,127.0.0.1,10.0.2.2`，运行 `npm run server:local`（Node.js 24.5+）。代理地址填写本机实际地址；不会修改系统代理。

模拟器连接 `http://10.0.2.2:端口`。真机连接电脑局域网 IP，服务须监听 `0.0.0.0`，并允许对应端口访问。已有配置无需覆盖。

## 接口

| 接口 | 说明 |
| --- | --- |
| `GET /api/health` | 返回 `{status: "ok", configured: true}`；仅确认已配置密钥，不保证模型连通 |
| `POST /api/describe` | 接收图片或视频帧，返回标题、摘要、细节、文字、时间线及不确定之处 |

设置连接口令后，两项接口都需请求头 `Authorization: Bearer <口令>`。错误统一返回 `{error, code}`。

```json
{
  "mediaType": "image",
  "mode": "brief",
  "frames": [{ "dataUrl": "data:image/jpeg;base64,...", "timestampMs": 0 }]
}
```

- `mode`：`brief`、`detailed`、`text`；可选 `question` 最多 500 字。
- 图片一帧、时间戳为 0；视频需 `durationMs`（最多 120000），最多 12 帧，时间戳递增且小于时长。
- 仅内联 JPEG／PNG／WebP：单帧 2 MB、总计 8 MB、请求体 12 MB；不接收远程 URL。
- 完整类型见 [contracts.ts](../shared/contracts.ts)，输入与输出校验见 [schema.ts](schema.ts)。

## 运行约定

- 同一 IP 每分钟 12 次，最多 4 个并发请求；60 秒超时或客户端取消时中止模型调用。
- 只调用选定提供方，失败直接报错；正式服务不会返回预设演示内容。
- 图片与问题发送给选定的 AI 提供方；服务不记录内容，调用设置 `store: false`，供应商保留规则依其条款。
- 公网部署使用 HTTPS 和访问控制。共享口令不等于用户账号系统；内存限流不适用于多实例共享配额。

构建与测试见[开发说明](../docs/development.md)。接口依据：[OpenAI 图像](https://developers.openai.com/api/docs/guides/images-vision)、[结构化输出](https://developers.openai.com/api/docs/guides/structured-outputs)、[Gemini 图像](https://ai.google.dev/gemini-api/docs/generate-content/image-understanding)、[结构化输出](https://ai.google.dev/gemini-api/docs/generate-content/structured-output)。
