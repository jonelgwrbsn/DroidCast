import {
  parseSignalingMessage,
  type DeviceCapabilities,
  type SignalingMessage,
} from './protocol';

export type SignalingConnectionState =
  | 'disconnected'
  | 'connecting'
  | 'connected'
  | 'reconnecting';

export interface SignalingClientCallbacks {
  onStateChange: (state: SignalingConnectionState) => void;
  onMessage: (message: SignalingMessage) => void;
  onLog: (level: 'INFO' | 'WARN' | 'ERROR', scope: string, message: string) => void;
}

export class SignalingClient {
  private ws: WebSocket | null = null;
  private url: string;
  private deviceId: string;
  private deviceName: string;
  private callbacks: SignalingClientCallbacks;
  private reconnectAttempt = 0;
  private reconnectTimer: number | null = null;
  private pingTimer: number | null = null;
  private manuallyClosed = false;
  private hadConnectedBefore = false;

  constructor(params: {
    url: string;
    deviceId: string;
    deviceName: string;
    callbacks: SignalingClientCallbacks;
  }) {
    this.url = params.url;
    this.deviceId = params.deviceId;
    this.deviceName = params.deviceName;
    this.callbacks = params.callbacks;
  }

  updateConfig(url: string, deviceName: string): void {
    const changed = this.url !== url || this.deviceName !== deviceName;
    this.url = url;
    this.deviceName = deviceName;
    if (changed && !this.manuallyClosed) {
      this.reconnectNow();
    }
  }

  connect(): void {
    this.manuallyClosed = false;
    this.clearTimers();
    this.openSocket();
  }

  reconnectNow(): void {
    this.manuallyClosed = false;
    this.reconnectAttempt = 0;
    this.clearTimers();
    if (this.ws) {
      try {
        this.ws.close(1000, 'Reconnecting');
      } catch {
        // Ignore
      }
      this.ws = null;
    }
    this.openSocket();
  }

  disconnect(): void {
    this.manuallyClosed = true;
    this.clearTimers();
    if (this.ws) {
      try {
        this.ws.close(1000, 'Receiver closed');
      } catch {
        // Ignore
      }
      this.ws = null;
    }
    this.callbacks.onStateChange('disconnected');
  }

  send(message: SignalingMessage): boolean {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      this.callbacks.onLog(
        'WARN',
        'Signaling',
        `Dropped outbound message (${message.type}) because WebSocket is not open`
      );
      return false;
    }
    this.ws.send(JSON.stringify(message));
    return true;
  }

  private openSocket(): void {
    this.callbacks.onStateChange(this.hadConnectedBefore ? 'reconnecting' : 'connecting');
    this.callbacks.onLog('INFO', 'Signaling', `Connecting to ${this.url}`);

    let socket: WebSocket;
    try {
      socket = new WebSocket(this.url);
    } catch (err) {
      this.callbacks.onLog(
        'ERROR',
        'Signaling',
        `Invalid signaling URL (${this.url}): ${err instanceof Error ? err.message : String(err)}`
      );
      this.scheduleReconnect();
      return;
    }

    this.ws = socket;

    socket.onopen = () => {
      if (this.ws !== socket) return;
      this.reconnectAttempt = 0;
      this.hadConnectedBefore = true;
      this.callbacks.onStateChange('connected');
      this.callbacks.onLog('INFO', 'Signaling', `Connected to signaling server at ${this.url}`);

      const capabilities: DeviceCapabilities = {
        video: true,
        audio: true,
        maxWidth: window.screen?.width ? Math.max(window.screen.width, 1920) : 1920,
        maxHeight: window.screen?.height ? Math.max(window.screen.height, 1080) : 1080,
        maxFps: 60,
      };

      this.send({
        type: 'device_register',
        deviceId: this.deviceId,
        role: 'receiver',
        name: this.deviceName,
        platform: 'web',
        capabilities,
      });

      this.pingTimer = window.setInterval(() => {
        this.send({ type: 'ping', timestamp: Date.now() });
      }, 15_000);
    };

    socket.onmessage = (event) => {
      if (this.ws !== socket) return;
      const raw = typeof event.data === 'string' ? event.data : '';
      const parsed = parseSignalingMessage(raw);
      if (!parsed.ok) {
        this.callbacks.onLog('WARN', 'Signaling', `Ignored invalid message: ${parsed.error}`);
        return;
      }
      this.callbacks.onMessage(parsed.message);
    };

    socket.onerror = () => {
      if (this.ws !== socket) return;
      this.callbacks.onLog('ERROR', 'Signaling', `WebSocket error on ${this.url}`);
    };

    socket.onclose = (event) => {
      if (this.ws !== socket) return;
      this.ws = null;
      this.clearPing();
      if (this.manuallyClosed) {
        this.callbacks.onStateChange('disconnected');
        return;
      }
      this.callbacks.onLog(
        'WARN',
        'Signaling',
        `Connection closed (code=${event.code}${event.reason ? `, reason=${event.reason}` : ''})`
      );
      this.scheduleReconnect();
    };
  }

  private scheduleReconnect(): void {
    if (this.manuallyClosed) return;
    this.callbacks.onStateChange('reconnecting');
    const delayMs = Math.min(1000 * Math.pow(1.6, this.reconnectAttempt), 10_000);
    this.reconnectAttempt += 1;
    this.reconnectTimer = window.setTimeout(() => {
      this.openSocket();
    }, delayMs);
  }

  private clearPing(): void {
    if (this.pingTimer !== null) {
      window.clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }

  private clearTimers(): void {
    this.clearPing();
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
}
