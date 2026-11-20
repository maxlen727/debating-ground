// 读材料（#157）：本机服务替网页版取链接的正文。规则和提取在 shared/pages.ts，这里只做网络请求。
// 本机服务跑在学习者自己的电脑上，能连到局域网：除了检查链接本身，连接时还要检查域名解析出来的每个地址
// （在 lookup 里查，查过的地址就是真正连上的地址，防 DNS 重绑定），跳转的每一跳都重新检查。
import dns from 'node:dns';
import http from 'node:http';
import https from 'node:https';
import type { LookupFunction } from 'node:net';
import {
  FETCH_HEADERS,
  PAGE_LIMITS,
  checkTarget,
  decodeBody,
  isBlockedIP,
  nextHop,
  type Fetched,
  type Get,
} from '../shared/pages.ts';

const BLOCKED_ADDRESS = '不能读取本机或局域网里的地址。';

// 解析出的地址里只要有一个是本机、局域网或保留地址就不连
export const guardedLookup: LookupFunction = (hostname, options, callback) => {
  dns.lookup(hostname, { all: true, family: options.family }, (err, addresses) => {
    if (err) return callback(err, '', 0);
    if (!addresses.length || addresses.some(a => isBlockedIP(a.address)))
      return callback(Object.assign(new Error(BLOCKED_ADDRESS), { code: 'EBLOCKED' }), '', 0);
    if (options.all)
      return (callback as (e: null, a: dns.LookupAddress[]) => void)(null, addresses);
    callback(null, addresses[0].address, addresses[0].family);
  });
};

interface Hop {
  status: number;
  location: string;
  contentType: string;
  bytes: Uint8Array;
}

function once(url: string, lookup: LookupFunction, signal: AbortSignal): Promise<Hop> {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const lib = u.protocol === 'https:' ? https : http;
    const req = lib.get(u, { headers: FETCH_HEADERS, lookup, signal, agent: false }, res => {
      const status = res.statusCode ?? 0;
      const location = String(res.headers.location ?? '');
      const contentType = String(res.headers['content-type'] ?? '');
      if (status >= 300 && status < 400 && location) {
        res.resume();
        return resolve({ status, location, contentType, bytes: new Uint8Array() });
      }
      const chunks: Buffer[] = [];
      let size = 0;
      res.on('data', (c: Buffer) => {
        size += c.length;
        if (size > PAGE_LIMITS.bytes) {
          req.destroy();
          reject(new Error('这个页面太大了（超过 3 MB），没法读取。可以把正文复制过来贴上。'));
          return;
        }
        chunks.push(c);
      });
      res.on('end', () => resolve({ status, location, contentType, bytes: Buffer.concat(chunks) }));
      res.on('error', reject);
    });
    req.on('error', reject);
  });
}

function friendly(e: unknown): Error {
  const err = e as { code?: string; name?: string; message?: string };
  if (err.code === 'EBLOCKED') return new Error(BLOCKED_ADDRESS);
  if (err.name === 'AbortError' || err.code === 'ABORT_ERR')
    return new Error('网站太久没有回应，没能读取。可以稍后再试，或者把正文复制过来贴上。');
  if (err.code === 'ENOTFOUND' || err.code === 'EAI_AGAIN')
    return new Error('找不到这个网站，请检查链接是不是写对了。');
  if (err.message?.includes('3 MB')) return err as Error;
  return new Error('没能连上这个网站。可以稍后再试，或者把正文复制过来贴上。');
}

/** 取一个地址：自己跟着跳转走（最多 4 次，每一跳都检查），有大小和时间上限。 */
export function nodeGet({
  timeoutMs = PAGE_LIMITS.timeoutMs,
  lookup = guardedLookup,
}: { timeoutMs?: number; lookup?: LookupFunction } = {}): Get {
  return async (start: string): Promise<Fetched> => {
    const bad = checkTarget(start);
    if (bad) throw new Error(bad);
    const signal = AbortSignal.timeout(timeoutMs);
    let url = start;
    for (let hop = 0; ; hop++) {
      let r: Hop;
      try {
        r = await once(url, lookup, signal);
      } catch (e) {
        throw friendly(e);
      }
      if (r.status >= 300 && r.status < 400 && r.location) {
        if (hop >= PAGE_LIMITS.redirects) throw new Error('这个链接跳转了太多次，没能读取。');
        url = nextHop(url, r.location);
        continue;
      }
      return {
        status: r.status,
        url,
        contentType: r.contentType,
        body: decodeBody(r.bytes, r.contentType),
      };
    }
  };
}
