import assert from 'node:assert/strict';
import { readFile, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { afterEach, test } from 'node:test';
import { createSignalingServer } from '../src/index.js';
import { MAX_MISSED_CALLS_PER_RECIPIENT, MISSED_CALL_TTL_MS, pruneMissedCalls } from '../src/missed.js';
import {
  LIVEKIT_ENV, account, bundle, cleanup, closeSocket, openSocket, record, register, restart, sendRequest, setup, sleep, token, until, uuid,
  waitFor,
} from '../test-support/harness.js';

afterEach(cleanup);

const missedFile = (resource) => join(resource.directory, 'identities.json.missed.json');
const types = (events) => events.map((event) => event.type);

function callSetup(overrides = {}) {
  const deleted = [];
  return setup({
    env: LIVEKIT_ENV, createRoom: async () => {}, deleteRoom: (room) => deleted.push(room), maxMessagesPerSecond: 1_000,
    ...overrides,
  }).then((resource) => Object.assign(resource, { deleted }));
}

const startCall = (owner, members) => sendRequest(owner.socket, { type: 'create_call', members: members.map((member) => member.number) },
  (item) => item.type === 'call_created' || item.type === 'error');
const grant = (who, callId) => sendRequest(who.socket, { type: 'join_call', callId }, (item) => item.type === 'room_grant' || item.type === 'error');
// Any acknowledged store operation proves that earlier queued writes (such as missed-call records) have finished.
const barrier = (who) => sendRequest(who.socket, { type: 'lookup', to: who.number, requestId: uuid(9_900), consumePreKey: false },
  (item) => item.requestId === uuid(9_900));
const readMissed = async (resource) => {
  try {
    return JSON.parse(await readFile(missedFile(resource), 'utf8')).calls;
  } catch (failure) {
    if (failure.code === 'ENOENT') return [];
    throw failure;
  }
};

async function connectedCall(owner, members) {
  const incoming = members.map((member) => waitFor(member.socket, (item) => item.type === 'incoming'));
  const call = await startCall(owner, members);
  assert.equal(call.type, 'call_created');
  await Promise.all(incoming);
  for (const who of [owner, ...members]) assert.equal((await grant(who, call.callId)).type, 'room_grant');
  return call;
}

async function reconnect(resource, who, version = 8) {
  const socket = await openSocket(resource);
  const events = record(socket);
  await register(socket, token(who.digit), bundle(who.seed, 0), version);
  await until(() => events.some((item) => item.type === (version >= 7 ? 'inbox_complete' : 'registered')));
  return { socket, events };
}

test('an unanswered call is stored as missed and delivered after registered until acknowledged', async () => {
  const resource = await callSetup({ ringingTimeoutMs: 120 });
  const owner = await account(resource, '1', 601, 8);
  const callee = await account(resource, '2', 602, 8);
  await sendRequest(owner.socket, { type: 'profile_set', name: 'Алия' }, (item) => item.type === 'profile_updated');
  const call = await startCall(owner, [callee]);
  assert.equal((await waitFor(callee.socket, (item) => item.type === 'ended')).reason, 'timeout');
  await closeSocket(callee.socket);
  const before = Date.now();
  await barrier(owner);
  assert.equal((await stat(missedFile(resource))).mode & 0o777, 0o600);
  assert.deepEqual((await readMissed(resource)).map((item) => [item.to, item.from, item.callId, item.reason]),
    [[callee.number, owner.number, call.callId, 'timeout']]);

  const first = await reconnect(resource, callee);
  assert.deepEqual(types(first.events), ['registered', 'missed_call', 'inbox_complete']);
  const missed = first.events[1];
  assert.deepEqual(Object.keys(missed).sort(), ['at', 'callId', 'from', 'fromName', 'type']);
  assert.equal(missed.callId, call.callId);
  assert.equal(missed.from, owner.number);
  assert.equal(missed.fromName, 'Алия');
  assert.ok(missed.at <= before && missed.at > before - 2_000);

  const second = await reconnect(resource, callee);
  assert.deepEqual(types(second.events), ['registered', 'missed_call', 'inbox_complete']);
  const legacy = await reconnect(resource, callee, 7);
  assert.deepEqual(types(legacy.events), ['registered', 'inbox_complete']);

  await restart(resource);
  const afterRestart = await reconnect(resource, callee);
  assert.deepEqual(types(afterRestart.events), ['registered', 'missed_call', 'inbox_complete']);
  afterRestart.socket.send(JSON.stringify({ type: 'missed_ack', callId: call.callId }));
  await until(async () => (await readMissed(resource)).length === 0);
  const acknowledged = await reconnect(resource, callee);
  assert.deepEqual(types(acknowledged.events), ['registered', 'inbox_complete']);
});

test('who gets a missed call: callees who never joined and did not decline, for every way an unanswered call ends', async () => {
  const resource = await callSetup({ ringingTimeoutMs: 5_000 });
  const alice = await account(resource, '1', 611, 8);
  const bob = await account(resource, '2', 612, 8);
  const carol = await account(resource, '3', 613, 8);
  const dave = await account(resource, '4', 614, 7);
  const missed = async () => { await barrier(alice); return (await readMissed(resource)).map((item) => `${item.to}:${item.reason}`).sort(); };

  const hangup = await startCall(alice, [bob]);
  alice.socket.send(JSON.stringify({ type: 'leave_call', callId: hangup.callId }));
  await waitFor(bob.socket, (item) => item.type === 'ended');
  assert.deepEqual(await missed(), [`${bob.number}:left`]);

  const declined = await startCall(alice, [bob]);
  bob.socket.send(JSON.stringify({ type: 'decline_call', callId: declined.callId }));
  await waitFor(alice.socket, (item) => item.type === 'ended' && item.callId === declined.callId);
  assert.deepEqual(await missed(), [`${bob.number}:left`]);

  const group = await startCall(alice, [bob, carol]);
  bob.socket.send(JSON.stringify({ type: 'decline_call', callId: group.callId }));
  await waitFor(carol.socket, (item) => item.type === 'ended' && item.callId === group.callId);
  assert.deepEqual(await missed(), [`${bob.number}:left`, `${carol.number}:declined`].sort());

  const answered = await connectedCall(alice, [bob]);
  alice.socket.send(JSON.stringify({ type: 'leave_call', callId: answered.callId }));
  await waitFor(bob.socket, (item) => item.type === 'ended' && item.callId === answered.callId);
  assert.equal((await missed()).length, 2);

  const dropped = await startCall(dave, [bob]);
  dave.socket.close();
  await waitFor(bob.socket, (item) => item.type === 'ended' && item.callId === dropped.callId);
  const records = await missed();
  assert.equal(records.length, 3);
  assert.ok(records.includes(`${bob.number}:disconnected`));
});

test('missed calls keep the newest 20 per recipient, expire after 7 days and ignore acknowledgements from other accounts', async () => {
  const resource = await callSetup({ ringingTimeoutMs: 5_000 });
  const owner = await account(resource, '1', 621, 8);
  const callee = await account(resource, '2', 622, 8);
  const other = await account(resource, '3', 623, 8);
  const ids = [];
  for (let index = 0; index < MAX_MISSED_CALLS_PER_RECIPIENT + 3; index += 1) {
    const call = await startCall(owner, [callee]);
    ids.push(call.callId);
    owner.socket.send(JSON.stringify({ type: 'leave_call', callId: call.callId }));
    await waitFor(owner.socket, (item) => item.type === 'ended' && item.callId === call.callId);
  }
  await barrier(owner);
  await closeSocket(callee.socket);
  const returned = await reconnect(resource, callee);
  const delivered = returned.events.filter((item) => item.type === 'missed_call').map((item) => item.callId);
  assert.deepEqual(delivered, ids.slice(-MAX_MISSED_CALLS_PER_RECIPIENT));

  other.socket.send(JSON.stringify({ type: 'missed_ack', callId: ids.at(-1) }));
  assert.deepEqual(await sendRequest(other.socket, { type: 'missed_ack', callId: 'nope' }, (item) => item.type === 'error'), { type: 'error', code: 'invalid_message' });
  await barrier(other);
  assert.equal((await readMissed(resource)).length, MAX_MISSED_CALLS_PER_RECIPIENT);
  returned.socket.send(JSON.stringify({ type: 'missed_ack', callId: ids.at(-1).toUpperCase() }));
  await until(async () => (await readMissed(resource)).length === MAX_MISSED_CALLS_PER_RECIPIENT - 1);

  const now = Date.now();
  const sample = [
    { to: '11111111', from: '22222222', callId: uuid(1), at: now - MISSED_CALL_TTL_MS - 1, reason: 'left' },
    { to: '11111111', from: '22222222', callId: uuid(2), at: now - MISSED_CALL_TTL_MS + 60_000, reason: 'left' },
    { to: '33333333', from: '22222222', callId: uuid(3), at: now, reason: 'timeout' },
  ];
  assert.deepEqual(pruneMissedCalls(sample, now).map((item) => item.callId), [uuid(2), uuid(3)]);
});

test('stored missed calls older than seven days are dropped at startup; invalid files stop startup', async () => {
  const resource = await callSetup();
  const owner = await account(resource, '1', 631, 8);
  const callee = await account(resource, '2', 632, 8);
  await closeSocket(callee.socket);
  const now = Date.now();
  const record_ = (callId, at) => ({ to: callee.number, from: owner.number, callId, at, reason: 'timeout' });
  const document = { version: 1, calls: [
    record_(uuid(31), now - 8 * 24 * 3_600_000), record_(uuid(32), now - 2 * 24 * 3_600_000),
    { to: '99999999', from: owner.number, callId: uuid(33), at: now, reason: 'left' },
  ] };
  await writeFile(missedFile(resource), JSON.stringify(document));
  await restart(resource);
  const returned = await reconnect(resource, callee);
  assert.deepEqual(returned.events.filter((item) => item.type === 'missed_call').map((item) => item.callId), [uuid(32)]);

  await resource.server.close();
  await writeFile(missedFile(resource), JSON.stringify({ version: 1, calls: [{ to: 'x' }] }));
  await assert.rejects(createSignalingServer({ dataFile: join(resource.directory, 'identities.json'), env: {} }), /Missed-call store contains an invalid record/);
  await writeFile(missedFile(resource), '{broken');
  await assert.rejects(createSignalingServer({ dataFile: join(resource.directory, 'identities.json'), env: {} }), /Missed-call store is not valid JSON/);
  await writeFile(missedFile(resource), JSON.stringify({ version: 2, calls: [] }));
  await assert.rejects(createSignalingServer({ dataFile: join(resource.directory, 'identities.json'), env: {} }), /unsupported format/);
  await writeFile(missedFile(resource), JSON.stringify(document));
  await restart(resource);
});

test('a joined member or the owner may drop and return within the grace period: call_resume follows registered, the call survives', async () => {
  const resource = await callSetup({ callResumeGraceMs: 400 });
  const owner = await account(resource, '1', 641, 8);
  const callee = await account(resource, '2', 642, 8);
  const call = await connectedCall(owner, [callee]);
  assert.equal((await grant(owner, call.callId)).type, 'room_grant');
  const calleeEvents = record(callee.socket);

  owner.socket.terminate();
  await sleep(150);
  assert.deepEqual(types(calleeEvents.filter((item) => item.type === 'ended')), []);
  const back = await reconnect(resource, owner);
  assert.deepEqual(types(back.events), ['registered', 'call_resume', 'inbox_complete']);
  const resume = back.events[1];
  assert.deepEqual({ ...resume, joined: [...resume.joined].sort() }, {
    type: 'call_resume', callId: call.callId, room: call.room, members: [owner.number, callee.number], owner: owner.number,
    joined: [owner.number, callee.number].sort(),
  });
  const regrant = await grant({ socket: back.socket }, call.callId);
  assert.equal(regrant.room, call.room);
  assert.equal(regrant.type, 'room_grant');
  await sleep(450);
  assert.deepEqual(types(calleeEvents.filter((item) => item.type === 'ended')), []);
  assert.deepEqual(resource.deleted, []);

  const backCallee = await (async () => {
    callee.socket.terminate();
    return reconnect(resource, callee);
  })();
  assert.deepEqual(types(backCallee.events), ['registered', 'call_resume', 'inbox_complete']);
  assert.equal(backCallee.events[1].callId, call.callId);

  const ended = waitFor(back.socket, (item) => item.type === 'ended');
  backCallee.socket.send(JSON.stringify({ type: 'leave_call', callId: call.callId }));
  assert.deepEqual(await ended, { type: 'ended', callId: call.callId, reason: 'left' });
  assert.deepEqual(resource.deleted, [call.room]);
});

test('when nobody returns within the grace period the call ends for everyone, and a later registration sees no call', async () => {
  const resource = await callSetup({ callResumeGraceMs: 200 });
  const owner = await account(resource, '1', 651, 8);
  const callee = await account(resource, '2', 652, 8);
  const call = await connectedCall(owner, [callee]);
  const ended = waitFor(callee.socket, (item) => item.type === 'ended');
  const startedAt = Date.now();
  owner.socket.terminate();
  assert.deepEqual(await ended, { type: 'ended', callId: call.callId, reason: 'disconnected' });
  assert.ok(Date.now() - startedAt >= 150, 'the call must outlive the connection for the grace period');
  assert.deepEqual(resource.deleted, [call.room]);
  const late = await reconnect(resource, owner);
  assert.deepEqual(types(late.events), ['registered', 'inbox_complete']);
});

test('members who have not joined are not held in grace; they get the invitation again and no call_resume', async () => {
  const resource = await callSetup({ callResumeGraceMs: 400 });
  const owner = await account(resource, '1', 661, 8);
  const callee = await account(resource, '2', 662, 8);
  const call = await startCall(owner, [callee]);
  await waitFor(callee.socket, (item) => item.type === 'incoming');
  callee.socket.terminate();
  const back = await reconnect(resource, callee);
  assert.deepEqual(types(back.events), ['registered', 'incoming', 'inbox_complete']);
  assert.equal(back.events[1].callId, call.callId);
  assert.equal((await grant({ socket: back.socket }, call.callId)).type, 'room_grant');

  // The owner of a ringing call that has not joined yet is covered by the grace period too.
  const second = await callSetup({ callResumeGraceMs: 400 });
  const caller = await account(second, '3', 663, 8);
  const target = await account(second, '4', 664, 8);
  const ringing = await startCall(caller, [target]);
  await waitFor(target.socket, (item) => item.type === 'incoming');
  caller.socket.terminate();
  const returned = await reconnect(second, caller);
  assert.deepEqual(types(returned.events), ['registered', 'call_resume', 'inbox_complete']);
  assert.deepEqual(returned.events[1].joined, []);
  assert.equal(returned.events[1].callId, ringing.callId);
  assert.equal((await grant(target, ringing.callId)).type, 'room_grant');
});

test('version-6 and version-7 members end the call immediately, exactly as in 0.7.1, whatever the grace setting', async () => {
  for (const version of [6, 7]) {
    const resource = await callSetup({ callResumeGraceMs: 5_000 });
    const owner = await account(resource, '1', 671, version);
    const callee = await account(resource, '2', 672, version);
    const call = await connectedCall(owner, [callee]);
    const ended = waitFor(callee.socket, (item) => item.type === 'ended');
    const startedAt = Date.now();
    owner.socket.close();
    assert.deepEqual(await ended, { type: 'ended', callId: call.callId, reason: 'disconnected' });
    assert.ok(Date.now() - startedAt < 1_000);
    assert.deepEqual(resource.deleted, [call.room]);
    await cleanup();
  }
});

test('a v8 member returning as v7 ends the call without that session seeing ended before registered', async () => {
  const resource = await callSetup({ callResumeGraceMs: 2_000 });
  const owner = await account(resource, '1', 681, 8);
  const callee = await account(resource, '2', 682, 8);
  const call = await connectedCall(owner, [callee]);
  const ended = waitFor(callee.socket, (item) => item.type === 'ended');
  owner.socket.terminate();
  const downgraded = await reconnect(resource, owner, 7);
  assert.deepEqual(await ended, { type: 'ended', callId: call.callId, reason: 'disconnected' });
  assert.deepEqual(types(downgraded.events), ['registered', 'inbox_complete']);
});

test('a replaced v8 session hands the call to the new one; the other party leaving during grace ends it for good', async () => {
  const resource = await callSetup({ callResumeGraceMs: 400 });
  const owner = await account(resource, '1', 691, 8);
  const callee = await account(resource, '2', 692, 8);
  const call = await connectedCall(owner, [callee]);
  const calleeEvents = record(callee.socket);
  const replaced = waitFor(owner.socket, (item) => item.type === 'error' && item.code === 'replaced');
  const second = await reconnect(resource, owner);
  await replaced;
  assert.deepEqual(types(second.events), ['registered', 'call_resume', 'inbox_complete']);
  await sleep(100);
  assert.deepEqual(types(calleeEvents.filter((item) => item.type === 'ended')), []);

  second.socket.terminate();
  await sleep(60);
  callee.socket.send(JSON.stringify({ type: 'leave_call', callId: call.callId }));
  await waitFor(callee.socket, (item) => item.type === 'ended');
  const third = await reconnect(resource, owner);
  assert.deepEqual(types(third.events), ['registered', 'inbox_complete']);
  assert.deepEqual(resource.deleted, [call.room]);
});

test('CALL_RESUME_GRACE_MS of 0 disables resume for v8; malformed values are rejected at startup', async () => {
  const resource = await callSetup({ env: { ...LIVEKIT_ENV, CALL_RESUME_GRACE_MS: '0' } });
  const owner = await account(resource, '1', 701, 8);
  const callee = await account(resource, '2', 702, 8);
  const call = await connectedCall(owner, [callee]);
  const ended = waitFor(callee.socket, (item) => item.type === 'ended');
  owner.socket.terminate();
  assert.deepEqual(await ended, { type: 'ended', callId: call.callId, reason: 'disconnected' });
  await assert.rejects(createSignalingServer({ dataFile: join(resource.directory, 'other.json'), env: { CALL_RESUME_GRACE_MS: 'soon' } }),
    /Invalid CALL_RESUME_GRACE_MS/);
});
