import assert from 'node:assert/strict';
import { createECDH, generateKeyPairSync, verify } from 'node:crypto';
import { createServer } from 'node:net';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { mock, test } from 'node:test';
import {
  createApnsSender, createCoalescer, createSafeLookup, createUnifiedPushSender, encryptWebPushPayload, isPublicAddress,
  parseEndpoint, parsePushRegistration, readApnsConfig, wirePayload,
} from '../src/push.js';
import { readBooleanSetting, readIntegerSetting } from '../src/common.js';
import { createUserAgent, decryptWebPush, fakeApnsConnector, fakeHttpsRequest } from '../test-support/webpush.js';

const ID = '3f9c1d52-8a64-4b7e-9d10-2c5e7a1b6f08';
const MESSAGE = { kind: 'message', id: ID, ttlMs: 1_000 };
const settle = () => new Promise((resolve) => setImmediate(resolve));

test('RFC 8291 Appendix A vector: the encrypted record matches byte for byte', () => {
  const decode = (value) => Buffer.from(value, 'base64url');
  const ephemeral = createECDH('prime256v1');
  ephemeral.setPrivateKey(decode('yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw'));
  assert.equal(ephemeral.getPublicKey().toString('base64url'),
    'BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8');
  const body = encryptWebPushPayload(Buffer.from('When I grow up, I want to be a watermelon'), {
    userPublicKey: decode('BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4'),
    authSecret: decode('BTBZMqHH6r4Tts7J_aSIgg'),
    salt: decode('DGv6ra1nlYgDCS1FRnbzlw'),
    ephemeral,
  });
  assert.equal(body.toString('base64url'),
    'DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN');
});

test('web-push encryption round-trips with fresh randomness and refuses oversized payloads', () => {
  const agent = createUserAgent();
  const plaintext = Buffer.from(JSON.stringify({ kind: 'message', id: ID }));
  const encrypt = () => encryptWebPushPayload(plaintext, { userPublicKey: agent.keys.getPublicKey(), authSecret: agent.authSecret });
  const first = encrypt();
  const second = encrypt();
  assert.notDeepEqual(first, second);
  assert.deepEqual(decryptWebPush(first, agent), plaintext);
  assert.deepEqual(decryptWebPush(second, agent), plaintext);
  assert.ok(first.length <= 4_096);
  assert.throws(() => encryptWebPushPayload(Buffer.alloc(4_096), {
    userPublicKey: agent.keys.getPublicKey(), authSecret: agent.authSecret,
  }), /too large/);
});

test('push payloads carry exactly kind, id and, for call_ended, a known reason', () => {
  assert.deepEqual(wirePayload({ kind: 'message', id: ID, ttlMs: 1, from: '12345678' }), { kind: 'message', id: ID });
  assert.deepEqual(wirePayload({ kind: 'call', id: ID, ttlMs: 45_000, reason: 'left' }), { kind: 'call', id: ID });
  assert.deepEqual(wirePayload({ kind: 'call_ended', id: ID, ttlMs: 1, reason: 'declined' }),
    { kind: 'call_ended', id: ID, reason: 'declined' });
  assert.deepEqual(wirePayload({ kind: 'call_ended', id: ID, ttlMs: 1, reason: '12345678' }), { kind: 'call_ended', id: ID });
});

test('address classification blocks private, loopback, link-local, CGNAT, ULA and reserved ranges', () => {
  const blocked = [
    '0.0.0.0', '10.1.2.3', '100.64.0.1', '100.127.255.254', '127.0.0.1', '127.255.0.9', '169.254.169.254',
    '172.16.0.1', '172.31.255.255', '192.0.0.8', '192.0.2.1', '192.168.1.1', '198.18.0.1', '198.19.255.255',
    '198.51.100.7', '203.0.113.9', '224.0.0.1', '240.0.0.1', '255.255.255.255',
    '::', '::1', 'fe80::1', 'fc00::1', 'fd12:3456:789a::1', 'ff02::1', '2001:db8::1', '2002:c0a8:101::1',
    '::ffff:127.0.0.1', '::ffff:10.0.0.1', '::ffff:169.254.169.254', '64:ff9b::7f00:1', '64:ff9b::a00:1',
  ];
  for (const address of blocked) assert.equal(isPublicAddress(address), false, address);
  const allowed = ['1.1.1.1', '8.8.8.8', '93.184.216.34', '100.63.255.255', '172.15.0.1', '172.32.0.1',
    '192.0.1.1', '2606:4700:4700::1111', '2a00:1450:4001:81b::200e', '::ffff:8.8.8.8'];
  for (const address of allowed) assert.equal(isPublicAddress(address), true, address);
  for (const junk of ['', 'localhost', '1.2.3', '256.1.1.1', undefined, null, 5]) assert.equal(isPublicAddress(junk), false, String(junk));
});

test('endpoint validation accepts only plain https URLs on public hosts', () => {
  const accepted = [
    'https://push.example.test/up/abc', 'https://push.example.test:8443/up?token=1', 'https://ntfy.example.org/UpAbCdEf',
    'https://1.1.1.1/up', 'https://[2606:4700:4700::1111]/up',
  ];
  for (const endpoint of accepted) assert.ok(parseEndpoint(endpoint), endpoint);
  const rejected = [
    'http://push.example.test/up', 'ftp://push.example.test/up', 'wss://push.example.test/up',
    'https://user@push.example.test/up', 'https://user:pass@push.example.test/up', 'https://:pass@push.example.test/up',
    'https://push.example.test@evil.test/up', 'https://localhost/up', 'https://foo.localhost/up', 'https://intranet/up',
    'https://127.0.0.1/up', 'https://127.1/up', 'https://2130706433/up', 'https://0x7f.0.0.1/up', 'https://[::1]/up',
    'https://[::ffff:127.0.0.1]/up', 'https://10.0.0.1/up', 'https://100.64.0.1/up', 'https://169.254.169.254/latest',
    'https://192.168.0.10/up', 'https://[fd00::1]/up', 'https://[fe80::1%25eth0]/up', 'https://push.example.test:0/up',
    'https://push.example.test/up with space', 'https://push.example.test/\u044f', `https://push.example.test/${'a'.repeat(2_100)}`,
    'https://', 'https:///up', '', 'push.example.test/up', 5, null, undefined,
  ];
  for (const endpoint of rejected) assert.equal(parseEndpoint(endpoint), undefined, String(endpoint));
  assert.ok(parseEndpoint('https://127.0.0.1:8443/up', true));
  assert.ok(parseEndpoint('https://localhost/up', true));
  assert.ok(parseEndpoint('https://intranet/up', true));
  assert.equal(parseEndpoint('http://127.0.0.1/up', true), undefined);
  assert.equal(parseEndpoint('https://user@127.0.0.1/up', true), undefined);
});

test('registration parsing validates keys, providers and unknown fields', () => {
  const agent = createUserAgent();
  const base = { type: 'push_register', provider: 'unifiedpush', endpoint: 'https://push.example.test/up/1' };
  assert.deepEqual(parsePushRegistration(base), { record: { provider: 'unifiedpush', endpoint: base.endpoint } });
  assert.deepEqual(parsePushRegistration({ ...base, ...agent.registration }).record,
    { provider: 'unifiedpush', endpoint: base.endpoint, ...agent.registration });
  const code = (message, options) => parsePushRegistration(message, options).error;
  const standard = (value) => Buffer.from(value, 'base64url').toString('base64');
  assert.deepEqual(parsePushRegistration({ ...base, pubKey: standard(agent.keys.getPublicKey()), auth: standard(agent.authSecret) }).record,
    { provider: 'unifiedpush', endpoint: base.endpoint, ...agent.registration });
  assert.equal(code({ ...base, pubKey: agent.registration.pubKey }), 'invalid_push_endpoint');
  assert.equal(code({ ...base, auth: agent.registration.auth }), 'invalid_push_endpoint');
  assert.equal(code({ ...base, pubKey: agent.registration.pubKey, auth: 'AAAA' }), 'invalid_push_endpoint');
  assert.equal(code({ ...base, pubKey: Buffer.alloc(65, 4).toString('base64url'), auth: agent.registration.auth }), 'invalid_push_endpoint');
  assert.equal(code({ ...base, extra: 1 }), 'invalid_push_endpoint');
  assert.equal(code({ ...base, endpoint: 'http://push.example.test/up' }), 'invalid_push_endpoint');
  assert.equal(code({ type: 'push_register', provider: 'apns', token: 'a'.repeat(64) }), 'push_provider_unsupported');
  assert.equal(code({ type: 'push_register', provider: 'fcm', token: 'a'.repeat(64) }, { apnsEnabled: true }), 'push_provider_unsupported');
  assert.deepEqual(parsePushRegistration({ type: 'push_register', provider: 'apns', token: 'AB'.repeat(32), environment: 'sandbox' },
    { apnsEnabled: true }).record, { provider: 'apns', token: 'ab'.repeat(32), environment: 'sandbox' });
  assert.equal(code({ type: 'push_register', provider: 'apns', token: 'xyz' }, { apnsEnabled: true }), 'invalid_push_endpoint');
  assert.equal(code({ type: 'push_register', provider: 'apns', token: 'a'.repeat(64), environment: 'moon' }, { apnsEnabled: true }),
    'invalid_push_endpoint');
});

test('the DNS lookup used for push connections refuses any answer that includes a forbidden address', async () => {
  const answer = (...addresses) => (hostname, options, callback) => callback(null, addresses.map((address) => ({
    address, family: address.includes(':') ? 6 : 4,
  })));
  const call = (lookup, options = {}) => new Promise((resolve) => {
    lookup('push.example.test', options, (failure, address, family) => resolve({ failure, address, family }));
  });

  const strict = createSafeLookup({ resolve: answer('93.184.216.34') });
  assert.deepEqual(await call(strict), { failure: null, address: '93.184.216.34', family: 4 });
  const all = await new Promise((resolve) => {
    strict('push.example.test', { all: true }, (failure, addresses) => resolve({ failure, addresses }));
  });
  assert.deepEqual(all.addresses, [{ address: '93.184.216.34', family: 4 }]);

  for (const addresses of [['127.0.0.1'], ['10.0.0.8'], ['169.254.169.254'], ['fd00::1'], ['93.184.216.34', '10.0.0.8'],
    ['::ffff:192.168.1.1'], []]) {
    const refused = await call(createSafeLookup({ resolve: answer(...addresses) }));
    assert.equal(refused.failure?.code, 'ERR_PUSH_FORBIDDEN_ADDRESS', addresses.join());
  }
  const permissive = await call(createSafeLookup({ allowPrivate: true, resolve: answer('127.0.0.1') }));
  assert.equal(permissive.address, '127.0.0.1');
  const failing = await call(createSafeLookup({
    resolve: (hostname, options, callback) => callback(Object.assign(new Error('nx'), { code: 'ENOTFOUND' })),
  }));
  assert.equal(failing.failure.code, 'ENOTFOUND');
});

test('the sender posts the exact payload with TTL and Urgency headers, no redirects and one bounded retry', async () => {
  const record = { provider: 'unifiedpush', endpoint: 'https://push.example.test:8443/up/abc?x=1' };
  const sender = (statuses) => {
    const request = fakeHttpsRequest(statuses);
    return { request, send: createUnifiedPushSender({ request, retryDelayMs: 1 }) };
  };

  const plain = sender([201]);
  await plain.send(record, { kind: 'message', id: ID, ttlMs: 86_400_000, from: '12345678' });
  assert.equal(plain.request.calls.length, 1);
  const [{ options, body }] = plain.request.calls;
  assert.equal(options.protocol, 'https:');
  assert.equal(options.hostname, 'push.example.test');
  assert.equal(options.port, '8443');
  assert.equal(options.path, '/up/abc?x=1');
  assert.equal(options.method, 'POST');
  assert.equal(options.agent, false);
  assert.equal(typeof options.lookup, 'function');
  assert.equal(options.headers.ttl, '86400');
  assert.equal(options.headers.urgency, 'high');
  assert.equal(options.headers['content-length'], body.length);
  assert.equal(options.headers['content-encoding'], undefined);
  assert.equal(body.toString('utf8'), JSON.stringify({ kind: 'message', id: ID }));
  assert.ok(body.length <= 4_096);

  const call = sender([201]);
  await call.send(record, { kind: 'call', id: ID, ttlMs: 45_000 });
  assert.equal(call.request.calls[0].options.headers.ttl, '45');
  const ended = sender([201]);
  await ended.send(record, { kind: 'call_ended', id: ID, ttlMs: 45_000, reason: 'timeout' });
  assert.equal(ended.request.calls[0].body.toString('utf8'), JSON.stringify({ kind: 'call_ended', id: ID, reason: 'timeout' }));

  for (const status of [404, 410]) {
    await assert.rejects(sender([status]).send(record, MESSAGE), (failure) => failure.code === 'endpoint_gone');
  }
  const redirect = sender([302, 201]);
  await assert.rejects(redirect.send(record, MESSAGE), (failure) => failure.code !== 'endpoint_gone' && /failed/.test(failure.message));
  assert.equal(redirect.request.calls.length, 1);
  const rejected = sender([400, 201]);
  await assert.rejects(rejected.send(record, MESSAGE));
  assert.equal(rejected.request.calls.length, 1);

  const flaky = sender([503, 201]);
  await flaky.send(record, MESSAGE);
  assert.equal(flaky.request.calls.length, 2);
  const down = sender([500, 502]);
  await assert.rejects(down.send(record, MESSAGE));
  assert.equal(down.request.calls.length, 2);

  const guarded = sender([201]);
  await assert.rejects(guarded.send({ provider: 'unifiedpush', endpoint: 'https://127.0.0.1/up' }, MESSAGE), /not allowed/);
  await assert.rejects(guarded.send({ provider: 'unifiedpush', endpoint: 'http://push.example.test/up' }, MESSAGE), /not allowed/);
  assert.equal(guarded.request.calls.length, 0);
});

test('registered keys encrypt the body with aes128gcm that only the user agent can read', async () => {
  const agent = createUserAgent();
  const request = fakeHttpsRequest([201]);
  const send = createUnifiedPushSender({ request });
  await send({ provider: 'unifiedpush', endpoint: 'https://push.example.test/up/enc', ...agent.registration },
    { kind: 'message', id: ID, ttlMs: 86_400_000 });
  const [{ options, body }] = request.calls;
  assert.equal(options.headers['content-encoding'], 'aes128gcm');
  assert.equal(options.headers['content-type'], 'application/octet-stream');
  assert.ok(body.length <= 4_096);
  assert.equal(body.toString('utf8').includes(ID), false);
  assert.equal(decryptWebPush(body, agent).toString('utf8'), JSON.stringify({ kind: 'message', id: ID }));
});

test('push requests time out after five seconds', async () => {
  mock.timers.enable({ apis: ['setTimeout'] });
  try {
    const request = fakeHttpsRequest(['hang']);
    const send = createUnifiedPushSender({ request });
    const result = send({ provider: 'unifiedpush', endpoint: 'https://push.example.test/up/slow' }, MESSAGE)
      .then(() => 'resolved', (failure) => failure);
    await settle();
    assert.equal(request.calls.length, 1);
    mock.timers.tick(4_999);
    await settle();
    assert.equal(request.calls[0].destroyed, undefined);
    mock.timers.tick(1);
    await settle();
    assert.match(request.calls[0].destroyed.message, /timed out/);
    mock.timers.tick(1_000);
    await settle();
    assert.equal(request.calls.length, 2);
    mock.timers.tick(5_000);
    await settle();
    assert.match((await result).message, /Push delivery failed/);
  } finally {
    mock.timers.reset();
  }
});

test('provider responses larger than 4 KiB are cut off', async () => {
  const request = fakeHttpsRequest([200], { responseBody: 'x'.repeat(5_000) });
  await createUnifiedPushSender({ request })({ provider: 'unifiedpush', endpoint: 'https://push.example.test/up/big' }, MESSAGE);
  assert.equal(request.calls[0].responseDestroyed, true);
});

test('real connections go only to a validated address and never to a rebinding target', async () => {
  const target = { provider: 'unifiedpush', endpoint: 'https://push.example.test:9/up' };
  const answer = (...addresses) => (hostname, options, callback) => callback(null, addresses.map((address) => ({ address, family: 4 })));
  const started = Date.now();
  await assert.rejects(createUnifiedPushSender({ resolve: answer('10.0.0.5'), retryDelayMs: 1 })(target, MESSAGE),
    (failure) => failure.code === 'ERR_PUSH_FORBIDDEN_ADDRESS');
  assert.ok(Date.now() - started < 1_000);
  await assert.rejects(createUnifiedPushSender({ resolve: answer('93.184.216.34', '127.0.0.1'), retryDelayMs: 1 })(target, MESSAGE),
    (failure) => failure.code === 'ERR_PUSH_FORBIDDEN_ADDRESS');

  const connections = [];
  const listener = createServer((socket) => { connections.push(socket.remoteAddress); socket.destroy(); });
  await new Promise((resolve) => listener.listen(0, '127.0.0.1', resolve));
  try {
    const resolved = [];
    const resolve = (hostname, options, callback) => {
      resolved.push(hostname);
      callback(null, [{ address: '127.0.0.1', family: 4 }]);
    };
    const local = { provider: 'unifiedpush', endpoint: `https://push.test.invalid:${listener.address().port}/up` };
    await assert.rejects(createUnifiedPushSender({ allowPrivate: true, resolve, retryDelayMs: 1, timeoutMs: 500 })(local, MESSAGE),
      /Push delivery failed/);
    assert.ok(connections.length >= 1);
    assert.ok(connections.every((address) => address === '127.0.0.1'));
    assert.deepEqual([...new Set(resolved)], ['push.test.invalid']);
  } finally {
    await new Promise((resolve) => listener.close(resolve));
  }
});

test('message pushes coalesce per number while calls are never merged', () => {
  mock.timers.enable({ apis: ['setTimeout'] });
  try {
    const sent = [];
    const coalescer = createCoalescer({ windowMs: 1_500, dispatch: (key, value, trailing) => sent.push({ key, value, trailing }) });
    coalescer.push('11111111', 'a');
    coalescer.push('11111111', 'b');
    coalescer.push('11111111', 'c');
    coalescer.push('22222222', 'x');
    assert.deepEqual(sent, [
      { key: '11111111', value: 'a', trailing: false },
      { key: '22222222', value: 'x', trailing: false },
    ]);
    mock.timers.tick(1_499);
    assert.equal(sent.length, 2);
    mock.timers.tick(1);
    assert.deepEqual(sent.at(-1), { key: '11111111', value: 'c', trailing: true });
    assert.equal(sent.length, 3);
    mock.timers.tick(1_500);
    assert.equal(sent.length, 3);
    coalescer.push('11111111', 'd');
    assert.deepEqual(sent.at(-1), { key: '11111111', value: 'd', trailing: false });
    coalescer.forget('11111111');
    coalescer.push('11111111', 'e');
    assert.deepEqual(sent.at(-1), { key: '11111111', value: 'e', trailing: false });
    coalescer.close();
  } finally {
    mock.timers.reset();
  }
});

test('APNs configuration is all-or-nothing and validates the key before startup', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'apns-config-'));
  try {
    const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    const keyFile = join(directory, 'AuthKey_TEST.p8');
    await writeFile(keyFile, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
    const full = { APNS_KEY_FILE: keyFile, APNS_KEY_ID: 'ABC123DEFG', APNS_TEAM_ID: 'TEAM123456', APNS_TOPIC: 'app.line' };
    assert.equal(await readApnsConfig({}), undefined);
    assert.equal(await readApnsConfig({ APNS_KEY_FILE: '  ', APNS_TOPIC: '' }), undefined);
    const config = await readApnsConfig(full);
    assert.equal(config.topic, 'app.line');
    assert.equal(config.keyId, 'ABC123DEFG');
    for (const name of Object.keys(full)) {
      const partial = { ...full };
      delete partial[name];
      await assert.rejects(readApnsConfig(partial), new RegExp(`missing: ${name}`));
    }
    await assert.rejects(readApnsConfig({ ...full, APNS_KEY_FILE: join(directory, 'missing.p8') }), /readable P-256 private key/);
    const rsa = generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey.export({ type: 'pkcs8', format: 'pem' });
    await writeFile(join(directory, 'rsa.p8'), rsa);
    await assert.rejects(readApnsConfig({ ...full, APNS_KEY_FILE: join(directory, 'rsa.p8') }), /P-256/);
    await assert.rejects(readApnsConfig({ ...full, APNS_TOPIC: 'bad topic' }), /APNS_TOPIC/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('APNs sender signs an ES256 provider token cached for under 50 minutes and sends a generic alert', async () => {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const keyPem = privateKey.export({ type: 'pkcs8', format: 'pem' });
  let clock = Date.UTC(2026, 9, 4, 12, 0, 0);
  const connector = fakeApnsConnector([{ status: 200 }]);
  const send = createApnsSender({
    keyPem, keyId: 'ABC123DEFG', teamId: 'TEAM123456', topic: 'app.line', connect: connector.connect, now: () => clock, retryDelayMs: 1,
  });
  const record = { provider: 'apns', token: 'ab'.repeat(32), environment: 'production' };
  await send(record, { kind: 'message', id: ID, ttlMs: 86_400_000 });
  const [first] = connector.requests;
  assert.deepEqual(connector.connected, ['https://api.push.apple.com']);
  assert.equal(first.headers[':method'], 'POST');
  assert.equal(first.headers[':path'], `/3/device/${'ab'.repeat(32)}`);
  assert.equal(first.headers['apns-topic'], 'app.line');
  assert.equal(first.headers['apns-push-type'], 'alert');
  assert.equal(first.headers['apns-priority'], '10');
  assert.equal(first.headers['apns-expiration'], String(Math.floor(clock / 1_000) + 86_400));
  assert.deepEqual(first.body, {
    aps: { alert: { title: 'Line', body: 'Новое сообщение' }, sound: 'default', 'mutable-content': 1 }, kind: 'message', id: ID,
  });
  const [header, claims, signature] = first.headers.authorization.replace(/^bearer /, '').split('.');
  assert.deepEqual(JSON.parse(Buffer.from(header, 'base64url')), { alg: 'ES256', kid: 'ABC123DEFG' });
  assert.deepEqual(JSON.parse(Buffer.from(claims, 'base64url')), { iss: 'TEAM123456', iat: Math.floor(clock / 1_000) });
  assert.ok(verify('sha256', Buffer.from(`${header}.${claims}`), { key: publicKey, dsaEncoding: 'ieee-p1363' },
    Buffer.from(signature, 'base64url')));

  clock += 49 * 60_000;
  await send(record, { kind: 'call', id: ID, ttlMs: 45_000 });
  assert.equal(connector.requests[1].headers.authorization, first.headers.authorization);
  assert.equal(connector.requests[1].body.aps.alert.body, 'Входящий звонок');
  assert.equal(connector.requests[1].headers['apns-expiration'], String(Math.floor(clock / 1_000) + 45));
  clock += 2 * 60_000;
  await send({ ...record, environment: 'sandbox' }, { kind: 'call_ended', id: ID, ttlMs: 45_000, reason: 'timeout' });
  assert.notEqual(connector.requests[2].headers.authorization, first.headers.authorization);
  assert.deepEqual(connector.connected, ['https://api.push.apple.com', 'https://api.sandbox.push.apple.com']);
  assert.equal(connector.requests[2].headers['apns-push-type'], 'background');
  assert.deepEqual(connector.requests[2].body, { aps: { 'content-available': 1 }, kind: 'call_ended', id: ID, reason: 'timeout' });
  send.close();
});

test('APNs rejections remove dead tokens and refresh a rejected provider token', async () => {
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const keyPem = privateKey.export({ type: 'pkcs8', format: 'pem' });
  const record = { provider: 'apns', token: 'cd'.repeat(32), environment: 'production' };
  const create = (connector, extra = {}) => createApnsSender({
    keyPem, keyId: 'K', teamId: 'T', topic: 'app.line', connect: connector.connect, retryDelayMs: 1, ...extra,
  });
  for (const response of [{ status: 410, reason: 'Unregistered' }, { status: 400, reason: 'BadDeviceToken' }]) {
    const send = create(fakeApnsConnector([response]));
    await assert.rejects(send(record, MESSAGE), (failure) => failure.code === 'endpoint_gone');
    send.close();
  }
  let clock = 1_000_000_000_000;
  const connector = fakeApnsConnector([{ status: 403, reason: 'ExpiredProviderToken' }, { status: 200 }]);
  const send = create(connector, { now: () => clock });
  await assert.rejects(send(record, MESSAGE), (failure) => failure.code !== 'endpoint_gone');
  clock += 1_000;
  await send(record, MESSAGE);
  assert.notEqual(connector.requests[0].headers.authorization, connector.requests[1].headers.authorization);
  send.close();
  const flaky = fakeApnsConnector([{ status: 503 }, { status: 200 }]);
  await create(flaky)(record, MESSAGE);
  assert.equal(flaky.requests.length, 2);
});

test('integer and boolean environment settings clamp, fall back and reject malformed values', () => {
  assert.equal(readIntegerSetting('X', undefined, 15_000, 0, 60_000), 15_000);
  assert.equal(readIntegerSetting('X', '', 15_000, 0, 60_000), 15_000);
  assert.equal(readIntegerSetting('X', '0', 15_000, 0, 60_000), 0);
  assert.equal(readIntegerSetting('X', ' 2500 ', 15_000, 0, 60_000), 2_500);
  assert.equal(readIntegerSetting('X', '999999', 15_000, 0, 60_000), 60_000);
  assert.equal(readIntegerSetting('X', 5, 15_000, 10, 60_000), 10);
  for (const bad of ['abc', '-1', '1.5', '1e3', {}]) assert.throws(() => readIntegerSetting('X', bad, 1, 0, 10), /Invalid X/);
  assert.equal(readBooleanSetting('true'), true);
  assert.equal(readBooleanSetting(' YES '), true);
  assert.equal(readBooleanSetting('1'), true);
  assert.equal(readBooleanSetting('false'), false);
  assert.equal(readBooleanSetting(undefined), false);
});
