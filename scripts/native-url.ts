// 原生版的 JS 沙箱（androidx.javascriptengine）只有语言本身，没有网页的 URL。读链接（#157）的地址检查要用到它：
// 这里补一个只认绝对地址的最小实现（协议、用户名密码、主机、端口、路径、查询）。不做 IDN 和百分号编码的规范化，
// 真正发请求前 Kotlin 用 OkHttp 的 HttpUrl 再解析一遍，连接时还要检查解析出的 IP，所以这里只需要认得出各个部分。
class MiniURL {
  protocol: string;
  username: string;
  password: string;
  hostname: string;
  port: string;
  pathname: string;
  search: string;
  hash: string;
  constructor(raw: string) {
    const m = String(raw)
      .trim()
      .match(/^([a-zA-Z][a-zA-Z0-9+.-]*):(\/\/([^/?#]*))?([^?#]*)(\?[^#]*)?(#.*)?$/);
    if (!m) throw new TypeError('Invalid URL');
    this.protocol = m[1].toLowerCase() + ':';
    let auth = m[3] ?? '';
    this.username = '';
    this.password = '';
    const at = auth.lastIndexOf('@');
    if (at >= 0) {
      const [u, p = ''] = auth.slice(0, at).split(':');
      this.username = u;
      this.password = p;
      auth = auth.slice(at + 1);
    }
    const host = auth.match(/^(\[[^\]]*\]|[^:]*)(?::(\d*))?$/);
    if (!host) throw new TypeError('Invalid URL');
    this.hostname = host[1].toLowerCase();
    const defaults: Record<string, string> = { 'http:': '80', 'https:': '443' };
    this.port = host[2] && host[2] !== defaults[this.protocol] ? host[2] : '';
    if (
      m[2] !== undefined &&
      !this.hostname &&
      (this.protocol === 'http:' || this.protocol === 'https:')
    )
      throw new TypeError('Invalid URL');
    this.pathname = m[4] || (m[2] !== undefined ? '/' : '');
    this.search = m[5] && m[5] !== '?' ? m[5] : '';
    this.hash = m[6] && m[6] !== '#' ? m[6] : '';
  }
  toString() {
    const auth = this.username
      ? this.username + (this.password ? ':' + this.password : '') + '@'
      : '';
    const host = this.hostname + (this.port ? ':' + this.port : '');
    return `${this.protocol}//${auth}${host}${this.pathname}${this.search}${this.hash}`;
  }
}

const g = globalThis as { URL?: unknown };
if (typeof g.URL === 'undefined') g.URL = MiniURL;
