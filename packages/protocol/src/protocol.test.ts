import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizePairingCode, parseSignalingMessage } from './messages.js';

test('parseSignalingMessage accepts valid device_register', () => {
  const raw = JSON.stringify({
    type: 'device_register',
    deviceId: 'android-1',
    role: 'sender',
    name: 'Pixel 8',
    platform: 'android',
    capabilities: {
      video: true,
      audio: true,
      maxWidth: 1920,
      maxHeight: 1080,
      maxFps: 60,
    },
  });

  const result = parseSignalingMessage(raw);
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.equal(result.message.type, 'device_register');
  }
});

test('parseSignalingMessage rejects malformed JSON and invalid ice_candidate', () => {
  const badJson = parseSignalingMessage('{bad');
  assert.equal(badJson.ok, false);

  const badIce = parseSignalingMessage(
    JSON.stringify({
      type: 'ice_candidate',
      sessionId: 'sess-1',
      candidate: 'candidate:1',
      sdpMid: '0',
      sdpMLineIndex: -1,
    })
  );
  assert.equal(badIce.ok, false);
});

test('normalizePairingCode strips whitespace', () => {
  assert.equal(normalizePairingCode('482 913'), '482913');
  assert.equal(normalizePairingCode(' 12 34 56 '), '123456');
});
