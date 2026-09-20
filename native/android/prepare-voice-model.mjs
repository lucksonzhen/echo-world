#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, rename, rm, stat } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';

const MODEL_URL = 'https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip';
const MODEL_BYTES = 43_898_754;
const MODEL_SHA256 = '3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba';
const androidDirectory = dirname(fileURLToPath(import.meta.url));
const destination = join(androidDirectory, 'app', 'src', 'main', 'assets', 'voice-model-cn.zip');
// Keep unfinished downloads outside assets so an overlapping build cannot package them.
const temporary = join(androidDirectory, 'app', 'build', `voice-model-download-${process.pid}.zip.partial`);

async function matchesModel(path) {
  try {
    if ((await stat(path)).size !== MODEL_BYTES) return false;
    const hash = createHash('sha256');
    for await (const chunk of createReadStream(path)) hash.update(chunk);
    return hash.digest('hex') === MODEL_SHA256;
  } catch (error) {
    if (error.code === 'ENOENT') return false;
    throw error;
  }
}

async function main() {
  if (await matchesModel(destination)) {
    console.log('离线中文语音模型已准备好，SHA-256 校验通过。');
    return;
  }
  await mkdir(dirname(destination), { recursive: true });
  await mkdir(dirname(temporary), { recursive: true });
  console.log('正在下载官方 Vosk 中文模型（约 42 MiB）。此步骤供开发者打包，安装 App 的用户无需另外下载。');
  const response = await fetch(MODEL_URL, { redirect: 'error', signal: AbortSignal.timeout(15 * 60_000) });
  if (!response.ok || !response.body) throw new Error(`模型下载失败：HTTP ${response.status}`);
  const advertisedLength = response.headers.get('content-length');
  if (advertisedLength && Number(advertisedLength) !== MODEL_BYTES) {
    await response.body.cancel();
    throw new Error('官方模型文件大小发生变化，已停止。请先核实新文件及校验值。');
  }
  const hash = createHash('sha256');
  let received = 0;
  let reportedAt = 0;
  const validator = new Transform({
    transform(chunk, _encoding, callback) {
      received += chunk.length;
      if (received > MODEL_BYTES) { callback(new Error('模型文件超出固定大小限制。')); return; }
      hash.update(chunk);
      if (received - reportedAt >= 4 * 1024 * 1024) {
        reportedAt = received;
        console.log(`已下载 ${Math.floor(received * 100 / MODEL_BYTES)}%`);
      }
      callback(null, chunk);
    },
  });
  try {
    await pipeline(Readable.fromWeb(response.body), validator, createWriteStream(temporary, { flags: 'wx' }));
    if (received !== MODEL_BYTES || hash.digest('hex') !== MODEL_SHA256) {
      throw new Error('模型 SHA-256 或文件大小校验失败，未替换现有模型。');
    }
    await rename(temporary, destination);
    console.log('离线中文模型已下载并校验。现在可以编译 APK。');
  } finally {
    await rm(temporary, { force: true });
  }
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
