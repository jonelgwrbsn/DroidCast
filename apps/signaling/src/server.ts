import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import dgram from 'node:dgram';
import { fileURLToPath } from 'node:url';
import { WebSocketServer, type WebSocket } from 'ws';
import { Bonjour } from 'bonjour-service';
import { DeviceManager } from './deviceManager.js';
import { SessionManager } from './sessionManager.js';
import {
  parseSignalingMessage,
  type ErrorMessage,
  type SignalingMessage,
} from './protocol.js';
import {
  buildApkMetadata,
  renderInstallPageHtml,
  resolveApkArtifact,
} from './apkHosting.js';

export interface SignalingServerOptions {
  port?: number;
  host?: string;
  allowedOrigins?: string[];
  enableDiscovery?: boolean;
  heartbeatIntervalMs?: number;
  staleDeviceTimeoutMs?: number;
  receiverDistDir?: string;
  apkPath?: string;
}

const DISCOVERY_UDP_PORT = 39481;
const DISCOVERY_MAGIC = 'DROIDCAST_DISCOVER_V1';

const MIME_TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

function logInfo(message: string): void {
  console.log(`[INFO] ${message}`);
}

function logWarn(message: string): void {
  console.warn(`[WARN] ${message}`);
}

function logError(message: string, err?: unknown): void {
  const detail = err instanceof Error ? err.message : err ? String(err) : '';
  console.error(`[ERROR] ${message}${detail ? `: ${detail}` : ''}`);
}

export function getLanAddresses(): string[] {
  const interfaces = os.networkInterfaces();
  const addresses: string[] = [];
  for (const entries of Object.values(interfaces)) {
    if (!entries) continue;
    for (const entry of entries) {
      if (entry.family === 'IPv4' && !entry.internal) {
        addresses.push(entry.address);
      }
    }
  }
  return addresses;
}

function isLocalNetworkOrigin(origin: string, extraAllowed: string[]): boolean {
  if (extraAllowed.includes('*') || extraAllowed.includes(origin)) {
    return true;
  }
  try {
    const parsed = new URL(origin);
    const host = parsed.hostname;
    if (host === 'localhost' || host === '127.0.0.1' || host === '::1') {
      return true;
    }
    if (
      host.startsWith('192.168.') ||
      host.startsWith('10.') ||
      /^172\.(1[6-9]|2\d|3[01])\./.test(host) ||
      host.endsWith('.local')
    ) {
      return true;
    }
  } catch {
    return false;
  }
  return false;
}

function sendError(
  socket: WebSocket,
  code: ErrorMessage['code'],
  message: string,
  sessionId?: string
): void {
  if (socket.readyState !== 1) return;
  const payload: ErrorMessage = {
    type: 'error',
    code,
    message,
    ...(sessionId ? { sessionId } : {}),
  };
  socket.send(JSON.stringify(payload));
}

export function createSignalingServer(options: SignalingServerOptions = {}) {
  const port = options.port ?? Number(process.env.PORT || 3000);
  const host = options.host ?? process.env.HOST ?? '0.0.0.0';
  const extraAllowed =
    options.allowedOrigins ??
    (process.env.ALLOWED_ORIGINS ? process.env.ALLOWED_ORIGINS.split(',').map((s) => s.trim()) : []);
  const enableDiscovery = options.enableDiscovery ?? process.env.NODE_ENV !== 'test';
  const heartbeatIntervalMs = options.heartbeatIntervalMs ?? 15_000;
  const staleDeviceTimeoutMs = options.staleDeviceTimeoutMs ?? 45_000;

  const moduleDir = path.dirname(fileURLToPath(import.meta.url));
  const defaultReceiverDir = path.resolve(moduleDir, '../../receiver/dist');
  const receiverDistDir = options.receiverDistDir ?? defaultReceiverDir;
  const hasReceiverBundle = fs.existsSync(path.join(receiverDistDir, 'index.html'));

  const deviceManager = new DeviceManager();
  const sessionManager = new SessionManager();
  const startedAt = Date.now();

  let actualPort = port;
  let bonjour: Bonjour | null = null;
  let udpSocket: dgram.Socket | null = null;

  const httpServer = http.createServer((req, res) => {
    const requestOrigin = req.headers.origin;
    if (requestOrigin && isLocalNetworkOrigin(requestOrigin, extraAllowed)) {
      res.setHeader('Access-Control-Allow-Origin', requestOrigin);
    }

    const reqPath = (req.url || '/').split('?')[0];

    if (reqPath === '/health') {
      res.setHeader('Content-Type', 'application/json');
      res.writeHead(200);
      res.end(
        JSON.stringify({
          ok: true,
          service: 'droidcast-signaling',
          uptimeSec: Math.floor((Date.now() - startedAt) / 1000),
          devices: deviceManager.counts(),
          activeSessions: sessionManager.count(),
        })
      );
      return;
    }

    if (reqPath === '/info') {
      const addresses = getLanAddresses();
      const apk = buildApkMetadata(resolveApkArtifact(options.apkPath));
      res.setHeader('Content-Type', 'application/json');
      res.writeHead(200);
      res.end(
        JSON.stringify({
          service: 'droidcast-signaling',
          port: actualPort,
          addresses,
          wsUrls: addresses.map((ip) => `ws://${ip}:${actualPort}`),
          receiverUrls: addresses.map((ip) => `http://${ip}:${actualPort}`),
          apk,
        })
      );
      return;
    }

    if ((reqPath === '/install' || reqPath === '/install/') && !hasReceiverBundle) {
      const addresses = getLanAddresses();
      const apk = buildApkMetadata(resolveApkArtifact(options.apkPath));
      const wsUrls = addresses.length > 0
        ? addresses.map((ip) => `ws://${ip}:${actualPort}`)
        : [`ws://127.0.0.1:${actualPort}`];
      const html = renderInstallPageHtml({
        apk,
        wsUrls,
        hostLabel: os.hostname(),
      });
      res.setHeader('Content-Type', 'text/html; charset=utf-8');
      res.setHeader('Cache-Control', 'no-store');
      res.writeHead(200);
      res.end(html);
      return;
    }

    if (reqPath === '/download/droidcast.apk') {
      const apk = resolveApkArtifact(options.apkPath);
      if (!apk) {
        res.setHeader('Content-Type', 'application/json');
        res.writeHead(404);
        res.end(
          JSON.stringify({
            error: 'DroidCast APK not found on this host. Build app-debug.apk first.',
          })
        );
        return;
      }

      res.setHeader('Content-Type', 'application/vnd.android.package-archive');
      res.setHeader('Content-Disposition', `attachment; filename="${apk.filename}"`);
      res.setHeader('Content-Length', String(apk.sizeBytes));
      res.setHeader('Cache-Control', 'no-store');
      res.writeHead(200);

      if (req.method === 'HEAD') {
        res.end();
        return;
      }

      const stream = fs.createReadStream(apk.filePath);
      stream.on('error', (err) => {
        logError('Failed while streaming DroidCast APK', err);
        if (!res.headersSent) {
          res.writeHead(500);
        }
        res.end();
      });
      stream.pipe(res);
      return;
    }

    if (hasReceiverBundle) {
      const safeRelPath = path.normalize(reqPath === '/' ? 'index.html' : reqPath).replace(/^(\.\.[/\\])+/, '');
      let filePath = path.join(receiverDistDir, safeRelPath);
      if (!filePath.startsWith(receiverDistDir) || !fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
        filePath = path.join(receiverDistDir, 'index.html');
      }
      const ext = path.extname(filePath);
      res.setHeader('Content-Type', MIME_TYPES[ext] || 'application/octet-stream');
      res.writeHead(200);
      fs.createReadStream(filePath).pipe(res);
      return;
    }

    res.setHeader('Content-Type', 'application/json');
    res.writeHead(404);
    res.end(JSON.stringify({ error: 'Not found' }));
  });

  const wss = new WebSocketServer({
    server: httpServer,
    maxPayload: 256 * 1024,
    verifyClient: (info, callback) => {
      const origin = info.origin;
      // Native mobile clients (Android OkHttp) do not send an Origin header
      if (!origin || isLocalNetworkOrigin(origin, extraAllowed)) {
        callback(true);
      } else {
        logWarn(`Rejected WebSocket connection from disallowed origin: ${origin}`);
        callback(false, 403, 'Origin not allowed');
      }
    },
  });

  const socketAlive = new WeakMap<WebSocket, boolean>();

  function sendJson(socket: WebSocket, msg: SignalingMessage): void {
    if (socket.readyState === 1) {
      socket.send(JSON.stringify(msg));
    }
  }

  function cleanupSocketConnection(socket: WebSocket, reasonSuffix?: string): void {
    const removed = deviceManager.removeBySocket(socket);
    if (!removed) return;

    logInfo(
      `${removed.info.role === 'receiver' ? 'Receiver' : 'Sender'} disconnected (${removed.info.name})${reasonSuffix ? ` [${reasonSuffix}]` : ''}`
    );

    const sessions = sessionManager.removeByDevice(removed.info.deviceId);
    for (const session of sessions) {
      const peerId = sessionManager.getPeerId(session, removed.info.deviceId);
      if (peerId) {
        deviceManager.updateStatus(peerId, 'ready');
        deviceManager.sendTo(peerId, {
          type: 'peer_disconnected',
          sessionId: session.sessionId,
          deviceId: removed.info.deviceId,
          reason: reasonSuffix
            ? `${removed.info.name} disconnected (${reasonSuffix})`
            : `${removed.info.name} disconnected`,
        });
      }
      logInfo(`Session closed (${session.sessionId}) due to peer disconnect`);
    }

    const oppositeRole = removed.info.role === 'sender' ? 'receiver' : 'sender';
    deviceManager.broadcastToRole(oppositeRole, {
      type: 'device_discovered',
      action: 'left',
      device: removed.info,
    });
  }

  wss.on('connection', (socket, req) => {
    const remoteAddress = req.socket.remoteAddress?.replace(/^::ffff:/, '') ?? undefined;
    socketAlive.set(socket, true);

    socket.on('pong', () => {
      socketAlive.set(socket, true);
      deviceManager.touchHeartbeat(socket);
    });

    socket.on('message', (data) => {
      const raw = typeof data === 'string' ? data : data.toString('utf8');
      const parsed = parseSignalingMessage(raw);
      if (!parsed.ok) {
        sendError(socket, 'INVALID_MESSAGE', parsed.error);
        return;
      }

      const msg = parsed.message;
      socketAlive.set(socket, true);
      deviceManager.touchHeartbeat(socket);

      switch (msg.type) {
        case 'ping': {
          sendJson(socket, { type: 'pong', timestamp: msg.timestamp });
          return;
        }

        case 'pong': {
          return;
        }

        case 'device_register': {
          const { device } = deviceManager.register({
            socket,
            deviceId: msg.deviceId,
            role: msg.role,
            name: msg.name,
            platform: msg.platform,
            capabilities: msg.capabilities,
            address: remoteAddress,
          });

          logInfo(
            `${msg.role === 'receiver' ? 'Receiver' : 'Sender'} connected (${device.info.name}, id=${device.info.deviceId})`
          );

          const peers = deviceManager.listOppositeRole(msg.role);
          sendJson(socket, {
            type: 'device_registered',
            deviceId: device.info.deviceId,
            role: device.info.role,
            peers,
          });

          const oppositeRole = msg.role === 'sender' ? 'receiver' : 'sender';
          deviceManager.broadcastToRole(oppositeRole, {
            type: 'device_discovered',
            action: 'joined',
            device: device.info,
          });
          return;
        }

        case 'create_session': {
          const initiator = deviceManager.getBySocket(socket);
          if (!initiator) {
            sendError(socket, 'UNAUTHORIZED', 'Register device before creating a session.');
            return;
          }

          const target = deviceManager.getById(msg.targetDeviceId);
          if (!target || target.info.role === initiator.info.role) {
            sendError(socket, 'DEVICE_NOT_FOUND', 'Target device is unavailable or offline.');
            return;
          }

          const sender = initiator.info.role === 'sender' ? initiator : target;
          const receiver = initiator.info.role === 'receiver' ? initiator : target;

          const session = sessionManager.createSession({
            senderId: sender.info.deviceId,
            receiverId: receiver.info.deviceId,
            senderName: sender.info.name,
            receiverName: receiver.info.name,
            quality: msg.quality,
          });

          deviceManager.updateStatus(sender.info.deviceId, 'pairing');
          deviceManager.updateStatus(receiver.info.deviceId, 'pairing');

          logInfo(
            `Session created (${session.sessionId}) between ${sender.info.name} and ${receiver.info.name}`
          );

          // Send pairing code ONLY to the Android sender so the user confirms on laptop
          deviceManager.sendTo(sender.info.deviceId, {
            type: 'session_created',
            sessionId: session.sessionId,
            senderId: session.senderId,
            receiverId: session.receiverId,
            senderName: session.senderName,
            receiverName: session.receiverName,
            pairingCode: session.pairingCode,
            pairingExpiresAt: session.pairingExpiresAt,
            authorized: false,
            quality: session.quality,
          });

          // Send session metadata WITHOUT pairingCode to the receiver
          deviceManager.sendTo(receiver.info.deviceId, {
            type: 'session_created',
            sessionId: session.sessionId,
            senderId: session.senderId,
            receiverId: session.receiverId,
            senderName: session.senderName,
            receiverName: session.receiverName,
            pairingExpiresAt: session.pairingExpiresAt,
            authorized: false,
            quality: session.quality,
          });
          return;
        }

        case 'pairing_confirm': {
          const actor = deviceManager.getBySocket(socket);
          if (!actor) {
            sendError(socket, 'UNAUTHORIZED', 'Register device first.', msg.sessionId);
            return;
          }

          const verification = sessionManager.verifyPairing(
            msg.sessionId,
            actor.info.deviceId,
            msg.pairingCode
          );

          if (!verification.ok) {
            sendError(socket, verification.code, verification.message, msg.sessionId);
            return;
          }

          const { session } = verification;
          logInfo(`WebRTC negotiation authorized for session ${session.sessionId}`);

          deviceManager.sendTo(session.senderId, {
            type: 'pairing_verified',
            sessionId: session.sessionId,
            authorized: true,
            quality: session.quality,
          });
          deviceManager.sendTo(session.receiverId, {
            type: 'pairing_verified',
            sessionId: session.sessionId,
            authorized: true,
            quality: session.quality,
          });
          return;
        }

        case 'offer':
        case 'answer':
        case 'ice_candidate':
        case 'cast_started':
        case 'quality_change':
        case 'cast_stopped': {
          const actor = deviceManager.getBySocket(socket);
          if (!actor) {
            sendError(socket, 'UNAUTHORIZED', 'Device is not registered.', msg.sessionId);
            return;
          }

          const session = sessionManager.get(msg.sessionId);
          if (!session) {
            sendError(socket, 'SESSION_NOT_FOUND', 'Casting session not found.', msg.sessionId);
            return;
          }

          if (!sessionManager.isParticipant(session, actor.info.deviceId)) {
            sendError(socket, 'UNAUTHORIZED', 'Device is not part of this session.', msg.sessionId);
            return;
          }

          if (!session.authorized && msg.type !== 'cast_stopped') {
            sendError(
              socket,
              'UNAUTHORIZED',
              'Session pairing has not been verified yet.',
              msg.sessionId
            );
            return;
          }

          sessionManager.touch(session.sessionId);

          if (msg.type === 'offer') {
            logInfo(`WebRTC negotiation started (session=${session.sessionId})`);
          } else if (msg.type === 'cast_started') {
            deviceManager.updateStatus(session.senderId, 'casting');
            deviceManager.updateStatus(session.receiverId, 'casting');
          } else if (msg.type === 'quality_change') {
            sessionManager.updateQuality(session.sessionId, msg.quality);
          } else if (msg.type === 'cast_stopped') {
            deviceManager.updateStatus(session.senderId, 'ready');
            deviceManager.updateStatus(session.receiverId, 'ready');
            sessionManager.remove(session.sessionId);
            logInfo(`Session closed (${session.sessionId}): ${msg.reason}`);
          }

          const peerId = sessionManager.getPeerId(session, actor.info.deviceId);
          if (peerId) {
            deviceManager.sendTo(peerId, msg);
          }
          return;
        }
      }
    });

    socket.on('close', () => {
      cleanupSocketConnection(socket);
    });

    socket.on('error', (err) => {
      logError('WebSocket client error', err);
      cleanupSocketConnection(socket, 'socket error');
    });
  });

  const cleanupTimer = setInterval(() => {
    const expiredSessions = sessionManager.pruneExpired();
    for (const session of expiredSessions) {
      deviceManager.updateStatus(session.senderId, 'ready');
      deviceManager.updateStatus(session.receiverId, 'ready');
      deviceManager.sendTo(session.senderId, {
        type: 'error',
        code: 'SESSION_EXPIRED',
        message: 'Pairing timed out before confirmation.',
        sessionId: session.sessionId,
      });
      deviceManager.sendTo(session.receiverId, {
        type: 'error',
        code: 'SESSION_EXPIRED',
        message: 'Pairing timed out before confirmation.',
        sessionId: session.sessionId,
      });
      logInfo(`Session expired and cleaned up (${session.sessionId})`);
    }

    for (const client of wss.clients) {
      if (socketAlive.get(client) === false) {
        const device = deviceManager.getBySocket(client);
        logWarn(
          `Heartbeat missed; terminating stale connection (${device?.info.name ?? 'unregistered socket'})`
        );
        cleanupSocketConnection(client, 'heartbeat timeout');
        try {
          client.terminate();
        } catch {
          // Ignore terminate error
        }
        continue;
      }

      if (client.readyState === 1) {
        socketAlive.set(client, false);
        try {
          client.ping();
        } catch {
          cleanupSocketConnection(client, 'ping error');
          try {
            client.terminate();
          } catch {
            // Ignore terminate error
          }
        }
      }
    }

    const staleDevices = deviceManager.findStaleDevices(staleDeviceTimeoutMs);
    for (const stale of staleDevices) {
      logWarn(`Closing idle device connection (${stale.info.name})`);
      cleanupSocketConnection(stale.socket, 'idle timeout');
      try {
        stale.socket.terminate();
      } catch {
        // Ignore terminate error
      }
    }
  }, heartbeatIntervalMs);
  cleanupTimer.unref();

  async function start(): Promise<{ port: number; addresses: string[] }> {
    await new Promise<void>((resolve, reject) => {
      httpServer.once('error', reject);
      httpServer.listen(port, host, () => {
        httpServer.off('error', reject);
        const addr = httpServer.address();
        if (addr && typeof addr === 'object') {
          actualPort = addr.port;
        }
        resolve();
      });
    });

    const addresses = getLanAddresses();
    logInfo(`DroidCast signaling server listening on ws://${host}:${actualPort}`);
    for (const ip of addresses) {
      logInfo(`LAN WebSocket endpoint: ws://${ip}:${actualPort}`);
    }

    if (enableDiscovery) {
      try {
        bonjour = new Bonjour();
        bonjour.publish({
          name: `DroidCast-${os.hostname()}`,
          type: 'droidcast',
          protocol: 'tcp',
          port: actualPort,
          txt: {
            version: '1',
            wsPort: String(actualPort),
          },
        });
        logInfo(`mDNS service advertised as _droidcast._tcp on port ${actualPort}`);
      } catch (err) {
        logWarn(`mDNS advertisement unavailable: ${err instanceof Error ? err.message : String(err)}`);
      }

      try {
        udpSocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
        udpSocket.on('message', (msg, rinfo) => {
          if (msg.toString('utf8').trim() === DISCOVERY_MAGIC) {
            const response = JSON.stringify({
              service: 'droidcast-signaling',
              name: os.hostname(),
              port: actualPort,
              addresses: getLanAddresses(),
            });
            udpSocket?.send(response, rinfo.port, rinfo.address);
          }
        });
        udpSocket.on('error', (err) => {
          logWarn(`UDP discovery listener error: ${err.message}`);
        });
        udpSocket.bind(DISCOVERY_UDP_PORT, () => {
          logInfo(`UDP LAN discovery responder active on port ${DISCOVERY_UDP_PORT}`);
        });
      } catch (err) {
        logWarn(`UDP LAN discovery unavailable: ${err instanceof Error ? err.message : String(err)}`);
      }
    }

    return { port: actualPort, addresses };
  }

  async function stop(): Promise<void> {
    clearInterval(cleanupTimer);
    if (bonjour) {
      try {
        bonjour.unpublishAll();
        bonjour.destroy();
      } catch {
        // Ignore cleanup errors
      }
      bonjour = null;
    }
    if (udpSocket) {
      try {
        udpSocket.close();
      } catch {
        // Ignore cleanup errors
      }
      udpSocket = null;
    }
    for (const client of wss.clients) {
      try {
        client.close(1001, 'Server shutting down');
      } catch {
        // Ignore
      }
    }
    await new Promise<void>((resolve) => {
      wss.close(() => {
        httpServer.close(() => resolve());
      });
    });
    logInfo('DroidCast signaling server stopped');
  }

  return {
    start,
    stop,
    getPort: () => actualPort,
    deviceManager,
    sessionManager,
  };
}

const isEntrypoint =
  process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];

if (isEntrypoint) {
  const server = createSignalingServer();
  server.start().catch((err) => {
    logError('Failed to start signaling server', err);
    process.exit(1);
  });

  const shutdown = () => {
    server.stop().finally(() => process.exit(0));
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}
