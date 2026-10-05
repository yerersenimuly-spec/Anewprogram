import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, test } from 'node:test';
import { createSignalingServer } from '../src/index.js';
import {
  LIVEKIT_ENV, account, base64, bundle, cleanup, closeSocket, openSocket, register, restart, sendRequest, setup, sleep, token, until, uuid,
} from '../test-support/harness.js';
import { createUserAgent, decryptWebPush, fakeApnsConnector, fakeHttpsRequest } from '../test-support/webpush.js';

afterEach(cleanup);

const endpointFile = (resource) => join(resource.directory, 'identities.json.push-endpoints.json');
const pushRegister = (socket, fields) => sendRequest(socket, { type: 'push_register', ...fields },
  (message) => message.type === 'push_registered' || message.type === 'error');
const unifiedPush = (endpoint, extra = {}) => ({ provider: 'unifiedpush', endpoint, ...extra });
const queued = (id) => (message) => (message.type === 'queued' && message.id === id) || message.type === 'error';
const bodyOf = (call) => JSON.parse(call.body.toString('utf8'));

async function writeApnsKey(directory) {
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const file = join(directory, 'AuthKey_TEST.p8');
  await writeFile(file, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
  return { APNS_KEY_FILE: file, APNS_KEY_ID: 'ABC123DEFG', APNS_TEAM_ID: 'TEAM123456', APNS_TOPIC: 'app.line' };
}

test('push providers are announced: UnifiedPush always, APNs only when fully configured', async () => {
  const plain = await account(await setup(), '1', 501, 8);
  assert.deepEqual(plain.info.pushProviders, ['unifiedpush']);

  const directory = await mkdtemp(join(tmpdir(), 'signal-apns-'));
  try {
    const env = await writeApnsKey(directory);
    const withApns = await account(await setup({ env, apns: { connect: fakeApnsConnector().connect } }), '2', 502, 8);
    assert.deepEqual(withApns.info.pushProviders, ['unifiedpush', 'apns']);
    for (const name of Object.keys(env)) {
      const partial = { ...env };
      delete partial[name];
      await assert.rejects(createSignalingServer({ dataFile: join(directory, 'identities.json'), env: partial }),
        /APNS_KEY_FILE, APNS_KEY_ID, APNS_TEAM_ID and APNS_TOPIC together/);
    }
    await assert.rejects(createSignalingServer({ dataFile: join(directory, 'identities.json'), env: { ...env, APNS_KEY_FILE: join(directory, 'none.p8') } }),
      /readable P-256 private key/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('endpoint registration enforces https, public hosts and well-formed keys, and reports the provider', async () => {
  const resource = await setup();
  const user = await account(resource, '1', 511, 8);
  const agent = createUserAgent();
  const refused = [
    'http://push.example.test/up', 'https://user:pw@push.example.test/up', 'https://localhost/up', 'https://127.0.0.1/up',
    'https://[::1]/up', 'https://10.1.1.1/up', 'https://169.254.169.254/latest', 'https://100.64.0.9/up', 'https://[fd00::5]/up',
    'https://intranet/up', 'https://push.example.test:0/up', 'ftp://push.example.test/up', '', 'x'.repeat(2_100),
  ];
  for (const endpoint of refused) {
    assert.deepEqual(await pushRegister(user.socket, unifiedPush(endpoint)), { type: 'error', code: 'invalid_push_endpoint' }, endpoint.slice(0, 50));
  }
  for (const fields of [unifiedPush('https://push.example.test/up', { pubKey: agent.registration.pubKey }),
    unifiedPush('https://push.example.test/up', { auth: agent.registration.auth }),
    unifiedPush('https://push.example.test/up', { pubKey: 'AAAA', auth: agent.registration.auth }),
    unifiedPush('https://push.example.test/up', { extra: true }), { provider: 'unifiedpush' }]) {
    assert.deepEqual(await pushRegister(user.socket, fields), { type: 'error', code: 'invalid_push_endpoint' });
  }
  const second = await account(resource, '2', 512, 8);
  assert.deepEqual(await pushRegister(second.socket, { provider: 'fcm', token: 'a'.repeat(40) }), { type: 'error', code: 'push_provider_unsupported' });
  assert.deepEqual(await pushRegister(second.socket, { provider: 'apns', token: 'a'.repeat(64) }), { type: 'error', code: 'push_provider_unsupported' });
  assert.deepEqual(await pushRegister(second.socket, { provider: 7, endpoint: 'x' }), { type: 'error', code: 'invalid_message' });
  await assert.rejects(readFile(endpointFile(resource)), (failure) => failure.code === 'ENOENT');

  const accepted = await pushRegister(second.socket, unifiedPush('https://push.example.test/up/ok', agent.registration));
  assert.deepEqual(accepted, { type: 'push_registered', pushEnabled: true, provider: 'unifiedpush' });
  assert.equal((await stat(endpointFile(resource))).mode & 0o777, 0o600);
  const document = JSON.parse(await readFile(endpointFile(resource), 'utf8'));
  assert.equal(document.version, 1);
  assert.deepEqual(document.endpoints[second.number], {
    provider: 'unifiedpush', endpoint: 'https://push.example.test/up/ok', ...agent.registration, registeredAt: document.endpoints[second.number].registeredAt,
  });

  await closeSocket(second.socket);
  await restart(resource);
  const again = await openSocket(resource);
  assert.equal((await register(again, token('2'), bundle(512, 0), 8)).pushEnabled, true);
  const legacy = await openSocket(resource);
  assert.equal((await register(legacy, token('2'), bundle(512, 0), 7)).pushEnabled, true);
});

test('PUSH_ALLOW_PRIVATE_ENDPOINTS permits private https hosts for self-hosted setups and nothing else', async () => {
  const resource = await setup({ env: { PUSH_ALLOW_PRIVATE_ENDPOINTS: 'true' } });
  const user = await account(resource, '1', 521, 8);
  for (const endpoint of ['https://127.0.0.1:8443/up', 'https://localhost/up', 'https://10.0.0.5/up', 'https://ntfy/up']) {
    assert.equal((await pushRegister(user.socket, unifiedPush(endpoint))).type, 'push_registered', endpoint);
  }
  for (const endpoint of ['http://127.0.0.1/up', 'https://user@127.0.0.1/up', 'https://127.0.0.1:0/up']) {
    assert.equal((await pushRegister(user.socket, unifiedPush(endpoint))).code, 'invalid_push_endpoint', endpoint);
  }
});

test('legacy token registrations answer pushEnabled false, push_unregister removes the endpoint, and registration is rate limited', async () => {
  const resource = await setup();
  const user = await account(resource, '1', 531, 8);
  assert.deepEqual(await pushRegister(user.socket, { token: 'fcm-device-token-0123456789abcdef' }), { type: 'push_registered', pushEnabled: false });
  assert.deepEqual(await sendRequest(user.socket, { type: 'push_register', token: 'x', extra: 1 }, (item) => item.type === 'error'),
    { type: 'error', code: 'invalid_message' });
  await assert.rejects(readFile(endpointFile(resource)), (failure) => failure.code === 'ENOENT');
  assert.deepEqual(await sendRequest(user.socket, { type: 'push_unregister' }, (item) => item.type === 'push_unregistered'), { type: 'push_unregistered' });
  await assert.rejects(readFile(endpointFile(resource)), (failure) => failure.code === 'ENOENT');

  await pushRegister(user.socket, unifiedPush('https://push.example.test/up/a'));
  assert.ok(JSON.parse(await readFile(endpointFile(resource), 'utf8')).endpoints[user.number]);
  assert.deepEqual(await sendRequest(user.socket, { type: 'push_unregister', extra: 1 }, (item) => item.type === 'error'),
    { type: 'error', code: 'invalid_message' });
  assert.deepEqual(await sendRequest(user.socket, { type: 'push_unregister' }, (item) => item.type === 'push_unregistered'), { type: 'push_unregistered' });
  assert.deepEqual(JSON.parse(await readFile(endpointFile(resource), 'utf8')).endpoints, {});
  await closeSocket(user.socket);
  const again = await openSocket(resource);
  assert.equal((await register(again, token('1'), bundle(531, 0), 8)).pushEnabled, false);

  const limited = await setup();
  const spammer = await account(limited, '2', 532, 8);
  for (let index = 0; index < 20; index += 1) {
    assert.equal((await pushRegister(spammer.socket, unifiedPush(`https://push.example.test/up/${index}`))).type, 'push_registered');
  }
  assert.deepEqual(await pushRegister(spammer.socket, unifiedPush('https://push.example.test/up/21')), { type: 'error', code: 'rate_limited' });
});

test('the UnifiedPush sender delivers the exact payload, encrypted for registered keys, with TTL and Urgency headers', async () => {
  const request = fakeHttpsRequest([201]);
  const resource = await setup({ env: LIVEKIT_ENV, unifiedPush: { request, retryDelayMs: 1 }, pushCoalesceMs: 0,
    createRoom: async () => {}, deleteRoom: () => {} });
  const sender = await account(resource, '1', 541, 8);
  const encrypted = await account(resource, '2', 542, 8);
  const clear = await account(resource, '3', 543, 8);
  const agent = createUserAgent();
  await pushRegister(encrypted.socket, unifiedPush('https://push.example.test/up/enc', agent.registration));
  await pushRegister(clear.socket, unifiedPush('https://push.example.test/up/clear'));
  await closeSocket(encrypted.socket);
  await closeSocket(clear.socket);

  const toEncrypted = { type: 'envelope', to: encrypted.number, id: uuid(40), cipherType: 3, body: base64('secret-ciphertext') };
  await sendRequest(sender.socket, toEncrypted, queued(toEncrypted.id));
  await until(() => request.calls.length === 1);
  const [first] = request.calls;
  assert.equal(first.options.hostname, 'push.example.test');
  assert.equal(first.options.path, '/up/enc');
  assert.equal(first.options.headers.ttl, '86400');
  assert.equal(first.options.headers.urgency, 'high');
  assert.equal(first.options.headers['content-encoding'], 'aes128gcm');
  assert.equal(decryptWebPush(first.body, agent).toString('utf8'), JSON.stringify({ kind: 'message', id: toEncrypted.id }));
  assert.equal(first.body.includes(Buffer.from(encrypted.number)), false);

  const toClear = { ...toEncrypted, to: clear.number, id: uuid(41) };
  await sendRequest(sender.socket, toClear, queued(toClear.id));
  await until(() => request.calls.length === 2);
  assert.equal(request.calls[1].options.path, '/up/clear');
  assert.equal(request.calls[1].body.toString('utf8'), JSON.stringify({ kind: 'message', id: toClear.id }));
  assert.equal(request.calls[1].body.toString('utf8').includes(sender.number), false);

  // Calls are never merged: two invitations and two endings are four distinct pushes.
  const kinds = [];
  for (let round = 0; round < 2; round += 1) {
    const created = sendRequest(sender.socket, { type: 'create_call', members: [clear.number] }, (item) => item.type === 'call_created' || item.type === 'error');
    const call = await created;
    assert.equal(call.type, 'call_created');
    await until(() => request.calls.length === 2 + round * 2 + 1);
    const ended = sendRequest(sender.socket, { type: 'leave_call', callId: call.callId }, (item) => item.type === 'ended');
    await ended;
    await until(() => request.calls.length === 2 + round * 2 + 2);
    kinds.push(bodyOf(request.calls[2 + round * 2]), bodyOf(request.calls[3 + round * 2]));
    assert.equal(request.calls[2 + round * 2].options.headers.ttl, '45');
  }
  assert.deepEqual(kinds.map((item) => item.kind), ['call', 'call_ended', 'call', 'call_ended']);
  assert.equal(kinds[1].reason, 'left');
  assert.deepEqual(Object.keys(kinds[0]).sort(), ['id', 'kind']);
  assert.deepEqual(Object.keys(kinds[1]).sort(), ['id', 'kind', 'reason']);
});

test('message pushes are coalesced per number within the window; the trailing push is dropped once the user is online', async () => {
  const request = fakeHttpsRequest([201]);
  const resource = await setup({ unifiedPush: { request, retryDelayMs: 1 }, pushCoalesceMs: 250 });
  const sender = await account(resource, '1', 551, 8);
  const recipient = await account(resource, '2', 552, 8);
  const other = await account(resource, '3', 553, 8);
  await pushRegister(recipient.socket, unifiedPush('https://push.example.test/up/r'));
  await pushRegister(other.socket, unifiedPush('https://push.example.test/up/o'));
  await closeSocket(recipient.socket);
  await closeSocket(other.socket);

  const ids = [uuid(50), uuid(51), uuid(52)];
  for (const id of ids) {
    await sendRequest(sender.socket, { type: 'envelope', to: recipient.number, id, cipherType: 3, body: base64(id) }, queued(id));
  }
  await until(() => request.calls.length === 1);
  assert.equal(bodyOf(request.calls[0]).id, ids[0]);
  const otherId = uuid(53);
  await sendRequest(sender.socket, { type: 'envelope', to: other.number, id: otherId, cipherType: 3, body: base64('o') }, queued(otherId));
  await until(() => request.calls.length === 2);
  assert.equal(request.calls[1].options.path, '/up/o');
  await sleep(100);
  assert.equal(request.calls.length, 2);
  await until(() => request.calls.length === 3, 1_000);
  assert.equal(request.calls[2].options.path, '/up/r');
  assert.equal(bodyOf(request.calls[2]).id, ids[2]);
  await sleep(600);
  assert.equal(request.calls.length, 3);

  const next = [uuid(54), uuid(55)];
  await sendRequest(sender.socket, { type: 'envelope', to: recipient.number, id: next[0], cipherType: 3, body: base64('n0') }, queued(next[0]));
  await until(() => request.calls.length === 4);
  await sendRequest(sender.socket, { type: 'envelope', to: recipient.number, id: next[1], cipherType: 3, body: base64('n1') }, queued(next[1]));
  const back = await openSocket(resource);
  await register(back, token('2'), bundle(552, 0), 8);
  await sleep(500);
  assert.equal(request.calls.length, 4);
});

test('the default coalescing window is 1.5 seconds', async () => {
  const request = fakeHttpsRequest([201]);
  const resource = await setup({ unifiedPush: { request, retryDelayMs: 1 } });
  const sender = await account(resource, '1', 561, 8);
  const recipient = await account(resource, '2', 562, 8);
  await pushRegister(recipient.socket, unifiedPush('https://push.example.test/up/default'));
  await closeSocket(recipient.socket);
  const startedAt = Date.now();
  for (const id of [uuid(60), uuid(61)]) {
    await sendRequest(sender.socket, { type: 'envelope', to: recipient.number, id, cipherType: 3, body: base64(id) }, queued(id));
  }
  await until(() => request.calls.length === 1);
  await sleep(1_000 - (Date.now() - startedAt));
  assert.equal(request.calls.length, 1);
  await until(() => request.calls.length === 2, 1_500);
  const elapsed = Date.now() - startedAt;
  assert.ok(elapsed >= 1_450 && elapsed < 2_600, `trailing push after ${elapsed} ms`);
  assert.equal(bodyOf(request.calls[1]).id, uuid(61));
});

test('providers that answer 404 or 410 drop the stored endpoint; other failures keep it', async () => {
  for (const [status, removed] of [[410, true], [404, true], [500, false], [400, false], [302, false]]) {
    const request = fakeHttpsRequest([status]);
    const resource = await setup({ unifiedPush: { request, retryDelayMs: 1 }, pushCoalesceMs: 0 });
    const sender = await account(resource, '1', 571, 8);
    const recipient = await account(resource, '2', 572, 8);
    await pushRegister(recipient.socket, unifiedPush('https://push.example.test/up/status'));
    await closeSocket(recipient.socket);
    const id = uuid(70);
    await sendRequest(sender.socket, { type: 'envelope', to: recipient.number, id, cipherType: 3, body: base64('x') }, queued(id));
    await until(() => request.calls.length >= 1);
    const endpoints = async () => JSON.parse(await readFile(endpointFile(resource), 'utf8')).endpoints;
    if (removed) await until(async () => Object.keys(await endpoints()).length === 0);
    else {
      await sleep(60);
      assert.deepEqual(Object.keys(await endpoints()), [recipient.number], `status ${status}`);
    }
    await cleanup();
  }
});

test('APNs registrations are stored per account and woken through the injected HTTP/2 connector', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'signal-apns-e2e-'));
  try {
    const env = await writeApnsKey(directory);
    const connector = fakeApnsConnector([{ status: 200 }, { status: 410, reason: 'Unregistered' }]);
    const resource = await setup({ env, apns: { connect: connector.connect, retryDelayMs: 1 }, pushCoalesceMs: 0 });
    const sender = await account(resource, '1', 581, 8);
    const recipient = await account(resource, '2', 582, 8);
    assert.deepEqual(await pushRegister(recipient.socket, { provider: 'apns', token: 'AB'.repeat(32), environment: 'sandbox' }),
      { type: 'push_registered', pushEnabled: true, provider: 'apns' });
    assert.equal((await pushRegister(recipient.socket, { provider: 'apns', token: 'zz' })).code, 'invalid_push_endpoint');
    assert.equal(JSON.parse(await readFile(endpointFile(resource), 'utf8')).endpoints[recipient.number].token, 'ab'.repeat(32));
    await closeSocket(recipient.socket);

    const id = uuid(80);
    await sendRequest(sender.socket, { type: 'envelope', to: recipient.number, id, cipherType: 3, body: base64('apns-secret') }, queued(id));
    await until(() => connector.requests.length === 1);
    const [request] = connector.requests;
    assert.deepEqual(connector.connected, ['https://api.sandbox.push.apple.com']);
    assert.equal(request.headers[':path'], `/3/device/${'ab'.repeat(32)}`);
    assert.equal(request.headers['apns-topic'], 'app.line');
    assert.deepEqual(request.body.kind, 'message');
    assert.equal(request.body.id, id);
    const serialized = JSON.stringify(request.body);
    assert.equal(serialized.includes(sender.number) || serialized.includes('apns-secret'), false);

    const second = uuid(81);
    await sendRequest(sender.socket, { type: 'envelope', to: recipient.number, id: second, cipherType: 3, body: base64('again') }, queued(second));
    await until(async () => Object.keys(JSON.parse(await readFile(endpointFile(resource), 'utf8')).endpoints).length === 0);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
