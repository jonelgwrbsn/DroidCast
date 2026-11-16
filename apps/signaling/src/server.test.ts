import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';
import { WebSocket } from 'ws';
import { createSignalingServer } from './server.js';
import type { SignalingMessage } from './protocol.js';

function nextMessage(ws: WebSocket): Promise<SignalingMessage> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Timed out waiting for WS message')), 3000);
    ws.once('message', (data) => {
      clearTimeout(timer);
      resolve(JSON.parse(data.toString('utf8')) as SignalingMessage);
    });
  });
}

test('signaling server pairs sender and receiver and exchanges offer/answer/ice', async () => {
  const server = createSignalingServer({
    port: 0,
    host: '127.0.0.1',
    enableDiscovery: false,
  });
  const { port } = await server.start();
  const url = `ws://127.0.0.1:${port}`;

  const receiver = new WebSocket(url);
  await new Promise<void>((resolve) => receiver.once('open', () => resolve()));
  const receiverRegPromise = nextMessage(receiver);
  receiver.send(
    JSON.stringify({
      type: 'device_register',
      deviceId: 'laptop-1',
      role: 'receiver',
      name: 'My Laptop',
      platform: 'web',
      capabilities: { video: true, audio: true, maxWidth: 1920, maxHeight: 1080, maxFps: 60 },
    })
  );
  const receiverReg = await receiverRegPromise;
  assert.equal(receiverReg.type, 'device_registered');

  const sender = new WebSocket(url);
  await new Promise<void>((resolve) => sender.once('open', () => resolve()));
  const senderRegPromise = nextMessage(sender);
  const discoveredPromise = nextMessage(receiver);
  sender.send(
    JSON.stringify({
      type: 'device_register',
      deviceId: 'phone-1',
      role: 'sender',
      name: 'Pixel 9',
      platform: 'android',
      capabilities: { video: true, audio: true, maxWidth: 1920, maxHeight: 1080, maxFps: 60 },
    })
  );
  const senderReg = await senderRegPromise;
  const discovered = await discoveredPromise;
  assert.equal(senderReg.type, 'device_registered');
  assert.equal(discovered.type, 'device_discovered');

  const senderSessionPromise = nextMessage(sender);
  const receiverSessionPromise = nextMessage(receiver);
  sender.send(
    JSON.stringify({
      type: 'create_session',
      targetDeviceId: 'laptop-1',
      quality: '1080p60',
    })
  );

  const senderSession = await senderSessionPromise;
  const receiverSession = await receiverSessionPromise;
  assert.equal(senderSession.type, 'session_created');
  assert.equal(receiverSession.type, 'session_created');
  if (senderSession.type === 'session_created' && receiverSession.type === 'session_created') {
    assert.ok(senderSession.pairingCode);
    assert.equal(receiverSession.pairingCode, undefined);

    // Reject unverified offer before pairing
    const unauthErrPromise = nextMessage(sender);
    sender.send(
      JSON.stringify({
        type: 'offer',
        sessionId: senderSession.sessionId,
        sdp: 'v=0...',
      })
    );
    const unauthErr = await unauthErrPromise;
    assert.equal(unauthErr.type, 'error');

    // Confirm pairing with code
    const senderVerifiedPromise = nextMessage(sender);
    const receiverVerifiedPromise = nextMessage(receiver);
    receiver.send(
      JSON.stringify({
        type: 'pairing_confirm',
        sessionId: receiverSession.sessionId,
        pairingCode: senderSession.pairingCode,
      })
    );
    const sv = await senderVerifiedPromise;
    const rv = await receiverVerifiedPromise;
    assert.equal(sv.type, 'pairing_verified');
    assert.equal(rv.type, 'pairing_verified');

    // Forward SDP offer once paired
    const offerForwardPromise = nextMessage(receiver);
    sender.send(
      JSON.stringify({
        type: 'offer',
        sessionId: senderSession.sessionId,
        sdp: 'v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n',
      })
    );
    const forwardedOffer = await offerForwardPromise;
    assert.equal(forwardedOffer.type, 'offer');

    // Forward SDP answer back to sender
    const answerForwardPromise = nextMessage(sender);
    receiver.send(
      JSON.stringify({
        type: 'answer',
        sessionId: receiverSession.sessionId,
        sdp: 'v=0\r\no=- 456 2 IN IP4 127.0.0.1\r\n',
      })
    );
    const forwardedAnswer = await answerForwardPromise;
    assert.equal(forwardedAnswer.type, 'answer');

    // Exchange ICE candidates in both directions
    const iceToReceiverPromise = nextMessage(receiver);
    sender.send(
      JSON.stringify({
        type: 'ice_candidate',
        sessionId: senderSession.sessionId,
        candidate: 'candidate:1 1 UDP 2122252543 192.168.1.50 50000 typ host',
        sdpMid: '0',
        sdpMLineIndex: 0,
      })
    );
    const iceToReceiver = await iceToReceiverPromise;
    assert.equal(iceToReceiver.type, 'ice_candidate');

    const iceToSenderPromise = nextMessage(sender);
    receiver.send(
      JSON.stringify({
        type: 'ice_candidate',
        sessionId: receiverSession.sessionId,
        candidate: 'candidate:2 1 UDP 2122252543 192.168.1.100 50001 typ host',
        sdpMid: '0',
        sdpMLineIndex: 0,
      })
    );
    const iceToSender = await iceToSenderPromise;
    assert.equal(iceToSender.type, 'ice_candidate');

    // Verify peer_disconnected notification when receiver disconnects
    const peerDisconnectedPromise = nextMessage(sender);
    receiver.close();
    const peerDisconnected = await peerDisconnectedPromise;
    assert.equal(peerDisconnected.type, 'peer_disconnected');
  }

  sender.close();
  await server.stop();
});

test('signaling server serves /install page and streams DroidCast.apk when available', async () => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'droidcast-apk-test-'));
  const fakeApkPath = path.join(tempDir, 'app-debug.apk');
  const apkPayload = Buffer.from('PK\x03\x04droidcast-test-apk-bytes');
  fs.writeFileSync(fakeApkPath, apkPayload);

  const server = createSignalingServer({
    port: 0,
    host: '127.0.0.1',
    enableDiscovery: false,
    receiverDistDir: path.join(tempDir, 'no-receiver-dist'),
    apkPath: fakeApkPath,
  });

  try {
    const { port } = await server.start();
    const baseUrl = `http://127.0.0.1:${port}`;

    const infoRes = await fetch(`${baseUrl}/info`);
    assert.equal(infoRes.status, 200);
    const infoJson = (await infoRes.json()) as {
      apk: {
        available: boolean;
        filename: string;
        sizeBytes: number | null;
        downloadPath: string;
        installPagePath: string;
      };
    };
    assert.equal(infoJson.apk.available, true);
    assert.equal(infoJson.apk.filename, 'DroidCast.apk');
    assert.equal(infoJson.apk.sizeBytes, apkPayload.length);
    assert.equal(infoJson.apk.downloadPath, '/download/droidcast.apk');
    assert.equal(infoJson.apk.installPagePath, '/install');

    const installRes = await fetch(`${baseUrl}/install`);
    assert.equal(installRes.status, 200);
    assert.match(installRes.headers.get('content-type') ?? '', /text\/html/);
    const installHtml = await installRes.text();
    assert.match(installHtml, /Download DroidCast\.apk/);
    assert.match(installHtml, /Oppo, Realme, OnePlus/);

    const apkRes = await fetch(`${baseUrl}/download/droidcast.apk`);
    assert.equal(apkRes.status, 200);
    assert.equal(apkRes.headers.get('content-type'), 'application/vnd.android.package-archive');
    assert.equal(
      apkRes.headers.get('content-disposition'),
      'attachment; filename="DroidCast.apk"'
    );
    assert.equal(apkRes.headers.get('content-length'), String(apkPayload.length));
    const downloaded = Buffer.from(await apkRes.arrayBuffer());
    assert.deepEqual(downloaded, apkPayload);
  } finally {
    await server.stop();
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
});

test('signaling server reports unavailable APK and returns 404 when APK is missing', async () => {
  const missingPath = path.join(os.tmpdir(), `nonexistent-${Date.now()}.apk`);
  const server = createSignalingServer({
    port: 0,
    host: '127.0.0.1',
    enableDiscovery: false,
    receiverDistDir: path.join(os.tmpdir(), `no-receiver-dist-${Date.now()}`),
    apkPath: missingPath,
  });

  try {
    const { port } = await server.start();
    const baseUrl = `http://127.0.0.1:${port}`;

    const infoRes = await fetch(`${baseUrl}/info`);
    assert.equal(infoRes.status, 200);
    const infoJson = (await infoRes.json()) as { apk: { available: boolean; sizeBytes: number | null } };
    assert.equal(infoJson.apk.available, false);
    assert.equal(infoJson.apk.sizeBytes, null);

    const installRes = await fetch(`${baseUrl}/install`);
    assert.equal(installRes.status, 200);
    const installHtml = await installRes.text();
    assert.match(installHtml, /APK not built yet on this host/);

    const apkRes = await fetch(`${baseUrl}/download/droidcast.apk`);
    assert.equal(apkRes.status, 404);
  } finally {
    await server.stop();
  }
});

test('signaling server ping/pong heartbeat terminates stale socket and cleans up abandoned session', async () => {
  const server = createSignalingServer({
    port: 0,
    host: '127.0.0.1',
    enableDiscovery: false,
    heartbeatIntervalMs: 60,
    staleDeviceTimeoutMs: 10_000,
  });

  const { port } = await server.start();
  const url = `ws://127.0.0.1:${port}`;

  const receiver = new WebSocket(url);
  const staleSender = new WebSocket(url, { autoPong: false });

  try {
    await Promise.all([
      new Promise<void>((resolve) => receiver.once('open', () => resolve())),
      new Promise<void>((resolve) => staleSender.once('open', () => resolve())),
    ]);

    const receiverRegPromise = nextMessage(receiver);
    receiver.send(
      JSON.stringify({
        type: 'device_register',
        deviceId: 'laptop-alive',
        role: 'receiver',
        name: 'Alive Laptop',
        platform: 'web',
        capabilities: { video: true, audio: true, maxWidth: 1920, maxHeight: 1080, maxFps: 60 },
      })
    );
    assert.equal((await receiverRegPromise).type, 'device_registered');

    const senderRegPromise = nextMessage(staleSender);
    const discoveredPromise = nextMessage(receiver);
    staleSender.send(
      JSON.stringify({
        type: 'device_register',
        deviceId: 'phone-stale',
        role: 'sender',
        name: 'Stale Phone',
        platform: 'android',
        capabilities: { video: true, audio: true, maxWidth: 1920, maxHeight: 1080, maxFps: 60 },
      })
    );
    assert.equal((await senderRegPromise).type, 'device_registered');
    assert.equal((await discoveredPromise).type, 'device_discovered');

    const senderSessionPromise = nextMessage(staleSender);
    const receiverSessionPromise = nextMessage(receiver);
    staleSender.send(
      JSON.stringify({
        type: 'create_session',
        targetDeviceId: 'laptop-alive',
        quality: 'adaptive',
      })
    );
    assert.equal((await senderSessionPromise).type, 'session_created');
    assert.equal((await receiverSessionPromise).type, 'session_created');
    assert.equal(server.sessionManager.count(), 1);

    // Wait for missed ping/pong heartbeat cleanup on staleSender
    const peerDisconnected = await nextMessage(receiver);
    assert.equal(peerDisconnected.type, 'peer_disconnected');
    assert.equal(server.sessionManager.count(), 0);
    assert.equal(server.deviceManager.getById('phone-stale'), undefined);
    assert.equal(server.deviceManager.getById('laptop-alive')?.info.status, 'ready');
    assert.equal(receiver.readyState, WebSocket.OPEN);
  } finally {
    receiver.close();
    staleSender.terminate();
    await server.stop();
  }
});

