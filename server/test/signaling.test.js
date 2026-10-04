import assert from 'node:assert/strict';
import { createHash, scryptSync } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, test } from 'node:test';
import { TokenVerifier } from 'livekit-server-sdk';
import { WebSocket } from 'ws';
import { createSignalingServer } from '../src/index.js';

const resources = [];
const LIVEKIT_ENV = {
  LIVEKIT_URL: 'wss://rtc.example.test',
  LIVEKIT_API_KEY: 'test-api-key',
  LIVEKIT_API_SECRET: 'test-api-secret-that-is-never-sent-to-clients',
};
const token = (digit) => digit.repeat(64);
const uuid = (id) => `550e8400-e29b-41d4-a716-${String(id).padStart(12, '0')}`;
const base64 = (value) => Buffer.from(value).toString('base64');
const ADMIN_CODE = 'correct-horse-battery-staple';
let adminRequestNumber = 20_000;
const nextAdminRequestId = () => uuid(adminRequestNumber++);

function adminEnv(code = ADMIN_CODE) {
  const salt = Buffer.from('admin-test-salt-1');
  return { ADMIN_PASSWORD_HASH: `scrypt$${salt.toString('base64')}$${scryptSync(code, salt, 64).toString('base64')}` };
}

function bundle(seed, preKeyCount = 2) {
  return {
    identityKey: base64(`identity-${seed}`),
    registrationId: seed,
    signedPreKey: { id: seed, publicKey: base64(`signed-public-${seed}`), signature: base64(`signed-signature-${seed}`) },
    kyberPreKey: { id: seed + 10_000, publicKey: base64(`kyber-public-${seed}`), signature: base64(`kyber-signature-${seed}`) },
    preKeys: Array.from({ length: preKeyCount }, (_, index) => ({ id: seed * 100 + index, publicKey: base64(`one-time-${seed}-${index}`) })),
  };
}

async function setup(options = {}) {
  const { directory: requestedDirectory, ...serverOptions } = options;
  const directory = requestedDirectory ?? await mkdtemp(join(tmpdir(), 'signal-test-'));
  const server = await createSignalingServer({
    dataFile: join(directory, 'identities.json'),
    env: {},
    heartbeatIntervalMs: 60_000,
    ...serverOptions,
  });
  const address = await server.listen(0, '127.0.0.1');
  const resource = { server, directory, url: `ws://127.0.0.1:${address.port}/signal`, sockets: [] };
  resources.push(resource);
  return resource;
}

function connect(url) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    socket.once('open', () => resolve(socket));
    socket.once('error', reject);
  });
}

function waitFor(socket, predicate = () => true, timeoutMs = 1_500) {
  return new Promise((resolve, reject) => {
    const received = [];
    const timer = setTimeout(() => {
      socket.off('message', onMessage);
      reject(new Error(`Timed out waiting for WebSocket message; saw ${JSON.stringify(received)}`));
    }, timeoutMs);
    const onMessage = (data) => {
      const message = JSON.parse(data.toString());
      received.push(message);
      if (!predicate(message)) return;
      clearTimeout(timer);
      socket.off('message', onMessage);
      resolve(message);
    };
    socket.on('message', onMessage);
  });
}

async function openSocket(resource) {
  const socket = await connect(resource.url);
  resource.sockets.push(socket);
  return socket;
}

async function register(socket, installationToken, publicBundle = bundle(1), protocolVersion = 6) {
  const result = waitFor(socket, (message) => ['registered', 'error'].includes(message.type));
  socket.send(JSON.stringify({ type: 'register', token: installationToken, bundle: publicBundle, protocolVersion }));
  return result;
}

async function lookup(socket, to, requestId = uuid(9_000)) {
  const result = waitFor(socket, (message) => message.requestId === requestId);
  socket.send(JSON.stringify({ type: 'lookup', to, requestId }));
  return result;
}

async function sendRequest(socket, message, predicate) {
  const result = waitFor(socket, predicate);
  socket.send(JSON.stringify(message));
  return result;
}

async function adminLogin(socket, code = ADMIN_CODE) {
  const requestId = nextAdminRequestId();
  return sendRequest(socket, { type: 'admin_login', requestId, code },
    (message) => message.type === 'admin_result' && message.requestId === requestId);
}

async function adminAction(socket, action, fields = {}) {
  const requestId = nextAdminRequestId();
  return sendRequest(socket, { type: 'admin', requestId, action, ...fields },
    (message) => message.type === 'admin_result' && message.requestId === requestId);
}

async function closeSocket(socket) {
  if (socket.readyState === WebSocket.CLOSED) return;
  const closed = new Promise((resolve) => socket.once('close', resolve));
  socket.close();
  await closed;
}

afterEach(async () => {
  await Promise.all(resources.splice(0).map(async ({ server, directory, sockets }) => {
    await server.close();
    await Promise.all(sockets.map(async (socket) => {
      if (socket.readyState !== WebSocket.CLOSED) await closeSocket(socket);
    }));
    await rm(directory, { recursive: true, force: true });
  }));
});

test('registration stores only token hashes and public bundles in an atomic 0600 v2 store', async () => {
  const resource = await setup();
  const socket = await openSocket(resource);
  const registered = await register(socket, token('a'), bundle(11));
  assert.equal(registered.type, 'registered');
  assert.match(registered.number, /^\d{8}$/);

  const dataFile = join(resource.directory, 'identities.json');
  const content = await readFile(dataFile, 'utf8');
  const document = JSON.parse(content);
  assert.equal(document.version, 2);
  assert.ok(document.identities[createHash('sha256').update(token('a')).digest('hex')]);
  assert.equal(content.includes(token('a')), false);
  assert.equal(content.includes('signed-signature-11'), false);
  assert.deepEqual(document.identities[createHash('sha256').update(token('a')).digest('hex')].bundle, bundle(11));
  assert.equal((await stat(dataFile)).mode & 0o777, 0o600);

  await closeSocket(socket);
  await resource.server.close();
  resource.server = await createSignalingServer({ dataFile, env: {}, heartbeatIntervalMs: 60_000 });
  const address = await resource.server.listen(0, '127.0.0.1');
  resource.url = `ws://127.0.0.1:${address.port}/signal`;
  const reconnect = await openSocket(resource);
  assert.deepEqual(await register(reconnect, token('a'), bundle(11)), {
    type: 'registered', number: registered.number, mediaReady: false,
    callsEnabled: true, chatEnabled: true, registrationEnabled: true, maxParticipants: 8,
  });
});

test('version-1 registrations migrate on bundle registration without changing numbers', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'signal-v1-'));
  const dataFile = join(directory, 'identities.json');
  const oldToken = token('b');
  const hash = createHash('sha256').update(oldToken).digest('hex');
  await writeFile(dataFile, JSON.stringify({ version: 1, identities: { [hash]: '01234567' } }), { mode: 0o600 });
  const server = await createSignalingServer({ dataFile, env: {}, heartbeatIntervalMs: 60_000 });
  const address = await server.listen(0, '127.0.0.1');
  const resource = { server, directory, url: `ws://127.0.0.1:${address.port}/signal`, sockets: [] };
  resources.push(resource);
  const socket = await openSocket(resource);
  assert.deepEqual(await register(socket, oldToken, bundle(12)), {
    type: 'registered', number: '01234567', mediaReady: false,
    callsEnabled: true, chatEnabled: true, registrationEnabled: true, maxParticipants: 8,
  });
  const migrated = JSON.parse(await readFile(dataFile, 'utf8'));
  assert.equal(migrated.version, 2);
  assert.equal(migrated.identities[hash].number, '01234567');
  assert.deepEqual(migrated.identities[hash].bundle, bundle(12));
});

test('identity keys cannot change, while a matching identity may rotate its public prekeys', async () => {
  const resource = await setup();
  const socket = await openSocket(resource);
  await register(socket, token('c'), bundle(13));

  const changedIdentity = await sendRequest(socket, { type: 'keys', bundle: bundle(14) }, (message) => message.type === 'error');
  assert.equal(changedIdentity.code, 'identity_mismatch');

  const rotated = bundle(13, 1);
  rotated.preKeys[0].id += 1;
  assert.deepEqual(await sendRequest(socket, { type: 'keys', bundle: rotated }, (message) => message.type === 'keys_updated'), { type: 'keys_updated' });
  const stored = JSON.parse(await readFile(join(resource.directory, 'identities.json'), 'utf8'));
  const entry = Object.values(stored.identities)[0];
  assert.deepEqual(entry.bundle, rotated);
});

test('bundle lookups atomically consume one target prekey and report exhaustion with request ids', async () => {
  const resource = await setup();
  const requester = await openSocket(resource);
  const target = await openSocket(resource);
  await register(requester, token('d'), bundle(15, 0));
  const targetRegistration = await register(target, token('e'), bundle(16, 2));

  const first = await lookup(requester, targetRegistration.number, uuid(1));
  assert.equal(first.type, 'bundle');
  assert.equal(first.peer, targetRegistration.number);
  assert.deepEqual(first.bundle.preKeys, [bundle(16, 2).preKeys[0]]);
  const second = await lookup(requester, targetRegistration.number, uuid(2));
  assert.deepEqual(second.bundle.preKeys, [bundle(16, 2).preKeys[1]]);
  const exhausted = await lookup(requester, targetRegistration.number, uuid(3));
  assert.deepEqual(exhausted, { type: 'error', code: 'prekeys_exhausted', requestId: uuid(3) });
  const absent = await lookup(requester, '99999999', uuid(4));
  assert.deepEqual(absent, { type: 'error', code: 'not_found', requestId: uuid(4) });

  const stored = JSON.parse(await readFile(join(resource.directory, 'identities.json'), 'utf8'));
  assert.equal(Object.values(stored.identities).find((entry) => entry.number === targetRegistration.number).bundle.preKeys.length, 0);
});

test('encrypted envelopes persist before acknowledgement and report recipient delivery separately', async () => {
  const resource = await setup();
  const sender = await openSocket(resource);
  const recipient = await openSocket(resource);
  const senderInfo = await register(sender, token('f'), bundle(17, 0), 7);
  const recipientInfo = await register(recipient, token('1'), bundle(18, 0), 7);
  const body = base64('opaque encrypted payload');
  const message = { type: 'envelope', to: recipientInfo.number, id: uuid(10), cipherType: 2, body };

  const delivered = waitFor(recipient, (item) => item.type === 'envelope');
  const queued = waitFor(sender, (item) => item.type === 'queued');
  sender.send(JSON.stringify(message));
  assert.deepEqual(await delivered, {
    type: 'envelope', from: senderInfo.number, id: message.id, cipherType: 2, body,
  });
  assert.deepEqual(await queued, { type: 'queued', id: message.id });
  const deliveryReceipt = waitFor(sender, (item) => item.type === 'delivered' && item.id === message.id);
  recipient.send(JSON.stringify({ type: 'delivery_ack', id: message.id }));
  assert.deepEqual(await deliveryReceipt, { type: 'delivered', id: message.id });

  const duplicateAck = waitFor(sender, (item) => item.type === 'delivered' && item.id === message.id);
  const unexpectedDuplicate = waitFor(recipient, (item) => item.type === 'envelope', 150).then(() => 'duplicate', () => 'none');
  sender.send(JSON.stringify(message));
  assert.deepEqual(await duplicateAck, { type: 'delivered', id: message.id });
  assert.equal(await unexpectedDuplicate, 'none');
  const conflictingRetry = await sendRequest(sender, {
    ...message, body: base64('different ciphertext with a reused id'),
  }, (item) => item.type === 'error');
  assert.deepEqual(conflictingRetry, { type: 'error', code: 'id_conflict', id: message.id });

  const offline = await sendRequest(sender, { ...message, to: '99999999', id: uuid(11) }, (item) => item.type === 'error');
  assert.deepEqual(offline, { type: 'error', code: 'not_found', id: uuid(11) });
  const invalidCipher = await sendRequest(sender, { ...message, id: uuid(12), body: 'not base64!' }, (item) => item.type === 'error');
  assert.equal(invalidCipher.code, 'invalid_message');
  assert.equal((await readFile(join(resource.directory, 'identities.json'), 'utf8')).includes(body), false);
  const mailboxFile = join(resource.directory, 'identities.json.mailbox.json');
  const mailbox = JSON.parse(await readFile(mailboxFile, 'utf8'));
  assert.deepEqual(mailbox.envelopes, []);
  assert.deepEqual(mailbox.receipts, [{
    from: senderInfo.number, to: recipientInfo.number, id: message.id,
    cipherType: message.cipherType,
    cipherHash: createHash('sha256').update(`${message.cipherType}:${message.body}`).digest('hex'),
    createdAt: mailbox.receipts[0].createdAt, expiresAt: mailbox.receipts[0].expiresAt,
  }]);
  assert.equal((await stat(mailboxFile)).mode & 0o777, 0o600);
});

test('offline ciphertext survives a server restart, rejects unauthorized acknowledgements, and replays durable receipts', async () => {
  const resource = await setup();
  const sender = await openSocket(resource);
  const recipient = await openSocket(resource);
  const senderInfo = await register(sender, token('a'), bundle(51, 0), 7);
  const recipientInfo = await register(recipient, token('b'), bundle(52, 0), 7);
  await closeSocket(recipient);
  const message = {
    type: 'envelope', to: recipientInfo.number, id: uuid(5_101), cipherType: 3,
    body: base64('opaque-offline-ciphertext'),
  };
  const queued = waitFor(sender, (item) => item.type === 'queued' && item.id === message.id);
  sender.send(JSON.stringify(message));
  assert.deepEqual(await queued, { type: 'queued', id: message.id });

  const mailboxFile = join(resource.directory, 'identities.json.mailbox.json');
  const persisted = JSON.parse(await readFile(mailboxFile, 'utf8'));
  assert.equal(persisted.envelopes.length, 1);
  assert.deepEqual(persisted.envelopes[0], {
    from: senderInfo.number, to: recipientInfo.number, id: message.id,
    cipherType: 3, body: message.body,
    createdAt: persisted.envelopes[0].createdAt, expiresAt: persisted.envelopes[0].expiresAt,
  });
  assert.equal((await stat(mailboxFile)).mode & 0o777, 0o600);

  await resource.server.close();
  resource.server = await createSignalingServer({
    dataFile: join(resource.directory, 'identities.json'), env: {}, heartbeatIntervalMs: 60_000,
  });
  const address = await resource.server.listen(0, '127.0.0.1');
  resource.url = `ws://127.0.0.1:${address.port}/signal`;

  const senderAgain = await openSocket(resource);
  await register(senderAgain, token('a'), bundle(51, 0), 7);
  const otherAccount = await openSocket(resource);
  await register(otherAccount, token('c'), bundle(53, 0), 7);
  const recipientAgain = await openSocket(resource);
  const replay = waitFor(recipientAgain, (item) => item.type === 'envelope' && item.id === message.id);
  await register(recipientAgain, token('b'), bundle(52, 0), 7);
  assert.deepEqual(await replay, {
    type: 'envelope', from: senderInfo.number, id: message.id,
    cipherType: 3, body: message.body,
  });

  const unauthorized = await sendRequest(otherAccount, { type: 'delivery_ack', id: message.id },
    (item) => item.type === 'error');
  assert.deepEqual(unauthorized, { type: 'error', code: 'unauthorized', id: message.id });
  const delivered = waitFor(senderAgain, (item) => item.type === 'delivered' && item.id === message.id);
  recipientAgain.send(JSON.stringify({ type: 'delivery_ack', id: message.id }));
  assert.deepEqual(await delivered, { type: 'delivered', id: message.id });

  await resource.server.close();
  resource.server = await createSignalingServer({
    dataFile: join(resource.directory, 'identities.json'), env: {}, heartbeatIntervalMs: 60_000,
  });
  const restartedAddress = await resource.server.listen(0, '127.0.0.1');
  resource.url = `ws://127.0.0.1:${restartedAddress.port}/signal`;
  const senderAfterReceipt = await openSocket(resource);
  const resumedReceipt = waitFor(senderAfterReceipt, (item) => item.type === 'delivered' && item.id === message.id);
  await register(senderAfterReceipt, token('a'), bundle(51, 0), 7);
  assert.deepEqual(await resumedReceipt, { type: 'delivered', id: message.id });
  const duplicate = await sendRequest(senderAfterReceipt, message,
    (item) => item.id === message.id && ['delivered', 'queued', 'error'].includes(item.type));
  assert.deepEqual(duplicate, { type: 'delivered', id: message.id });
  const afterReceipt = JSON.parse(await readFile(mailboxFile, 'utf8'));
  assert.equal(afterReceipt.envelopes.length, 0);
  assert.equal(afterReceipt.receipts.length, 1);
  assert.equal(JSON.stringify(afterReceipt).includes(message.body), false);
});

test('persistent message limits enforce both per-recipient and global mailbox bounds', async () => {
  const resource = await setup({ maxMailboxEntries: 2, maxMailboxPerUser: 1 });
  const sender = await openSocket(resource);
  const first = await openSocket(resource);
  const second = await openSocket(resource);
  const third = await openSocket(resource);
  await register(sender, token('d'), bundle(54, 0), 7);
  const firstInfo = await register(first, token('e'), bundle(55, 0), 7);
  const secondInfo = await register(second, token('f'), bundle(56, 0), 7);
  const thirdInfo = await register(third, token('0'), bundle(57, 0), 7);
  await Promise.all([closeSocket(first), closeSocket(second), closeSocket(third)]);

  const envelope = (to, id) => ({ type: 'envelope', to, id, cipherType: 2, body: base64(`cipher-${id}`) });
  assert.equal((await sendRequest(sender, envelope(firstInfo.number, uuid(5_201)), (item) => item.type === 'queued')).type, 'queued');
  assert.equal((await sendRequest(sender, envelope(firstInfo.number, uuid(5_202)), (item) => item.type === 'error')).code, 'mailbox_full');
  assert.equal((await sendRequest(sender, envelope(secondInfo.number, uuid(5_203)), (item) => item.type === 'queued')).type, 'queued');
  assert.equal((await sendRequest(sender, envelope(thirdInfo.number, uuid(5_204)), (item) => item.type === 'error')).code, 'mailbox_full');
  const saved = JSON.parse(await readFile(join(resource.directory, 'identities.json.mailbox.json'), 'utf8'));
  assert.equal(saved.envelopes.length, 2);
});

test('version-6 clients retain sent acknowledgements while their ciphertext is durably queued', async () => {
  const resource = await setup();
  const sender = await openSocket(resource);
  const recipient = await openSocket(resource);
  await register(sender, token('a'), bundle(68, 0));
  const recipientInfo = await register(recipient, token('b'), bundle(69, 0));
  const message = {
    type: 'envelope', to: recipientInfo.number, id: uuid(5_251),
    cipherType: 2, body: base64('legacy-client-ciphertext'),
  };
  const incoming = waitFor(recipient, (item) => item.type === 'envelope' && item.id === message.id);
  const sent = waitFor(sender, (item) => item.type === 'sent' && item.id === message.id);
  sender.send(JSON.stringify(message));
  assert.deepEqual(await sent, { type: 'sent', id: message.id });
  await incoming;
  assert.equal(JSON.parse(await readFile(join(resource.directory, 'identities.json.mailbox.json'), 'utf8')).envelopes.length, 1);
  assert.deepEqual(await sendRequest(recipient, { type: 'delivery_ack', id: message.id },
    (item) => item.type === 'error'), { type: 'error', code: 'invalid_message', id: message.id });
  assert.deepEqual(await sendRequest(recipient, { type: 'inbox_sync' },
    (item) => item.type === 'error'), { type: 'error', code: 'invalid_message' });
});

test('version-7 registration and authenticated inbox sync deliver calls, receipts, and ciphertext before inbox_complete', async () => {
  const resource = await setup({
    env: LIVEKIT_ENV,
    pushSender: async () => {},
    createRoom: async () => {},
    deleteRoom: () => {},
    ringingTimeoutMs: 5_000,
    maxInboxSyncsPerWindow: 1,
  });
  const owner = await openSocket(resource);
  const peer = await openSocket(resource);
  const member = await openSocket(resource);
  await register(owner, token('7'), bundle(76, 0), 7);
  const peerInfo = await register(peer, token('8'), bundle(77, 0), 7);
  const memberInfo = await register(member, token('9'), bundle(78, 0), 8);
  await sendRequest(member, { type: 'push_register', provider: 'unifiedpush', endpoint: 'https://push.example.test/up/member-9' },
    (item) => item.type === 'push_registered');

  const deliveredToPeer = waitFor(peer, (item) => item.type === 'envelope' && item.id === uuid(7_601));
  const receipt = waitFor(member, (item) => item.type === 'delivered' && item.id === uuid(7_601));
  member.send(JSON.stringify({ type: 'envelope', to: peerInfo.number, id: uuid(7_601),
    cipherType: 2, body: base64('receipt-ciphertext') }));
  await deliveredToPeer;
  peer.send(JSON.stringify({ type: 'delivery_ack', id: uuid(7_601) }));
  await receipt;
  await closeSocket(member);

  const queuedEnvelope = { type: 'envelope', to: memberInfo.number, id: uuid(7_602),
    cipherType: 3, body: base64('offline-ciphertext') };
  assert.equal((await sendRequest(peer, queuedEnvelope, (item) => item.type === 'queued')).type, 'queued');
  const callCreated = waitFor(owner, (item) => item.type === 'call_created');
  owner.send(JSON.stringify({ type: 'create_call', members: [memberInfo.number] }));
  const call = await callCreated;

  const reconnected = await openSocket(resource);
  const registrationEvents = [];
  reconnected.on('message', (data) => registrationEvents.push(JSON.parse(data.toString())));
  const registrationComplete = waitFor(reconnected, (item) => item.type === 'inbox_complete');
  const registered = await register(reconnected, token('9'), bundle(78, 0), 7);
  const completed = await registrationComplete;
  assert.equal(registered.pushEnabled, true);
  assert.deepEqual(completed, { type: 'inbox_complete' });
  const registrationTypes = registrationEvents.map((item) => item.type);
  assert.ok(registrationTypes.indexOf('incoming') < registrationTypes.indexOf('delivered'));
  assert.ok(registrationTypes.indexOf('delivered') < registrationTypes.indexOf('envelope'));
  assert.ok(registrationTypes.indexOf('envelope') < registrationTypes.indexOf('inbox_complete'));
  assert.ok(registrationEvents.some((item) => item.type === 'incoming' && item.callId === call.callId));
  assert.ok(registrationEvents.some((item) => item.type === 'delivered' && item.id === uuid(7_601)));
  assert.ok(registrationEvents.some((item) => item.type === 'envelope' && item.id === queuedEnvelope.id));

  assert.deepEqual(await sendRequest(reconnected, { type: 'inbox_sync', extra: true },
    (item) => item.type === 'error'), { type: 'error', code: 'invalid_message' });
  const syncStart = registrationEvents.length;
  const repeatedEnvelope = waitFor(reconnected,
    (item) => item.type === 'envelope' && item.id === queuedEnvelope.id);
  const syncComplete = waitFor(reconnected, (item) => item.type === 'inbox_complete');
  reconnected.send(JSON.stringify({ type: 'inbox_sync' }));
  await repeatedEnvelope;
  await syncComplete;
  const syncEvents = registrationEvents.slice(syncStart);
  assert.deepEqual(syncEvents.map((item) => item.type), ['incoming', 'delivered', 'envelope', 'inbox_complete']);
  assert.equal(JSON.parse(await readFile(join(resource.directory, 'identities.json.push-endpoints.json'), 'utf8'))
    .endpoints[memberInfo.number].endpoint, 'https://push.example.test/up/member-9');

  assert.deepEqual(await sendRequest(reconnected, { type: 'inbox_sync' },
    (item) => item.type === 'error'), { type: 'error', code: 'rate_limited' });
  const grant = await sendRequest(reconnected, { type: 'join_call', callId: call.callId },
    (item) => item.type === 'room_grant' || item.type === 'error');
  assert.equal(grant.type, 'room_grant');
  const ended = waitFor(owner, (item) => item.type === 'ended' && item.callId === call.callId);
  reconnected.send(JSON.stringify({ type: 'decline_call', callId: call.callId }));
  assert.deepEqual(await ended, { type: 'ended', callId: call.callId, reason: 'declined' });
});

test('expired mailbox ciphertext is pruned on restart without being delivered', async () => {
  const resource = await setup({ mailboxTtlMs: 80 });
  const sender = await openSocket(resource);
  const recipient = await openSocket(resource);
  await register(sender, token('1'), bundle(58, 0), 7);
  const recipientInfo = await register(recipient, token('2'), bundle(59, 0), 7);
  await closeSocket(recipient);
  const message = { type: 'envelope', to: recipientInfo.number, id: uuid(5_301), cipherType: 2, body: base64('expires') };
  const queued = waitFor(sender, (item) => item.type === 'queued');
  sender.send(JSON.stringify(message));
  await queued;
  await new Promise((resolve) => setTimeout(resolve, 100));

  await resource.server.close();
  resource.server = await createSignalingServer({
    dataFile: join(resource.directory, 'identities.json'), env: {}, heartbeatIntervalMs: 60_000,
    mailboxTtlMs: 80,
  });
  const address = await resource.server.listen(0, '127.0.0.1');
  resource.url = `ws://127.0.0.1:${address.port}/signal`;
  const reconnect = await openSocket(resource);
  const unexpectedDelivery = waitFor(reconnect, (item) => item.type === 'envelope', 100).then(() => true, () => false);
  await register(reconnect, token('2'), bundle(59, 0), 7);
  assert.equal(await unexpectedDelivery, false);
  assert.deepEqual(JSON.parse(await readFile(join(resource.directory, 'identities.json.mailbox.json'), 'utf8')).envelopes, []);
});

test('push is optional, account-bound, and sends only generic offline wake-up data', async () => {
  const disabled = await setup();
  const disabledUser = await openSocket(disabled);
  const registration = await register(disabledUser, token('3'), bundle(60, 0), 7);
  assert.equal(registration.pushEnabled, false);
  assert.deepEqual(await sendRequest(disabledUser, {
    type: 'push_register', token: 'fcm-device-token-0123456789abcdef',
  }, (item) => item.type === 'push_registered'), { type: 'push_registered', pushEnabled: false });
  assert.deepEqual(await sendRequest(disabledUser, {
    type: 'push_register', number: registration.number, token: 'fcm-device-token-0123456789abcdef',
  }, (item) => item.type === 'error'), { type: 'error', code: 'invalid_message' });
  await assert.rejects(readFile(join(disabled.directory, 'identities.json.push.json')),
    (failure) => failure.code === 'ENOENT');
  await assert.rejects(readFile(join(disabled.directory, 'identities.json.push-endpoints.json')),
    (failure) => failure.code === 'ENOENT');
  assert.deepEqual(await sendRequest(disabledUser, {
    type: 'push_register', provider: 'unifiedpush', endpoint: 'https://push.example.test/up/v7',
  }, (item) => item.type === 'error'), { type: 'error', code: 'invalid_message' });

  const pushes = [];
  const enabled = await setup({ pushSender: async (record, payload) => { pushes.push({ record, payload }); } });
  const sender = await openSocket(enabled);
  const recipient = await openSocket(enabled);
  await register(sender, token('4'), bundle(61, 0), 8);
  const recipientInfo = await register(recipient, token('5'), bundle(62, 0), 8);
  const insecureEndpoint = await sendRequest(recipient, {
    type: 'push_register', provider: 'unifiedpush', endpoint: 'http://push.example.test/up/insecure',
  }, (item) => item.type === 'error');
  assert.deepEqual(insecureEndpoint, { type: 'error', code: 'invalid_push_endpoint' });
  const validPush = await sendRequest(recipient, {
    type: 'push_register', provider: 'unifiedpush', endpoint: 'https://push.example.test/up/first',
  }, (item) => item.type === 'push_registered');
  assert.deepEqual(validPush, { type: 'push_registered', pushEnabled: true, provider: 'unifiedpush' });
  const registeredPushAgain = await sendRequest(recipient, {
    type: 'push_register', provider: 'unifiedpush', endpoint: 'https://push.example.test/up/second',
  }, (item) => item.type === 'push_registered');
  assert.deepEqual(registeredPushAgain, { type: 'push_registered', pushEnabled: true, provider: 'unifiedpush' });
  const pushFile = join(enabled.directory, 'identities.json.push-endpoints.json');
  assert.equal((await stat(pushFile)).mode & 0o777, 0o600);
  assert.deepEqual(Object.keys(JSON.parse(await readFile(pushFile, 'utf8')).endpoints), [recipientInfo.number]);
  assert.equal(JSON.parse(await readFile(pushFile, 'utf8')).endpoints[recipientInfo.number].endpoint,
    'https://push.example.test/up/second');
  await closeSocket(recipient);

  const message = {
    type: 'envelope', to: recipientInfo.number, id: uuid(5_401), cipherType: 3,
    body: base64('private-ciphertext-not-in-push'),
  };
  const queued = waitFor(sender, (item) => item.type === 'queued' && item.id === message.id);
  sender.send(JSON.stringify(message));
  await queued;
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(pushes.length, 1);
  assert.equal(pushes[0].pushToken, 'fcm-device-token-0123456789abcdef');
  assert.deepEqual(pushes[0].payload, { kind: 'message', id: message.id, ttlMs: 24 * 60 * 60 * 1_000 });
  assert.equal(JSON.stringify(pushes[0].payload).includes(message.body), false);
  assert.equal(JSON.stringify(pushes[0].payload).includes('from'), false);
});

test('partial or unreadable FCM configuration fails closed instead of reporting push available', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'signal-fcm-config-'));
  try {
    await assert.rejects(createSignalingServer({
      dataFile: join(directory, 'identities.json'), env: { FCM_PROJECT_ID: 'line-demo' },
    }), /FCM_PROJECT_ID and GOOGLE_APPLICATION_CREDENTIALS must be configured together/);
    await assert.rejects(createSignalingServer({
      dataFile: join(directory, 'identities.json'),
      env: { FCM_PROJECT_ID: 'line-demo', GOOGLE_APPLICATION_CREDENTIALS: join(directory, 'missing-service-account.json') },
    }), /FCM configuration is invalid/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('FCM invalid-token responses remove the matching account token', async () => {
  const resource = await setup({ pushSender: async () => {
    const failure = new Error('provider failure');
    failure.code = 'messaging/registration-token-not-registered';
    throw failure;
  } });
  const sender = await openSocket(resource);
  const recipient = await openSocket(resource);
  await register(sender, token('c'), bundle(70, 0), 7);
  const recipientInfo = await register(recipient, token('d'), bundle(71, 0), 7);
  await sendRequest(recipient, { type: 'push_register', token: 'fcm-invalid-token-0123456789abcdef' },
    (item) => item.type === 'push_registered');
  await closeSocket(recipient);
  const queued = waitFor(sender, (item) => item.type === 'queued');
  sender.send(JSON.stringify({ type: 'envelope', to: recipientInfo.number, id: uuid(5_451),
    cipherType: 2, body: base64('retry-after-push-failure') }));
  await queued;
  let storedTokens;
  for (let attempt = 0; attempt < 100; attempt += 1) {
    storedTokens = JSON.parse(await readFile(join(resource.directory, 'identities.json.push.json'), 'utf8')).tokens;
    if (Object.keys(storedTokens).length === 0) break;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  assert.deepEqual(storedTokens, {});
});

test('offline calls require a registered push token, create the room first, and can be accepted on reconnect', async () => {
  const pushes = [];
  const resource = await setup({
    env: LIVEKIT_ENV,
    pushSender: async (pushToken, payload) => { pushes.push({ pushToken, payload }); },
    createRoom: async () => {},
    deleteRoom: () => {},
  });
  const owner = await openSocket(resource);
  const member = await openSocket(resource);
  const ownerInfo = await register(owner, token('6'), bundle(63, 0), 7);
  const memberInfo = await register(member, token('7'), bundle(64, 0), 7);
  await sendRequest(member, { type: 'push_register', token: 'fcm-call-token-0123456789abcdef' },
    (item) => item.type === 'push_registered');
  await closeSocket(member);

  const created = waitFor(owner, (item) => item.type === 'call_created');
  owner.send(JSON.stringify({ type: 'create_call', members: [memberInfo.number] }));
  const call = await created;
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(pushes.length, 1);
  assert.equal(pushes[0].payload.kind, 'call');
  assert.equal(pushes[0].payload.id, call.callId);
  assert.equal(pushes[0].payload.ttlMs, 45_000);
  assert.equal(JSON.stringify(pushes[0].payload).includes(ownerInfo.number), false);

  const ended = waitFor(owner, (item) => item.type === 'ended' && item.callId === call.callId);
  owner.send(JSON.stringify({ type: 'leave_call', callId: call.callId }));
  await ended;
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.deepEqual(pushes.map((item) => item.payload.kind), ['call', 'call_ended']);

  const createdAgain = waitFor(owner, (item) => item.type === 'call_created');
  owner.send(JSON.stringify({ type: 'create_call', members: [memberInfo.number] }));
  const nextCall = await createdAgain;
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(pushes[2].payload.kind, 'call');
  assert.equal(pushes[2].payload.id, nextCall.callId);

  const reconnectedMember = await openSocket(resource);
  const invitation = waitFor(reconnectedMember, (item) => item.type === 'incoming' && item.callId === nextCall.callId);
  await register(reconnectedMember, token('7'), bundle(64, 0), 7);
  assert.equal((await invitation).owner, ownerInfo.number);
  const memberGrant = await sendRequest(reconnectedMember, { type: 'join_call', callId: nextCall.callId },
    (item) => item.type === 'room_grant' || item.type === 'error');
  assert.equal(memberGrant.type, 'room_grant');
  const ownerGrant = await sendRequest(owner, { type: 'join_call', callId: nextCall.callId },
    (item) => item.type === 'room_grant' || item.type === 'error');
  assert.equal(ownerGrant.type, 'room_grant');
});

test('blocked accounts cannot receive queued messages or offline call invitations', async () => {
  const resource = await setup({ env: adminEnv() });
  const admin = await openSocket(resource);
  const sender = await openSocket(resource);
  const target = await openSocket(resource);
  await register(admin, token('8'), bundle(65, 0));
  await register(sender, token('9'), bundle(66, 0), 7);
  const targetInfo = await register(target, token('0'), bundle(67, 0), 7);
  assert.equal((await adminLogin(admin)).ok, true);
  await adminAction(admin, 'block', { number: targetInfo.number });

  const message = await sendRequest(sender, {
    type: 'envelope', to: targetInfo.number, id: uuid(5_501), cipherType: 2, body: base64('blocked-ciphertext'),
  }, (item) => item.type === 'error');
  assert.equal(message.code, 'blocked');
  const call = await sendRequest(sender, { type: 'create_call', members: [targetInfo.number] },
    (item) => item.type === 'error');
  assert.equal(call.code, 'blocked');
  const mailboxFile = join(resource.directory, 'identities.json.mailbox.json');
  assert.deepEqual(JSON.parse(await readFile(mailboxFile, 'utf8')).envelopes, []);
});

test('group calls issue verifiable, short-lived, room-scoped microphone-only LiveKit grants', async () => {
  const deletedRooms = [];
  const createdRooms = [];
  const resource = await setup({
    env: LIVEKIT_ENV,
    ringingTimeoutMs: 300,
    createRoom: async (roomOptions) => { createdRooms.push(roomOptions); },
    deleteRoom: (room) => deletedRooms.push(room),
  });
  const owner = await openSocket(resource);
  const memberA = await openSocket(resource);
  const memberB = await openSocket(resource);
  const outsider = await openSocket(resource);
  const ownerInfo = await register(owner, token('2'), bundle(21, 0));
  assert.equal(ownerInfo.mediaReady, true);
  const memberAInfo = await register(memberA, token('3'), bundle(22, 0));
  const memberBInfo = await register(memberB, token('4'), bundle(23, 0));
  await register(outsider, token('5'), bundle(24, 0));

  const inviteA = waitFor(memberA, (item) => item.type === 'incoming');
  const inviteB = waitFor(memberB, (item) => item.type === 'incoming');
  const created = waitFor(owner, (item) => item.type === 'call_created');
  owner.send(JSON.stringify({ type: 'create_call', members: [memberAInfo.number, memberBInfo.number] }));
  const [call, incomingA, incomingB] = await Promise.all([created, inviteA, inviteB]);
  assert.match(call.callId, /^[0-9a-f-]{36}$/i);
  assert.equal(call.room, `line-${call.callId}`);
  assert.deepEqual(call.members, [ownerInfo.number, memberAInfo.number, memberBInfo.number]);
  assert.equal(call.owner, ownerInfo.number);
  assert.deepEqual(incomingA, { type: 'incoming', callId: call.callId, room: call.room, members: call.members, owner: call.owner });
  assert.deepEqual(incomingB, incomingA);
  assert.deepEqual(createdRooms, [{ name: call.room, maxParticipants: 3 }]);

  const unauthorized = await sendRequest(outsider, { type: 'join_call', callId: call.callId }, (item) => item.type === 'error');
  assert.equal(unauthorized.code, 'unauthorized');
  const ownerBusy = await sendRequest(owner, { type: 'create_call', members: [outsider.number ?? '99999999'] }, (item) => item.type === 'error');
  assert.equal(ownerBusy.code, 'busy');

  const grants = await Promise.all([owner, memberA, memberB].map((socket) => sendRequest(
    socket,
    { type: 'join_call', callId: call.callId },
    (item) => item.type === 'room_grant' || item.type === 'error',
  )));
  const verifier = new TokenVerifier(LIVEKIT_ENV.LIVEKIT_API_KEY, LIVEKIT_ENV.LIVEKIT_API_SECRET);
  for (const [index, grant] of grants.entries()) {
    assert.deepEqual(Object.keys(grant).sort(), ['callId', 'members', 'owner', 'room', 'token', 'type', 'url'].sort());
    assert.equal(grant.url, LIVEKIT_ENV.LIVEKIT_URL);
    assert.equal(grant.room, call.room);
    assert.deepEqual(grant.members, call.members);
    const claims = await verifier.verify(grant.token);
    assert.equal(claims.sub, call.members[index]);
    const lifetime = claims.exp - claims.nbf;
    assert.ok(lifetime >= 119 && lifetime <= 120, `Unexpected JWT lifetime: ${lifetime}`);
    assert.deepEqual(claims.video, {
      roomJoin: true,
      room: call.room,
      canPublishSources: ['microphone'],
      canSubscribe: true,
      canPublishData: false,
    });
  }

  await new Promise((resolve) => setTimeout(resolve, 450));
  const stillBusy = await sendRequest(owner, { type: 'create_call', members: [outsider.number ?? '99999999'] }, (item) => item.type === 'error');
  assert.equal(stillBusy.code, 'busy');
  const endedA = waitFor(memberA, (item) => item.type === 'ended');
  const endedB = waitFor(memberB, (item) => item.type === 'ended');
  owner.send(JSON.stringify({ type: 'leave_call', callId: call.callId }));
  assert.deepEqual(await Promise.all([endedA, endedB]), [
    { type: 'ended', callId: call.callId, reason: 'left' },
    { type: 'ended', callId: call.callId, reason: 'left' },
  ]);
  assert.deepEqual(deletedRooms, [call.room]);
});

test('failed room provisioning announces no call, cleans up, and releases the roster', async () => {
  const createdRooms = [];
  let reportDeletion;
  const deletionReported = new Promise((resolve) => { reportDeletion = resolve; });
  const resource = await setup({
    env: LIVEKIT_ENV,
    createRoom: async (options) => {
      createdRooms.push(options);
      if (createdRooms.length === 1) throw new Error('provisioning failed');
    },
    deleteRoom: (room) => reportDeletion(room),
  });
  const owner = await openSocket(resource);
  const member = await openSocket(resource);
  await register(owner, token('a'), bundle(71, 0));
  const memberInfo = await register(member, token('b'), bundle(72, 0));

  const failure = waitFor(owner, (message) => message.type === 'error');
  owner.send(JSON.stringify({ type: 'create_call', members: [memberInfo.number] }));
  assert.equal((await failure).code, 'media_unavailable');
  const firstRoom = await deletionReported;
  assert.equal(firstRoom, createdRooms[0].name);
  assert.deepEqual(createdRooms[0], { name: firstRoom, maxParticipants: 2 });
  assert.equal(await waitFor(member, (message) => message.type === 'incoming', 100).then(() => true, () => false), false);

  const created = waitFor(owner, (message) => message.type === 'call_created');
  const incoming = waitFor(member, (message) => message.type === 'incoming');
  owner.send(JSON.stringify({ type: 'create_call', members: [memberInfo.number] }));
  const [call] = await Promise.all([created, incoming]);
  assert.deepEqual(createdRooms[1], { name: call.room, maxParticipants: 2 });
});

test('calls disabled during room provisioning cannot announce or revive the call', async () => {
  let releaseProvisioning;
  let reportProvisioningStarted;
  let reportDeletion;
  const provisioningGate = new Promise((resolve) => { releaseProvisioning = resolve; });
  const provisioningStarted = new Promise((resolve) => { reportProvisioningStarted = resolve; });
  const deletionReported = new Promise((resolve) => { reportDeletion = resolve; });
  let roomName;
  const resource = await setup({
    env: { ...LIVEKIT_ENV, ...adminEnv() },
    createRoom: async (options) => {
      roomName = options.name;
      assert.equal(options.maxParticipants, 2);
      reportProvisioningStarted();
      await provisioningGate;
    },
    deleteRoom: (room) => reportDeletion(room),
  });
  const owner = await openSocket(resource);
  const member = await openSocket(resource);
  await register(owner, token('c'), bundle(73, 0));
  const memberInfo = await register(member, token('d'), bundle(74, 0));
  assert.equal((await adminLogin(owner)).ok, true);

  owner.send(JSON.stringify({ type: 'create_call', members: [memberInfo.number] }));
  await provisioningStarted;
  assert.equal((await adminAction(owner, 'update_settings', { settings: { callsEnabled: false } })).ok, true);
  const callCreated = waitFor(owner, (message) => message.type === 'call_created', 150).then(() => true, () => false);
  const invited = waitFor(member, (message) => message.type === 'incoming', 150).then(() => true, () => false);
  releaseProvisioning();
  assert.equal(await deletionReported, roomName);
  assert.equal(await callCreated, false);
  assert.equal(await invited, false);

  assert.match(roomName, /^line-[0-9a-f-]{36}$/i);
  await adminAction(owner, 'update_settings', { settings: { callsEnabled: true } });
  const staleGrant = await sendRequest(owner, { type: 'join_call', callId: roomName.slice('line-'.length) },
    (message) => message.type === 'error');
  assert.equal(staleGrant.code, 'unauthorized');
});

for (const [kind, env] of [
  ['missing', {}],
  ['insecure', { ...LIVEKIT_ENV, LIVEKIT_URL: 'ws://rtc.example.test' }],
]) {
test(`${kind} LiveKit configuration never creates a placeholder token`, async () => {
  const resource = await setup({ env });
  const owner = await openSocket(resource);
  const member = await openSocket(resource);
  const ownerInfo = await register(owner, token('6'), bundle(25, 0));
  const memberInfo = await register(member, token('7'), bundle(26, 0));
  assert.equal(ownerInfo.mediaReady, false);
  const result = await sendRequest(owner, { type: 'create_call', members: [memberInfo.number] },
    (item) => item.type === 'error');
  assert.deepEqual(result, { type: 'error', code: 'media_not_configured' });
  assert.equal(await waitFor(member, (item) => item.type === 'incoming', 100).then(() => true, () => false), false);
  assert.match(ownerInfo.number, /^\d{8}$/);
});
}

test('LiveKit media configuration requires a clean WSS root URL and valid host', async () => {
  const invalidUrls = [
    'ws://rtc.example.test',
    'https://rtc.example.test',
    'wss:///signal',
    'wss://user@rtc.example.test',
    'wss://user:pass@rtc.example.test',
    'wss://@rtc.example.test',
    'wss://rtc.example.test?token=secret',
    'wss://rtc.example.test?',
    'wss://rtc.example.test#fragment',
    'wss://rtc.example.test#',
    'wss://rtc.example.test/room',
    'wss://bad_host.example.test',
    'wss://-bad.example.test',
    'wss://example..test',
    'wss://rtc.example.test:0',
    'wss://rtc.example.test:65536',
  ];

  for (const [index, LIVEKIT_URL] of invalidUrls.entries()) {
    const resource = await setup({ env: { ...LIVEKIT_ENV, LIVEKIT_URL } });
    const socket = await openSocket(resource);
    const registered = await register(socket, token(String(index % 10)), bundle(50 + index, 0));
    assert.equal(registered.mediaReady, false, `Unexpectedly accepted ${LIVEKIT_URL}`);
  }

  const rootUrl = await setup({ env: { ...LIVEKIT_ENV, LIVEKIT_URL: 'wss://rtc.example.test/' } });
  const socket = await openSocket(rootUrl);
  assert.equal((await register(socket, token('8'), bundle(70, 0))).mediaReady, true);
});

test('decline, disconnect, and unanswered timeout end the entire fixed roster', async () => {
  const deletedRooms = [];
  const resource = await setup({
    env: LIVEKIT_ENV,
    createRoom: async () => {},
    deleteRoom: (room) => deletedRooms.push(room),
    ringingTimeoutMs: 500,
  });
  const owner = await openSocket(resource);
  const member = await openSocket(resource);
  const memberInfo = await register(member, token('8'), bundle(27, 0));
  await register(owner, token('9'), bundle(28, 0));
  const incoming = waitFor(member, (item) => item.type === 'incoming');
  const created = waitFor(owner, (item) => item.type === 'call_created');
  owner.send(JSON.stringify({ type: 'create_call', members: [memberInfo.number] }));
  const call = await created;
  await incoming;
  const ended = waitFor(member, (item) => item.type === 'ended');
  assert.deepEqual(await ended, { type: 'ended', callId: call.callId, reason: 'timeout' });
  assert.deepEqual(deletedRooms, [call.room]);

  const memberAgain = await openSocket(resource);
  await register(memberAgain, token('8'), bundle(27, 0));
  const incomingAgain = waitFor(memberAgain, (item) => item.type === 'incoming');
  const createdAgain = waitFor(owner, (item) => item.type === 'call_created');
  owner.send(JSON.stringify({ type: 'create_call', members: [memberInfo.number] }));
  const disconnectedCall = await createdAgain;
  await incomingAgain;
  const disconnected = waitFor(owner, (item) => item.type === 'ended' && item.callId === disconnectedCall.callId);
  await closeSocket(memberAgain);
  assert.deepEqual(await disconnected, { type: 'ended', callId: disconnectedCall.callId, reason: 'timeout' });
  assert.deepEqual(deletedRooms, [call.room, disconnectedCall.room]);
});

test('registration deadline and per-IP registration limits are enforced', async () => {
  const resource = await setup({ registrationTimeoutMs: 80, maxRegistrationsPerIp: 1 });
  const idle = await openSocket(resource);
  assert.equal((await waitFor(idle, (item) => item.type === 'error')).code, 'registration_timeout');

  const first = await openSocket(resource);
  assert.equal((await register(first, token('a'), bundle(29, 0))).type, 'registered');
  const second = await openSocket(resource);
  assert.equal((await register(second, token('b'), bundle(30, 0))).code, 'rate_limited');
});

test('admin hash helper emits only a usable scrypt verifier and enforces password length', () => {
  const helper = fileURLToPath(new URL('../scripts/hash-admin-password.mjs', import.meta.url));
  const output = execFileSync(process.execPath, [helper], {
    input: `${ADMIN_CODE}\n`, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'],
  }).trim();
  const match = /^scrypt\$([A-Za-z0-9+/]+={0,2})\$([A-Za-z0-9+/]+={0,2})$/.exec(output);
  assert.ok(match);
  assert.deepEqual(scryptSync(ADMIN_CODE, Buffer.from(match[1], 'base64'), 64), Buffer.from(match[2], 'base64'));
  assert.equal(output.includes(ADMIN_CODE), false);
  assert.throws(() => execFileSync(process.execPath, [helper], {
    input: 'short\n', encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'],
  }));
});

test('admin login is hash-verified, scoped to one socket, expires, and exposes privacy-safe status', async () => {
  const resource = await setup({ env: adminEnv(), adminSessionMs: 500 });
  const socket = await openSocket(resource);
  assert.equal((await adminLogin(socket)).error, 'registration_required');
  await register(socket, token('0'), bundle(30, 0));
  const rejected = await adminAction(socket, 'status');
  assert.deepEqual(rejected, {
    type: 'admin_result', requestId: rejected.requestId, ok: false, error: 'unauthorized',
  });

  assert.equal((await adminLogin(socket, 'not-the-secret')).error, 'invalid_credentials');
  const login = await adminLogin(socket);
  assert.equal(login.ok, true);
  assert.ok(login.expiresAt > Date.now());
  const status = await adminAction(socket, 'status');
  assert.equal(status.ok, true);
  assert.deepEqual(status.result.settings, {
    callsEnabled: true, chatEnabled: true, registrationEnabled: true, maxParticipants: 8,
  });
  assert.deepEqual(status.result.metrics, {
    online: 1, registered: 1, activeCalls: 0, mediaConfigured: false,
    uptimeSeconds: status.result.metrics.uptimeSeconds,
  });
  assert.ok(status.result.events.every((event) => Object.keys(event).sort().join(',') === 'at,event,outcome'));
  assert.equal(JSON.stringify(status.result.events).includes(ADMIN_CODE), false);

  const otherSocket = await openSocket(resource);
  assert.equal((await adminAction(otherSocket, 'status')).error, 'unauthorized');
  assert.equal((await adminAction(socket, 'logout')).result.loggedOut, true);
  assert.equal((await adminAction(socket, 'status')).error, 'unauthorized');
  assert.equal((await adminLogin(socket)).ok, true);
  await closeSocket(socket);
  assert.equal((await adminAction(otherSocket, 'status')).error, 'unauthorized');
  const expiringSocket = await openSocket(resource);
  await register(expiringSocket, token('1'), bundle(31, 0));
  assert.equal((await adminLogin(expiringSocket)).ok, true);
  await new Promise((resolve) => setTimeout(resolve, 550));
  assert.equal((await adminAction(expiringSocket, 'status')).error, 'expired');
});

test('logout cancels a pending password check and cannot leave an authenticated socket', async () => {
  const resource = await setup({ env: adminEnv() });
  const socket = await openSocket(resource);
  await register(socket, token('7'), bundle(47, 0));
  const login = adminLogin(socket);
  const logout = await adminAction(socket, 'logout');
  assert.equal(logout.result.loggedOut, true);
  assert.equal((await login).error, 'login_cancelled');
  assert.equal((await adminAction(socket, 'status')).error, 'unauthorized');
});

test('admin login failures are rate limited and lock out the peer until the lock expires', async () => {
  const resource = await setup({ env: adminEnv(), adminLockMs: 80, adminRateWindowMs: 250 });
  const socket = await openSocket(resource);
  await register(socket, token('a'), bundle(38, 0));
  for (let attempt = 0; attempt < 5; attempt += 1) {
    assert.equal((await adminLogin(socket, 'bad')).error, 'invalid_credentials');
  }
  assert.equal((await adminLogin(socket, ADMIN_CODE)).error, 'rate_limited');
  await new Promise((resolve) => setTimeout(resolve, 280));
  assert.equal((await adminLogin(socket)).ok, true);
});

test('a failed login still counts against the peer limit when its socket disconnects during scrypt', async () => {
  let releaseDerivation;
  let reportStarted;
  let reportFinished;
  const derivationGate = new Promise((resolve) => { releaseDerivation = resolve; });
  const derivationStarted = new Promise((resolve) => { reportStarted = resolve; });
  const derivationFinished = new Promise((resolve) => { reportFinished = resolve; });
  const resource = await setup({
    env: adminEnv(),
    deriveAdminPassword: async (code, salt) => {
      if (code === 'wrong-code-on-disconnect') {
        reportStarted();
        await derivationGate;
        setImmediate(reportFinished);
      }
      return scryptSync(code, salt, 64);
    },
  });
  const abandoned = await openSocket(resource);
  await register(abandoned, token('b'), bundle(39, 0));
  abandoned.send(JSON.stringify({ type: 'admin_login', requestId: uuid(30_001), code: 'wrong-code-on-disconnect' }));
  await derivationStarted;
  await closeSocket(abandoned);
  releaseDerivation();
  await derivationFinished;

  const retry = await openSocket(resource);
  await register(retry, token('c'), bundle(40, 0));
  for (let attempt = 0; attempt < 4; attempt += 1) {
    assert.equal((await adminLogin(retry, 'wrong-code-still-open')).error, 'invalid_credentials');
  }
  assert.equal((await adminLogin(retry)).error, 'rate_limited');
});

test('missing admin hash disables admin access without affecting ordinary registration', async () => {
  const resource = await setup();
  const socket = await openSocket(resource);
  assert.equal((await adminLogin(socket)).error, 'registration_required');
  assert.equal((await register(socket, token('a'), bundle(31, 0))).type, 'registered');
  assert.equal((await adminLogin(socket)).error, 'admin_disabled');
});

test('invalid or stale admin stores fail startup closed', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'signal-admin-store-'));
  const dataFile = join(directory, 'identities.json');
  const adminDataFile = join(directory, 'admin.json');
  try {
    await writeFile(adminDataFile, '{invalid json', { mode: 0o600 });
    await assert.rejects(createSignalingServer({ dataFile, adminDataFile, env: {} }), /Admin store is not valid JSON/);
    await writeFile(adminDataFile, JSON.stringify({
      version: 1,
      settings: { callsEnabled: true, chatEnabled: true, registrationEnabled: true, maxParticipants: 8 },
      blockedNumbers: ['12345678'],
    }), { mode: 0o600 });
    await assert.rejects(createSignalingServer({ dataFile, adminDataFile, env: {} }), /unknown accounts/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('admin settings persist and enforce chat, call size, calls, and new-registration controls', async () => {
  const resource = await setup({ env: adminEnv() });
  const admin = await openSocket(resource);
  const peerA = await openSocket(resource);
  const peerB = await openSocket(resource);
  const adminInfo = await register(admin, token('2'), bundle(32, 0));
  const peerAInfo = await register(peerA, token('3'), bundle(33, 0));
  const peerBInfo = await register(peerB, token('4'), bundle(34, 0));
  assert.equal((await adminAction(peerA, 'status')).error, 'unauthorized');
  assert.equal((await adminLogin(admin)).ok, true);

  const capabilitiesChanged = waitFor(peerA, (message) => message.type === 'capabilities');
  const update = await adminAction(admin, 'update_settings', {
    settings: { chatEnabled: false, maxParticipants: 2 },
  });
  assert.deepEqual(update.result.settings, {
    callsEnabled: true, chatEnabled: false, registrationEnabled: true, maxParticipants: 2,
  });
  const caps = await capabilitiesChanged;
  assert.deepEqual(caps, {
    type: 'capabilities', callsEnabled: true, chatEnabled: false,
    registrationEnabled: true, maxParticipants: 2, mediaReady: false,
  });
  const body = base64('encrypted');
  const disabledChat = await sendRequest(admin, {
    type: 'envelope', to: peerAInfo.number, id: uuid(40), cipherType: 2, body,
  }, (message) => message.type === 'error');
  assert.equal(disabledChat.code, 'chat_disabled');
  assert.equal((await sendRequest(admin, {
    type: 'create_call', members: [peerAInfo.number, peerBInfo.number],
  }, (message) => message.type === 'error')).code, 'invalid_message');

  const disabled = await adminAction(admin, 'update_settings', {
    settings: { callsEnabled: false, registrationEnabled: false },
  });
  assert.equal(disabled.ok, true);
  assert.equal((await sendRequest(admin, { type: 'create_call', members: [peerAInfo.number] },
    (message) => message.type === 'error')).code, 'calls_disabled');
  const newcomer = await openSocket(resource);
  assert.equal((await register(newcomer, token('5'), bundle(35, 0))).code, 'registration_disabled');
  const existingLogin = await openSocket(resource);
  assert.equal((await register(existingLogin, token('3'), bundle(33, 0))).type, 'registered');

  const store = join(resource.directory, 'identities.json.admin.json');
  assert.equal((await stat(store)).mode & 0o777, 0o600);
  const adminData = JSON.parse(await readFile(store, 'utf8'));
  assert.deepEqual(adminData.settings, disabled.result.settings);
  assert.equal((await adminAction(admin, 'clear_events')).result.cleared, true);
  const cleared = await adminAction(admin, 'status');
  assert.deepEqual(cleared.result.events.map((event) => event.event), ['events_cleared']);
  assert.equal((await adminAction(admin, 'update_settings', { settings: { arbitrary: true } })).error, 'invalid_message');
  assert.equal((await adminAction(admin, 'update_settings', { settings: { maxParticipants: 9 } })).error, 'invalid_settings');

  await resource.server.close();
  resource.server = await createSignalingServer({ dataFile: join(resource.directory, 'identities.json'), env: adminEnv(), heartbeatIntervalMs: 60_000 });
  const address = await resource.server.listen(0, '127.0.0.1');
  resource.url = `ws://127.0.0.1:${address.port}/signal`;
  const afterRestart = await openSocket(resource);
  assert.equal((await register(afterRestart, token('3'), bundle(33, 0))).callsEnabled, false);
});

test('admin blocking ends calls, disconnects accounts, persists the list, and prevents reconnection', async () => {
  const resource = await setup({ env: { ...LIVEKIT_ENV, ...adminEnv() }, createRoom: async () => {}, deleteRoom: () => {} });
  const admin = await openSocket(resource);
  const target = await openSocket(resource);
  const adminInfo = await register(admin, token('6'), bundle(36, 0));
  const targetInfo = await register(target, token('7'), bundle(37, 0));
  assert.equal((await adminLogin(admin)).ok, true);
  assert.equal((await adminAction(admin, 'block', { number: adminInfo.number })).error, 'self_block_denied');

  const callCreated = waitFor(admin, (message) => message.type === 'call_created');
  const incoming = waitFor(target, (message) => message.type === 'incoming');
  admin.send(JSON.stringify({ type: 'create_call', members: [targetInfo.number] }));
  const call = await callCreated;
  await incoming;
  assert.deepEqual((await adminAction(admin, 'status')).result.calls, [{ id: call.callId, participantCount: 2 }]);
  const endedAdmin = waitFor(admin, (message) => message.type === 'ended');
  const endedTarget = waitFor(target, (message) => message.type === 'ended');
  const endedCall = await adminAction(admin, 'end_call', { callId: call.callId });
  assert.deepEqual(endedCall.result, { callId: call.callId, ended: true });
  assert.deepEqual(await Promise.all([endedAdmin, endedTarget]), [
    { type: 'ended', callId: call.callId, reason: 'admin_ended' },
    { type: 'ended', callId: call.callId, reason: 'admin_ended' },
  ]);

  const nextCallCreated = waitFor(admin, (message) => message.type === 'call_created');
  const nextIncoming = waitFor(target, (message) => message.type === 'incoming');
  admin.send(JSON.stringify({ type: 'create_call', members: [targetInfo.number] }));
  const nextCall = await nextCallCreated;
  await nextIncoming;
  const ended = waitFor(admin, (message) => message.type === 'ended');
  const blocked = waitFor(target, (message) => message.type === 'error' && message.code === 'blocked');
  const disconnected = new Promise((resolve) => target.once('close', resolve));
  const result = await adminAction(admin, 'block', { number: targetInfo.number });
  assert.deepEqual(result.result, { number: targetInfo.number, blocked: true });
  assert.deepEqual(await ended, { type: 'ended', callId: nextCall.callId, reason: 'blocked' });
  await blocked;
  await disconnected;

  const status = await adminAction(admin, 'status');
  assert.deepEqual(status.result.blockedNumbers, [targetInfo.number]);
  assert.deepEqual(status.result.calls, []);
  assert.equal(JSON.stringify(status.result.events).includes(targetInfo.number), false);
  const unblocked = await adminAction(admin, 'unblock', { number: targetInfo.number });
  assert.deepEqual(unblocked.result, { number: targetInfo.number, blocked: false });
  await adminAction(admin, 'block', { number: targetInfo.number });

  await resource.server.close();
  resource.server = await createSignalingServer({ dataFile: join(resource.directory, 'identities.json'), env: adminEnv(), heartbeatIntervalMs: 60_000 });
  const address = await resource.server.listen(0, '127.0.0.1');
  resource.url = `ws://127.0.0.1:${address.port}/signal`;
  const reconnect = await openSocket(resource);
  assert.equal((await register(reconnect, token('7'), bundle(37, 0))).code, 'blocked');
});
