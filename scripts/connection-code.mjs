import { writeFileSync } from 'node:fs';

const args = process.argv.slice(2);
const options = Object.fromEntries(args.map((value, index) => value.startsWith('--') && args[index + 1] ? [value.slice(2), args[index + 1]] : null).filter(Boolean));
const { api, 'api-pin': apiPins, media, 'media-pin': mediaPins, output } = options;
function endpoint(value, apiEndpoint) {
  const url = new URL(value);
  if (url.protocol !== 'wss:' || url.username || url.password || url.search || url.hash ||
      (apiEndpoint ? url.pathname !== '/signal' : url.pathname !== '/')) throw new Error('Use secure WSS endpoints without credentials');
  return value;
}
function pins(value) {
  const values = value?.split(',').map((pin) => pin.trim());
  if (!values?.length || values.length > 3 || values.some((pin) => !/^sha256\/[A-Za-z0-9+/]{43}=$/.test(pin))) throw new Error('Valid SPKI SHA-256 pins required');
  return values.join(',');
}
try {
  const profile = { version: 1, api: endpoint(api, true), apiPins: pins(apiPins), media: endpoint(media, false), mediaPins: pins(mediaPins) };
  const code = `LINE1.${Buffer.from(JSON.stringify(profile)).toString('base64url')}`;
  if (output) writeFileSync(output, `${code}\n`, { mode: 0o600 });
  else console.log(code);
} catch (error) {
  console.error(`Cannot create connection code: ${error.message}`);
  console.error('Usage: node scripts/connection-code.mjs --api <wss-url/signal> --api-pin <sha256/pin> --media <wss-url> --media-pin <sha256/pin> [--output <file>]');
  process.exitCode = 1;
}
