import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { WebSocket } from 'ws';
import { createSignalingServer } from '../src/index.js';

function request(ws, message, match) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { ws.off('message', listener); reject(new Error('Timed out')); }, 3000);
    function listener(data) {
      const reply = JSON.parse(data);
      if (!match(reply)) return;
      clearTimeout(timer); ws.off('message', listener); resolve(reply);
    }
    ws.on('message', listener); ws.send(JSON.stringify(message));
  });
}
const b64 = (value) => Buffer.from(value).toString('base64');
function bundle() {
  return { identityKey: b64('identity'), registrationId: 10,
    signedPreKey: { id: 1, publicKey: b64('signed'), signature: b64('signature') },
    kyberPreKey: { id: 1, publicKey: b64('kyber'), signature: b64('signature') },
    preKeys: [{ id: 1, publicKey: b64('one') }, { id: 2, publicKey: b64('two') }] };
}

test('identity-only lookups do not consume keys and updates/restarts never republish issued keys', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'line-prekeys-'));
  let server;
  const sockets = [];
  try {
    async function start() {
      server = await createSignalingServer({ dataFile: join(directory, 'keys.json'), env: {} });
      const address = await server.listen(0, '127.0.0.1');
      const ws = new WebSocket(`ws://127.0.0.1:${address.port}/signal`);
      await new Promise((resolve, reject) => { ws.once('open', resolve); ws.once('error', reject); });
      sockets.push(ws);
      const info = await request(ws, { type: 'register', token: 'e'.repeat(64), bundle: bundle() }, (m) => m.type === 'registered');
      return [ws, info.number];
    }
    let [ws, number] = await start();
    async function lookup(consumePreKey) {
      const requestId = randomUUID();
      return request(ws, { type: 'lookup', to: number, requestId, consumePreKey }, (m) => m.requestId === requestId);
    }
    for (let i = 0; i < 5; i++) assert.equal((await lookup(false)).type, 'bundle');
    assert.equal((await lookup(true)).bundle.preKey.id, 1);
    await request(ws, { type: 'keys', bundle: bundle() }, (m) => m.type === 'keys_updated');
    assert.equal((await lookup(true)).bundle.preKey.id, 2);
    await server.close();
    [ws, number] = await start();
    assert.equal((await lookup(true)).code, 'prekeys_exhausted');
    assert.equal((await lookup(false)).type, 'bundle');
  } finally {
    sockets.forEach((ws) => ws.terminate());
    await server?.close();
    await rm(directory, { recursive: true, force: true });
  }
});
