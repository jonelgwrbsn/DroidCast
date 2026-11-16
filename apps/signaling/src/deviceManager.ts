import type { WebSocket } from 'ws';
import type {
  DeviceCapabilities,
  DeviceInfo,
  DevicePlatform,
  DeviceRole,
  DeviceStatus,
  SignalingMessage,
} from './protocol.js';

export interface ConnectedDevice {
  info: DeviceInfo;
  socket: WebSocket;
  lastHeartbeatAt: number;
}

export class DeviceManager {
  private readonly devicesById = new Map<string, ConnectedDevice>();
  private readonly deviceIdBySocket = new Map<WebSocket, string>();

  register(params: {
    socket: WebSocket;
    deviceId: string;
    role: DeviceRole;
    name: string;
    platform: DevicePlatform;
    capabilities: DeviceCapabilities;
    address?: string;
  }): { device: ConnectedDevice; replacedExisting: boolean } {
    const existing = this.devicesById.get(params.deviceId);
    let replacedExisting = false;
    if (existing && existing.socket !== params.socket) {
      this.deviceIdBySocket.delete(existing.socket);
      try {
        existing.socket.close(4000, 'Replaced by new connection');
      } catch {
        // Ignore socket close error during replacement
      }
      replacedExisting = true;
    }

    const now = Date.now();
    const info: DeviceInfo = {
      deviceId: params.deviceId,
      role: params.role,
      name: params.name.trim(),
      platform: params.platform,
      status: 'ready',
      address: params.address,
      capabilities: params.capabilities,
      connectedAt: existing?.info.connectedAt ?? now,
    };

    const record: ConnectedDevice = {
      info,
      socket: params.socket,
      lastHeartbeatAt: now,
    };

    this.devicesById.set(params.deviceId, record);
    this.deviceIdBySocket.set(params.socket, params.deviceId);
    return { device: record, replacedExisting };
  }

  getById(deviceId: string): ConnectedDevice | undefined {
    return this.devicesById.get(deviceId);
  }

  getBySocket(socket: WebSocket): ConnectedDevice | undefined {
    const deviceId = this.deviceIdBySocket.get(socket);
    return deviceId ? this.devicesById.get(deviceId) : undefined;
  }

  touchHeartbeat(socket: WebSocket): void {
    const device = this.getBySocket(socket);
    if (device) {
      device.lastHeartbeatAt = Date.now();
    }
  }

  updateStatus(deviceId: string, status: DeviceStatus): DeviceInfo | undefined {
    const device = this.devicesById.get(deviceId);
    if (!device) return undefined;
    device.info = { ...device.info, status };
    return device.info;
  }

  listByRole(role: DeviceRole): DeviceInfo[] {
    const result: DeviceInfo[] = [];
    for (const device of this.devicesById.values()) {
      if (device.info.role === role) {
        result.push(device.info);
      }
    }
    return result;
  }

  listOppositeRole(role: DeviceRole): DeviceInfo[] {
    return this.listByRole(role === 'sender' ? 'receiver' : 'sender');
  }

  removeBySocket(socket: WebSocket): ConnectedDevice | undefined {
    const deviceId = this.deviceIdBySocket.get(socket);
    if (!deviceId) return undefined;
    this.deviceIdBySocket.delete(socket);
    const device = this.devicesById.get(deviceId);
    if (device && device.socket === socket) {
      this.devicesById.delete(deviceId);
      return device;
    }
    return undefined;
  }

  findStaleDevices(maxIdleMs: number, now = Date.now()): ConnectedDevice[] {
    const stale: ConnectedDevice[] = [];
    for (const device of this.devicesById.values()) {
      if (now - device.lastHeartbeatAt > maxIdleMs) {
        stale.push(device);
      }
    }
    return stale;
  }

  sendTo(deviceId: string, message: SignalingMessage): boolean {
    const device = this.devicesById.get(deviceId);
    if (!device || device.socket.readyState !== 1) {
      return false;
    }
    device.socket.send(JSON.stringify(message));
    return true;
  }

  broadcastToRole(role: DeviceRole, message: SignalingMessage): void {
    const payload = JSON.stringify(message);
    for (const device of this.devicesById.values()) {
      if (device.info.role === role && device.socket.readyState === 1) {
        device.socket.send(payload);
      }
    }
  }

  counts(): { senders: number; receivers: number } {
    let senders = 0;
    let receivers = 0;
    for (const device of this.devicesById.values()) {
      if (device.info.role === 'sender') senders += 1;
      else receivers += 1;
    }
    return { senders, receivers };
  }
}
