import 'dotenv/config';
import { createApp } from './app';
import { readServerConfig } from './config';

const config = readServerConfig();
const app = createApp(config.options);
const server = app.listen(config.port, config.host, () => {
  console.log(`听见世界描述服务已启动：http://${config.host}:${config.port}`);
  console.log(`真实识别：${config.options.provider} / ${config.options.model}`);
  if (!config.options.apiKey) console.log(`尚未配置 ${config.keyVariable}：健康检查可用，真实描述暂不可用。`);
  if (!config.options.accessToken) console.log('当前未设置连接口令，仅用于受信任的本地开发网络。公开部署前请启用访问控制和 HTTPS。');
});
server.requestTimeout = 75_000;
server.headersTimeout = 15_000;
for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.on(signal, () => {
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(1), 5000).unref();
  });
}
