// Shared helpers for the protocol tests that are not part of the original 0.7.1 suite.
import { scryptSync } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WebSocket } from 'ws';
import { createSignalingServer } from '../src/index.js';

export const resources = [];
export const LIVEKIT_ENV = {
  LIVEKIT_URL: 'wss://rtc.example.test',
  LIVEKIT_API_KEY: 'test-api-key',
  LIVEKIT_API_SECRET: 'test-api-secret-that-is-never-sent-to-clients',
};
export const token = (digit) => digit.repeat(64);
export const tokenFor = (index) => index.toString(16).padStart(64, '0');
export const uuid = (id) => `550e8400-e29b-41d4-a716-${String(id).padStart(12, '0')}`;
export const base64 = (value) => Buffer.from(value).toString('base64');
export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export const ADMIN_CODE = 'correct-horse-battery-staple';
let adminRequestNumber = 40_000;

export function adminEnv(code = ADMIN_CODE) {
  const salt = Buffer.from('admin-test-salt-1');
  return { ADMIN_PASSWORD_HASH: `scrypt$${salt.toString('base64')}$${scryptSync(code, salt, 64).toString('base64')}` };
}

export function bundle(seed, preKeyCount = 2) {
  return {
    identityKey: base64(`identity-${seed}`),
    registrationId: seed,
    signedPreKey: { id: seed, publicKey: base64(`signed-public-${seed}`), signature: base64(`signed-signature-${seed}`) },
    kyberPreKey: { id: seed + 10_000, publicKey: base64(`kyber-public-${seed}`), signature: base64(`kyber-signature-${seed}`) },
    preKeys: Array.from({ length: preKeyCount }, (_, index) => ({ id: seed * 100 + index, publicKey: base64(`one-time-${seed}-${index}`) })),
  };
}

export async function setup(options = {}) {
  const { directory: requestedDirectory, ...serverOptions } = options;
  const directory = requestedDirectory ?? await mkdtemp(join(tmpdir(), 'signal-test-'));
  const server = await createSignalingServer({
    dataFile: join(directory, 'identities.json'),
    env: {},
    heartbeatIntervalMs: 60_000,
    ...serverOptions,
  });
  const address = await server.listen(0, '127.0.0.1');
  const resource = {
    server, directory, port: address.port, url: `ws://127.0.0.1:${address.port}/signal`,
    http: `http://127.0.0.1:${address.port}`, sockets: [], options: serverOptions,
  };
  resources.push(resource);
  return resource;
}

// Stops the server and starts a new one on the same directory (a process restart).
export async function restart(resource, overrides = {}) {
  await resource.server.close();
  const server = await createSignalingServer({
    dataFile: join(resource.directory, 'identities.json'),
    env: {},
    heartbeatIntervalMs: 60_000,
    ...resource.options,
    ...overrides,
  });
  const address = await server.listen(0, '127.0.0.1');
  resource.server = server;
  resource.port = address.port;
  resource.url = `ws://127.0.0.1:${address.port}/signal`;
  resource.http = `http://127.0.0.1:${address.port}`;
  return resource;
}

export function connect(url) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    socket.once('open', () => resolve(socket));
    socket.once('error', reject);
  });
}

export function waitFor(socket, predicate = () => true, timeoutMs = 1_500) {
  const origin = new Error('waitFor started here');
  return new Promise((resolve, reject) => {
    const received = [];
    const timer = setTimeout(() => {
      socket.off('message', onMessage);
      const failure = new Error(`Timed out waiting for WebSocket message; saw ${JSON.stringify(received)}`);
      failure.stack = `${failure.message}\n${origin.stack.split('\n').slice(2, 5).join('\n')}`;
      reject(failure);
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

// Records everything the socket receives, in order.
export function record(socket) {
  const messages = [];
  socket.on('message', (data) => messages.push(JSON.parse(data.toString())));
  return messages;
}

export async function until(predicate, timeoutMs = 2_000, intervalMs = 10) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await predicate();
    if (value) return value;
    if (Date.now() > deadline) throw new Error('Condition not met in time');
    await sleep(intervalMs);
  }
}

export async function openSocket(resource) {
  const socket = await connect(resource.url);
  resource.sockets.push(socket);
  return socket;
}

export function register(socket, installationToken, publicBundle = bundle(1), protocolVersion = 6) {
  const result = waitFor(socket, (message) => ['registered', 'error'].includes(message.type));
  socket.send(JSON.stringify({ type: 'register', token: installationToken, bundle: publicBundle, protocolVersion }));
  return result;
}

export function sendRequest(socket, message, predicate) {
  const result = waitFor(socket, predicate);
  socket.send(JSON.stringify(message));
  return result;
}

export async function closeSocket(socket) {
  if (socket.readyState === WebSocket.CLOSED) return;
  const closed = new Promise((resolve) => socket.once('close', resolve));
  socket.close();
  await closed;
}

// Registers a fresh account and returns its socket and `registered` event.
export async function account(resource, digit, seed, protocolVersion = 8) {
  const socket = await openSocket(resource);
  const info = await register(socket, token(digit), bundle(seed, 0), protocolVersion);
  return { socket, info, number: info.number, digit, seed, protocolVersion };
}

export function adminLogin(socket, code = ADMIN_CODE) {
  const requestId = uuid(adminRequestNumber++);
  return sendRequest(socket, { type: 'admin_login', requestId, code },
    (message) => message.type === 'admin_result' && message.requestId === requestId);
}

export function adminAction(socket, action, fields = {}) {
  const requestId = uuid(adminRequestNumber++);
  return sendRequest(socket, { type: 'admin', requestId, action, ...fields },
    (message) => message.type === 'admin_result' && message.requestId === requestId);
}

export async function cleanup() {
  await Promise.all(resources.splice(0).map(async ({ server, directory, sockets }) => {
    await server.close();
    await Promise.all(sockets.map(async (socket) => {
      if (socket.readyState !== WebSocket.CLOSED) await closeSocket(socket);
    }));
    await rm(directory, { recursive: true, force: true });
  }));
}
