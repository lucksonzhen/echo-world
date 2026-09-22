import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readServerConfig } from '../server/config';

test('provider selection uses only the selected provider key and model', () => {
  const env = { OPENAI_API_KEY: ' openai-test ', GEMINI_API_KEY: ' gemini-test ',
    OPENAI_MODEL: ' openai-model ', GEMINI_MODEL: ' gemini-model ' };
  const openai = readServerConfig(env);
  assert.equal(openai.options.provider, 'openai');
  assert.equal(openai.options.apiKey, 'openai-test');
  assert.equal(openai.options.model, 'openai-model');
  const gemini = readServerConfig({ ...env, AI_PROVIDER: 'gemini', PORT: '8788', HOST: '127.0.0.1' });
  assert.equal(gemini.options.provider, 'gemini');
  assert.equal(gemini.options.apiKey, 'gemini-test');
  assert.equal(gemini.options.model, 'gemini-model');
  assert.equal(gemini.port, 8788);
  assert.equal(gemini.host, '127.0.0.1');
  assert.equal(readServerConfig({ AI_PROVIDER: 'gemini', OPENAI_API_KEY: 'other' }).options.apiKey, undefined);
  assert.equal(readServerConfig({ GEMINI_API_KEY: 'other' }).options.apiKey, undefined);
});

test('invalid provider and port fail before starting the service', () => {
  assert.throws(() => readServerConfig({ AI_PROVIDER: 'typo' }), /AI_PROVIDER/);
  for (const port of ['0', '65536', 'abc', '1.5', '']) {
    assert.throws(() => readServerConfig({ PORT: port }), /PORT/);
  }
});

test('secrets under the client-bundled EXPO_PUBLIC_ prefix refuse to start', () => {
  for (const name of ['EXPO_PUBLIC_OPENAI_API_KEY', 'EXPO_PUBLIC_GEMINI_APIKEY', 'EXPO_PUBLIC_APP_ACCESS_TOKEN', 'EXPO_PUBLIC_SECRET']) {
    assert.throws(() => readServerConfig({ OPENAI_API_KEY: 'k', [name]: 'leaked' }), new RegExp(name));
  }
  // Non-secret public settings and empty placeholders remain allowed.
  const config = readServerConfig({ OPENAI_API_KEY: 'k', EXPO_PUBLIC_API_URL: 'http://localhost:8787', EXPO_PUBLIC_OPENAI_API_KEY: '  ' });
  assert.equal(config.options.apiKey, 'k');
});
