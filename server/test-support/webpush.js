// Receiving side of RFC 8291 (aes128gcm), used to prove what a push distributor would actually read.
import { createDecipheriv, createECDH, hkdfSync, randomBytes } from 'node:crypto';

export function createUserAgent() {
  const keys = createECDH('prime256v1');
  keys.generateKeys();
  const authSecret = randomBytes(16);
  return {
    keys,
    authSecret,
    registration: { pubKey: keys.getPublicKey().toString('base64url'), auth: authSecret.toString('base64url') },
  };
}

export function decryptWebPush(body, { keys, authSecret }) {
  const salt = body.subarray(0, 16);
  const recordSize = body.readUInt32BE(16);
  const keyIdLength = body[20];
  const serverPublicKey = body.subarray(21, 21 + keyIdLength);
  const encrypted = body.subarray(21 + keyIdLength);
  if (recordSize < encrypted.length) throw new Error('payload exceeds the declared record size');
  const sharedSecret = keys.computeSecret(serverPublicKey);
  const keyInfo = Buffer.concat([Buffer.from('WebPush: info\0', 'latin1'), keys.getPublicKey(), serverPublicKey]);
  const inputKey = Buffer.from(hkdfSync('sha256', sharedSecret, authSecret, keyInfo, 32));
  const contentKey = Buffer.from(hkdfSync('sha256', inputKey, salt, Buffer.from('Content-Encoding: aes128gcm\0', 'latin1'), 16));
  const nonce = Buffer.from(hkdfSync('sha256', inputKey, salt, Buffer.from('Content-Encoding: nonce\0', 'latin1'), 12));
  const decipher = createDecipheriv('aes-128-gcm', contentKey, nonce);
  decipher.setAuthTag(encrypted.subarray(encrypted.length - 16));
  const padded = Buffer.concat([decipher.update(encrypted.subarray(0, encrypted.length - 16)), decipher.final()]);
  let end = padded.length;
  while (end > 0 && padded[end - 1] === 0) end -= 1;
  if (end === 0 || padded[end - 1] !== 2) throw new Error('missing final-record delimiter');
  return padded.subarray(0, end - 1);
}

// A stand-in for https.request that records every call and answers with the queued status codes.
export function fakeHttpsRequest(statuses = [201], { responseBody = '' } = {}) {
  const calls = [];
  const request = (options, onResponse) => {
    const index = calls.length;
    const handlers = {};
    const call = { options, body: undefined, destroyed: undefined };
    calls.push(call);
    const outgoing = {
      on(event, handler) { handlers[event] = handler; return outgoing; },
      destroy(failure) {
        call.destroyed = failure;
        handlers.error?.(failure ?? new Error('destroyed'));
      },
      end(body) {
        call.body = Buffer.from(body);
        const status = statuses[Math.min(index, statuses.length - 1)];
        if (status === 'hang') return;
        queueMicrotask(() => {
          const listeners = {};
          const response = {
            statusCode: status,
            on(event, handler) { listeners[event] = handler; return response; },
            destroy() { call.responseDestroyed = true; },
          };
          onResponse(response);
          listeners.data?.(Buffer.from(responseBody));
          listeners.end?.();
        });
      },
    };
    return outgoing;
  };
  request.calls = calls;
  return request;
}

// A stand-in for http2.connect: records every request and answers with the queued { status, reason } entries.
export function fakeApnsConnector(responses = [{ status: 200 }]) {
  const requests = [];
  const connected = [];
  const connect = (authority) => {
    connected.push(authority);
    return {
      closed: false,
      destroyed: false,
      on() {},
      unref() {},
      destroy() { this.destroyed = true; },
      request(headers) {
        const index = requests.length;
        const call = { headers, body: undefined };
        requests.push(call);
        const listeners = {};
        return {
          setEncoding() {},
          on(event, handler) { listeners[event] = handler; },
          close() {},
          end(body) {
            call.body = JSON.parse(body.toString('utf8'));
            const response = responses[Math.min(index, responses.length - 1)];
            queueMicrotask(() => {
              listeners.response?.({ ':status': response.status });
              if (response.reason) listeners.data?.(JSON.stringify({ reason: response.reason }));
              listeners.end?.();
            });
          },
        };
      },
    };
  };
  return { connect, requests, connected };
}
