// 局域网房间（#148）：本机服务在同一个 Wi-Fi 里开多人讨论房间，不需要部署 Cloudflare Worker。
// 房间的规则和 Worker 用的是同一个 RoomCore（worker/src/room.ts），这里只换掉它的“外壳”：
// 存储放在内存里（服务停了房间就没了），闹钟用 setTimeout，连接用 server/wsserver.ts，AI 用房主在本机服务里连好的 API。
// 同学凭邀请码进房，拿不到房主的 API Key（Key 只在房主的本机服务内存里）。
import os from 'node:os';
import type http from 'node:http';
import type { Duplex } from 'node:stream';
import {
  RoomCore,
  randomToken,
  type Generate,
  type SocketLike,
  type StoredRoom,
} from '../worker/src/room.ts';
import { ROOM_ID, isPrivateIPv4 } from '../shared/room.ts';
import { ApiError } from './ai.ts';
// RoomCore 认的是 Worker 那份副本里的 ApiError：本机的错误要换成它，房间才会把原因告诉大家
import { ApiError as RoomError } from '../worker/src/vendor/server/ai.ts';
import { acceptWebSocket, rejectUpgrade, type ServerSocket } from './wsserver.ts';

export const LAN_PORT = 4174;
const MAX_ROOMS = 3; // 一台电脑同时开的房间数

/** 这台电脑在局域网里的地址（第一个私有 IPv4），找不到就是 null。 */
export function lanAddress(nets = os.networkInterfaces()): string | null {
  for (const list of Object.values(nets))
    for (const n of list ?? [])
      if (n.family === 'IPv4' && !n.internal && isPrivateIPv4(n.address)) return n.address;
  return null;
}

interface LanRoom {
  core: RoomCore;
  stored: StoredRoom | undefined;
  sockets: Set<{ ws: ServerSocket; member: string | null }>;
  timer: ReturnType<typeof setTimeout> | null;
}

export class LanRooms {
  private rooms = new Map<string, LanRoom>();
  private now: () => number;
  constructor(now: () => number = () => Date.now()) {
    this.now = now;
  }

  get count() {
    return this.rooms.size;
  }

  /** 房主在本机页面上开一个房间；generate 用房主在本机服务里连好的 API。 */
  async create(input: { setup?: unknown; hostName?: unknown }, generate: Generate) {
    if (this.rooms.size >= MAX_ROOMS)
      throw new ApiError(`同时最多开 ${MAX_ROOMS} 个局域网房间，先结束一个再开。`, 429);
    const roomId = randomToken();
    const room: LanRoom = {
      core: null as unknown as RoomCore,
      stored: undefined,
      sockets: new Set(),
      timer: null,
    };
    const socketsOf = (): SocketLike[] =>
      [...room.sockets].map(s => ({
        send: d => s.ws.send(d),
        close: () => s.ws.close(),
        memberId: () => s.member,
        setMember: id => {
          s.member = id;
        },
      }));
    room.core = new RoomCore({
      load: () => Promise.resolve(room.stored),
      save: r => {
        room.stored = structuredClone(r);
        return Promise.resolve();
      },
      destroy: () => {
        if (room.timer) clearTimeout(room.timer);
        room.stored = undefined;
        this.rooms.delete(roomId);
        return Promise.resolve();
      },
      sockets: socketsOf,
      setAlarm: at => {
        if (room.timer) clearTimeout(room.timer);
        // setTimeout 最多等 24.8 天；房间最多活 4 小时，远远够用
        room.timer = setTimeout(() => void room.core.alarm(), Math.max(0, at - this.now()));
        return Promise.resolve();
      },
      generate: async (purpose, payload, onText) => {
        try {
          return await generate(purpose, payload, onText);
        } catch (e) {
          throw e instanceof ApiError ? new RoomError(e.message, e.status) : e;
        }
      },
      now: this.now,
    });
    this.rooms.set(roomId, room);
    try {
      return await room.core.init({ ...input, roomId });
    } catch (e) {
      this.rooms.delete(roomId);
      throw e instanceof RoomError ? new ApiError(e.message, e.status) : e;
    }
  }

  /** 同学（和房主）连进房间：WebSocket 地址是 /api/rooms/<id>/ws，凭邀请码或成员凭据在房间里验证。 */
  upgrade(req: http.IncomingMessage, socket: Duplex, roomId: string) {
    const room = ROOM_ID.test(roomId) ? this.rooms.get(roomId) : undefined;
    if (!room) return rejectUpgrade(socket, 404, 'Not Found');
    const ws = acceptWebSocket(req, socket);
    if (!ws) return;
    const entry = { ws, member: null as string | null };
    room.sockets.add(entry);
    const me: SocketLike = {
      send: d => ws.send(d),
      close: () => ws.close(),
      memberId: () => entry.member,
      setMember: id => {
        entry.member = id;
      },
    };
    ws.onMessage(text => void room.core.onMessage(me, text));
    ws.onClose(() => {
      room.sockets.delete(entry);
      void room.core.onClose(me);
    });
    void room.core.onOpen(me);
  }

  /** 服务停下时：关掉所有连接和闹钟。 */
  closeAll() {
    for (const r of this.rooms.values()) {
      if (r.timer) clearTimeout(r.timer);
      for (const s of r.sockets) s.ws.close();
    }
    this.rooms.clear();
  }
}
