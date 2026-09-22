#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, rename, rm, stat, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';

// The large model is the default: far better Chinese recognition for real voices, at the cost of
// a ~1.27 GiB download/APK asset and about 2 GiB of storage and RAM on the phone. Pass --small
// for the lightweight build used on low-storage devices. Constants must match OfflineModelStore.
const MODELS = {
  large: {
    name: 'vosk-model-cn-0.22',
    url: 'https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip',
    bytes: 1_358_736_686,
    sha256: '7f5580bf7ca3d9e8ce8be68337378950ed78ef974df7a7d4ead8dbe32bec2fa1',
    sizeText: '约 1.27 GiB',
  },
  small: {
    name: 'vosk-model-small-cn-0.22',
    url: 'https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip',
    bytes: 43_898_754,
    sha256: '3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba',
    sizeText: '约 41.9 MiB',
  },
};

const useSmall = process.argv.includes('--small');
if (useSmall && process.argv.includes('--large')) throw new Error('--small 与 --large 只能选一个。');
const model = useSmall ? MODELS.small : MODELS.large;
const androidDirectory = dirname(fileURLToPath(import.meta.url));
const destination = join(androidDirectory, 'app', 'src', 'main', 'assets', 'voice-model-cn.zip');
const notice = join(androidDirectory, 'app', 'src', 'main', 'assets', 'voice-model-notice.txt');
// Keep unfinished downloads outside assets so an overlapping build cannot package them.
const temporary = join(androidDirectory, 'app', 'build', `voice-model-download-${process.pid}.zip.partial`);

async function matchesModel(path) {
  try {
    if ((await stat(path)).size !== model.bytes) return false;
    const hash = createHash('sha256');
    for await (const chunk of createReadStream(path)) hash.update(chunk);
    return hash.digest('hex') === model.sha256;
  } catch (error) {
    if (error.code === 'ENOENT') return false;
    throw error;
  }
}

async function writeNotice() {
  await writeFile(notice, [
    `Vosk Chinese speech model: ${model.name}`,
    'License: Apache License 2.0 (see licenses/VOSK-APACHE-2.0.txt)',
    `Source: ${model.url}`,
    `Archive bytes: ${model.bytes}`,
    `SHA-256: ${model.sha256}`,
    '',
  ].join('\n'), 'utf8');
}

async function main() {
  if (await matchesModel(destination)) {
    await writeNotice();
    console.log(`离线中文语音模型（${model.name}）已准备好，SHA-256 校验通过。`);
    return;
  }
  await mkdir(dirname(destination), { recursive: true });
  await mkdir(dirname(temporary), { recursive: true });
  console.log(`正在下载官方 Vosk 中文模型 ${model.name}（${model.sizeText}）。此步骤供开发者打包，安装 App 的用户无需另外下载。`);
  const response = await fetch(model.url, { redirect: 'error', signal: AbortSignal.timeout(60 * 60_000) });
  if (!response.ok || !response.body) throw new Error(`模型下载失败：HTTP ${response.status}`);
  const advertisedLength = response.headers.get('content-length');
  if (advertisedLength && Number(advertisedLength) !== model.bytes) {
    await response.body.cancel();
    throw new Error('官方模型文件大小发生变化，已停止。请先核实新文件及校验值。');
  }
  const hash = createHash('sha256');
  let received = 0;
  let reportedAt = 0;
  const validator = new Transform({
    transform(chunk, _encoding, callback) {
      received += chunk.length;
      if (received > model.bytes) { callback(new Error('模型文件超出固定大小限制。')); return; }
      hash.update(chunk);
      if (received - reportedAt >= 64 * 1024 * 1024) {
        reportedAt = received;
        console.log(`已下载 ${Math.floor(received * 100 / model.bytes)}%`);
      }
      callback(null, chunk);
    },
  });
  try {
    await pipeline(Readable.fromWeb(response.body), validator, createWriteStream(temporary, { flags: 'wx' }));
    if (received !== model.bytes || hash.digest('hex') !== model.sha256) {
      throw new Error('模型 SHA-256 或文件大小校验失败，未替换现有模型。');
    }
    await rm(destination, { force: true });
    await rename(temporary, destination);
    await writeNotice();
    console.log(`离线中文模型 ${model.name} 已下载并校验。现在可以编译 APK。`);
  } finally {
    await rm(temporary, { force: true });
  }
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
