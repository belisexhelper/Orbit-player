import type { WebSocket } from 'ws';

/** Drži otvorene WebSocket veze uređaja i šalje im poruke u realnom vremenu. */
export class DeviceHub {
  private sockets = new Map<string, Set<WebSocket>>();

  add(deviceId: string, ws: WebSocket) {
    let set = this.sockets.get(deviceId);
    if (!set) {
      set = new Set();
      this.sockets.set(deviceId, set);
    }
    set.add(ws);
  }

  remove(deviceId: string, ws: WebSocket) {
    const set = this.sockets.get(deviceId);
    if (!set) return;
    set.delete(ws);
    if (set.size === 0) this.sockets.delete(deviceId);
  }

  isOnline(deviceId: string): boolean {
    return (this.sockets.get(deviceId)?.size ?? 0) > 0;
  }

  onlineIds(): string[] {
    return [...this.sockets.keys()];
  }

  /** Vraća true ako je poruka poslata bar jednoj otvorenoj vezi. */
  send(deviceId: string, message: unknown): boolean {
    const set = this.sockets.get(deviceId);
    if (!set) return false;
    const data = JSON.stringify(message);
    let sent = false;
    for (const ws of set) {
      if (ws.readyState === 1) {
        ws.send(data);
        sent = true;
      }
    }
    return sent;
  }

  closeAll() {
    for (const set of this.sockets.values()) for (const ws of set) ws.close(1001, 'server shutdown');
    this.sockets.clear();
  }

  disconnect(deviceId: string) {
    const set = this.sockets.get(deviceId);
    if (!set) return;
    for (const ws of set) ws.close(4001, 'disconnected');
    this.sockets.delete(deviceId);
  }
}
