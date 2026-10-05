import assert from 'node:assert/strict';
import { readFile, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { afterEach, test } from 'node:test';
import {
  ADMIN_CODE, LIVEKIT_ENV, account, adminAction, adminEnv, adminLogin, base64, bundle, cleanup, closeSocket, openSocket, record,
  register, restart, sendRequest, setup, sleep, token, until, uuid, waitFor,
} from '../test-support/harness.js';

afterEach(cleanup);

const profileFile = (resource) => join(resource.directory, 'identities.json.profiles.json');
const setName = (socket, name) => sendRequest(socket, { type: 'profile_set', name },
  (message) => ['profile_updated', 'error'].includes(message.type));
const getProfiles = (socket, numbers, requestId = uuid(7_000)) => sendRequest(socket, { type: 'profile_get', requestId, numbers },
  (message) => message.requestId === requestId || message.type === 'error');

test('registered announces protocol 8 only to v8 sessions; v6 and v7 receive exactly what 0.7.1 sent', async () => {
  const resource = await setup();
  const before = Date.now();
  const v8 = await account(resource, '1', 201, 8);
  assert.equal(v8.info.protocol, 8);
  assert.deepEqual(v8.info.features, ['profile', 'push', 'receipts', 'missed_calls', 'attachments', 'call_resume']);
  assert.deepEqual(v8.info.pushProviders, ['unifiedpush']);
  assert.ok(v8.info.serverTime >= before && v8.info.serverTime <= Date.now());
  assert.equal(v8.info.pushEnabled, false);
  assert.equal('name' in v8.info, false);

  const future = await account(resource, '2', 202, 9);
  assert.equal(future.info.protocol, 8);
  assert.equal((await account(resource, '3', 203, 255)).info.protocol, 8);

  const base = ['callsEnabled', 'chatEnabled', 'maxParticipants', 'mediaReady', 'number', 'registrationEnabled', 'type'];
  const v7 = await account(resource, '4', 204, 7);
  assert.deepEqual(Object.keys(v7.info).sort(), [...base, 'pushEnabled'].sort());
  const v6 = await account(resource, '5', 205, 6);
  assert.deepEqual(Object.keys(v6.info).sort(), base);
  const implicit = await openSocket(resource);
  const legacy = await sendRequest(implicit, { type: 'register', token: token('6'), bundle: bundle(206, 0) },
    (message) => message.type === 'registered');
  assert.deepEqual(Object.keys(legacy).sort(), base);
});

test('v8 messages are rejected for v6 and v7 sessions exactly like unknown messages in 0.7.1', async () => {
  const resource = await setup();
  const peer = await account(resource, '1', 211, 8);
  const requests = [
    { type: 'profile_set', name: 'x' },
    { type: 'profile_get', requestId: uuid(1), numbers: [peer.number] },
    { type: 'missed_ack', callId: uuid(2) },
    { type: 'blob_create', requestId: 'r1', id: uuid(3), to: peer.number, size: 10 },
    { type: 'blob_get', requestId: 'r2', id: uuid(3) },
    { type: 'blob_ack', id: uuid(3) },
    { type: 'push_unregister' },
    { type: 'push_register', provider: 'unifiedpush', endpoint: 'https://push.example.test/up' },
    { type: 'envelope', to: peer.number, id: uuid(4), cipherType: 3, body: base64('x'), silent: true },
    { type: 'call_resume' },
  ];
  for (const version of [6, 7]) {
    const old = await account(resource, version === 6 ? '2' : '3', 212 + version, version);
    for (const message of requests) {
      const reply = await sendRequest(old.socket, message, (item) => item.type === 'error');
      assert.equal(reply.code, 'invalid_message', `v${version} ${message.type}`);
      assert.deepEqual(Object.keys(reply).filter((key) => !['type', 'code', 'id', 'requestId'].includes(key)), []);
    }
  }
});

test('profile_set normalises, stores in its own 0600 file, survives a restart and is announced at registration', async () => {
  const resource = await setup();
  await assert.rejects(readFile(profileFile(resource)), (failure) => failure.code === 'ENOENT');
  const alice = await account(resource, '1', 221, 8);
  const stored = await setName(alice.socket, '  Алия \t Нурлан\n ');
  assert.deepEqual(Object.keys(stored).sort(), ['name', 'type', 'updatedAt']);
  assert.equal(stored.type, 'profile_updated');
  assert.equal(stored.name, 'Алия Нурлан');
  assert.ok(Number.isSafeInteger(stored.updatedAt) && stored.updatedAt > 0);
  assert.equal((await stat(profileFile(resource))).mode & 0o777, 0o600);
  const document = JSON.parse(await readFile(profileFile(resource), 'utf8'));
  assert.deepEqual(document, { version: 1, profiles: { [alice.number]: { name: 'Алия Нурлан', updatedAt: stored.updatedAt, proto: 8 } } });

  assert.deepEqual(await setName(alice.socket, 'Алия   Нурлан'), stored);
  const decomposed = await setName(alice.socket, 'Rene\u0301e');
  assert.equal(decomposed.name, 'Ren\u00e9e');
  assert.ok(decomposed.updatedAt > stored.updatedAt);
  const renamed = await setName(alice.socket, 'Алия');
  assert.ok(renamed.updatedAt > decomposed.updatedAt);

  await closeSocket(alice.socket);
  await restart(resource);
  const again = await openSocket(resource);
  const registered = await register(again, token('1'), bundle(221, 0), 8);
  assert.equal(registered.number, alice.number);
  assert.equal(registered.name, 'Алия');
  const v7 = await openSocket(resource);
  const legacy = await register(v7, token('1'), bundle(221, 0), 7);
  assert.equal('name' in legacy, false);
  const cleared = await (async () => {
    const socket = await openSocket(resource);
    await register(socket, token('1'), bundle(221, 0), 8);
    return setName(socket, ' \u00a0 ');
  })();
  assert.deepEqual(cleared, { type: 'profile_updated', name: '', updatedAt: 0 });
  const third = await openSocket(resource);
  assert.equal('name' in await register(third, token('1'), bundle(221, 0), 8), false);
});

test('profile names reject control, format, bidi, private-use and over-long input with invalid_profile', async () => {
  const resource = await setup();
  const valid = ['Alice', 'Алия Нурлан', '\u04a2\u04b1\u0440\u043b\u0430\u043d', '😀'.repeat(32), 'я'.repeat(32), 'a\u00a0b', '日本語 テスト'];
  const invalid = [
    '\u0000', 'a\u0000b', '\u0007bell', 'a\u0085b', '\u007fdel', 'a\u200bb', '\u200dzwj', '\u2060word',
    'a\u202eb', 'a\u202ab', 'a\u2066b', 'a\u2069b', '\u061cb', 'a\ue000b', 'a\ud800b', '😀'.repeat(33), 'я'.repeat(33),
    '\u{E0041}tag', 'a\u0378b',
  ];
  const queue = [...valid.map((name) => ({ name, ok: true })), ...invalid.map((name) => ({ name, ok: false }))];
  let socket;
  for (const [index, entry] of queue.entries()) {
    if (index % 8 === 0) socket = (await account(resource, String(index / 8 + 1), 300 + index, 8)).socket;
    const reply = await setName(socket, entry.name);
    if (entry.ok) assert.equal(reply.type, 'profile_updated', JSON.stringify(entry.name));
    else assert.deepEqual(reply, { type: 'error', code: 'invalid_profile' }, JSON.stringify(entry.name));
  }
});

test('profile_set rejects malformed requests and is limited to ten per minute per number', async () => {
  const resource = await setup();
  const alice = await account(resource, '1', 231, 8);
  for (const message of [{ type: 'profile_set' }, { type: 'profile_set', name: 5 }, { type: 'profile_set', name: null },
    { type: 'profile_set', name: 'x', extra: 1 }, { type: 'profile_set', names: 'x' }]) {
    assert.deepEqual(await sendRequest(alice.socket, message, (item) => item.type === 'error'), { type: 'error', code: 'invalid_message' });
  }
  assert.deepEqual(await setName(alice.socket, 'x'.repeat(513)), { type: 'error', code: 'invalid_profile' });
  for (let index = 2; index <= 10; index += 1) {
    assert.equal((await setName(alice.socket, `Name ${index}`)).type, 'profile_updated');
  }
  assert.deepEqual(await setName(alice.socket, 'Name 11'), { type: 'error', code: 'rate_limited' });
  const bob = await account(resource, '2', 232, 8);
  assert.equal((await setName(bob.socket, 'Bob')).type, 'profile_updated');
});

test('profile_get lists only known profiles, validates its input and allows 30 requests a minute', async () => {
  const resource = await setup();
  const alice = await account(resource, '1', 241, 8);
  const bob = await account(resource, '2', 242, 8);
  const old = await account(resource, '3', 243, 7);
  const asker = await account(resource, '4', 244, 8);
  const aliceName = await setName(alice.socket, 'Алия');
  await setName(bob.socket, 'Бек');

  const requestId = uuid(7_100);
  const reply = await getProfiles(asker.socket, [alice.number, bob.number, old.number, '00000000'], requestId);
  assert.equal(reply.type, 'profiles');
  assert.equal(reply.requestId, requestId);
  const byNumber = Object.fromEntries(reply.profiles.map((entry) => [entry.number, entry]));
  assert.deepEqual(byNumber[alice.number], { number: alice.number, name: 'Алия', updatedAt: aliceName.updatedAt, proto: 8 });
  assert.equal(byNumber[bob.number].name, 'Бек');
  assert.equal(byNumber[old.number], undefined);
  assert.equal(byNumber['00000000'], undefined);
  assert.equal(reply.profiles.length, 2);
  assert.deepEqual((await getProfiles(asker.socket, [])).profiles, []);

  const many = Array.from({ length: 33 }, (_, index) => String(10_000_000 + index));
  const invalid = [
    { requestId: uuid(7_101), numbers: many }, { requestId: 'not-a-uuid', numbers: [alice.number] },
    { requestId: uuid(7_102), numbers: ['1234567'] }, { requestId: uuid(7_103), numbers: [alice.number, alice.number] },
    { requestId: uuid(7_104), numbers: 'abc' }, { requestId: uuid(7_105) }, { numbers: [alice.number] },
    { requestId: uuid(7_106), numbers: [alice.number], extra: true },
  ];
  for (const fields of invalid) {
    assert.deepEqual(await sendRequest(asker.socket, { type: 'profile_get', ...fields }, (item) => item.type === 'error'),
      { type: 'error', code: 'invalid_message' }, JSON.stringify(fields).slice(0, 80));
  }
  assert.equal((await getProfiles(asker.socket, many.slice(0, 32), uuid(7_107))).profiles.length, 0);
  assert.deepEqual(await sendRequest(old.socket, { type: 'profile_get', requestId: uuid(7_108), numbers: [] }, (item) => item.type === 'error'),
    { type: 'error', code: 'invalid_message' });

  // 3 successful lookups above plus the 28 below fill the window; the 31st attempt is refused.
  for (let index = 0; index < 27; index += 1) assert.equal((await getProfiles(asker.socket, [alice.number], uuid(7_200 + index))).type, 'profiles');
  const limited = await getProfiles(asker.socket, [alice.number], uuid(7_300));
  assert.deepEqual(limited, { type: 'error', code: 'rate_limited', requestId: uuid(7_300) });
});

test('profiles reach peers through lookup, envelopes and incoming calls for v8 clients only', async () => {
  const resource = await setup({ env: LIVEKIT_ENV, createRoom: async () => {}, deleteRoom: () => {} });
  const alice = await account(resource, '1', 251, 8);
  const bob = await account(resource, '2', 252, 8);
  const carol = await account(resource, '3', 253, 7);
  const named = await setName(alice.socket, 'Алия');

  const lookup = (who, to, requestId) => sendRequest(who.socket, { type: 'lookup', to, requestId, consumePreKey: false },
    (message) => message.requestId === requestId);
  const forV8 = await lookup(bob, alice.number, uuid(7_400));
  assert.deepEqual(forV8.profile, { name: 'Алия', updatedAt: named.updatedAt, proto: 8 });
  const forV7 = await lookup(carol, alice.number, uuid(7_401));
  assert.equal('profile' in forV7, false);
  assert.deepEqual(Object.keys(forV7).sort(), ['bundle', 'peer', 'requestId', 'type']);

  const message = { to: bob.number, id: uuid(7_402), cipherType: 3, body: base64('hello') };
  const delivered = waitFor(bob.socket, (item) => item.type === 'envelope');
  const before = Date.now();
  alice.socket.send(JSON.stringify({ type: 'envelope', ...message }));
  const envelope = await delivered;
  assert.equal(envelope.fromName, 'Алия');
  assert.ok(envelope.sentAt >= before && envelope.sentAt <= Date.now());
  assert.deepEqual(Object.keys(envelope).sort(), ['body', 'cipherType', 'fromName', 'from', 'id', 'sentAt', 'type'].sort());

  const toLegacy = waitFor(carol.socket, (item) => item.type === 'envelope');
  alice.socket.send(JSON.stringify({ type: 'envelope', ...message, to: carol.number, id: uuid(7_403) }));
  assert.deepEqual(Object.keys(await toLegacy).sort(), ['body', 'cipherType', 'from', 'id', 'type']);

  await closeSocket(bob.socket);
  const offline = { ...message, id: uuid(7_404), body: base64('later') };
  await sendRequest(alice.socket, { type: 'envelope', ...offline }, (item) => item.type === 'queued');
  const returned = await openSocket(resource);
  const events = record(returned);
  await register(returned, token('2'), bundle(252, 0), 8);
  await until(() => events.some((item) => item.type === 'inbox_complete'));
  const replay = events.find((item) => item.type === 'envelope');
  assert.equal(replay.fromName, 'Алия');
  assert.ok(Number.isSafeInteger(replay.sentAt) && replay.sentAt <= Date.now());

  const carolIncoming = waitFor(carol.socket, (item) => item.type === 'incoming');
  const bobIncoming = waitFor(returned, (item) => item.type === 'incoming');
  alice.socket.send(JSON.stringify({ type: 'create_call', members: [bob.number, carol.number] }));
  const forBob = await bobIncoming;
  assert.equal(forBob.ownerName, 'Алия');
  const forCarol = await carolIncoming;
  assert.equal('ownerName' in forCarol, false);
  assert.deepEqual(Object.keys(forCarol).sort(), ['callId', 'members', 'owner', 'room', 'type']);
});

test('blocking removes the profile, endpoint and lookup of a number; unblocking does not bring them back', async () => {
  const resource = await setup({ env: adminEnv() });
  const admin = await account(resource, '1', 261, 8);
  const target = await account(resource, '2', 262, 8);
  assert.equal((await adminLogin(admin.socket, ADMIN_CODE)).ok, true);
  await setName(target.socket, 'Цель');
  await sendRequest(target.socket, { type: 'push_register', provider: 'unifiedpush', endpoint: 'https://push.example.test/up/target' },
    (item) => item.type === 'push_registered');
  const endpointFile = join(resource.directory, 'identities.json.push-endpoints.json');
  assert.ok(JSON.parse(await readFile(endpointFile, 'utf8')).endpoints[target.number]);
  assert.equal((await getProfiles(admin.socket, [target.number], uuid(7_500))).profiles.length, 1);

  const blocked = waitFor(target.socket, (item) => item.type === 'error' && item.code === 'blocked');
  assert.equal((await adminAction(admin.socket, 'block', { number: target.number })).ok, true);
  await blocked;
  assert.deepEqual((await getProfiles(admin.socket, [target.number], uuid(7_501))).profiles, []);
  assert.deepEqual(JSON.parse(await readFile(profileFile(resource), 'utf8')).profiles[target.number], undefined);
  assert.deepEqual(JSON.parse(await readFile(endpointFile, 'utf8')).endpoints, {});
  const lookup = await sendRequest(admin.socket, { type: 'lookup', to: target.number, requestId: uuid(7_502) },
    (message) => message.requestId === uuid(7_502));
  assert.equal(lookup.code, 'not_found');

  assert.equal((await adminAction(admin.socket, 'unblock', { number: target.number })).ok, true);
  assert.deepEqual((await getProfiles(admin.socket, [target.number], uuid(7_503))).profiles, []);
  const back = await openSocket(resource);
  const registered = await register(back, token('2'), bundle(262, 0), 8);
  assert.equal(registered.number, target.number);
  assert.equal('name' in registered, false);
  assert.equal(registered.pushEnabled, false);
  await sleep(20);
});
