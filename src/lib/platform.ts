// 运行在哪：浏览器，还是安卓 App（Capacitor）。
// App 里没有本机服务（只能用演示模式或自己部署的后端），也不能下载文件；分享出去的邀请链接要指向在线版网页。
// 这里只读 Capacitor 注入的全局对象，不引入 @capacitor/core，测试（Node）里也能直接用。
type CapacitorGlobal = { isNativePlatform?: () => boolean };
export const isNativeApp = () => !!(globalThis as { Capacitor?: CapacitorGlobal }).Capacitor?.isNativePlatform?.();

// 在线版网页的地址：App 里生成的邀请链接用它，同学用浏览器打开就能加入
const env = (import.meta as { env?: Record<string, string | undefined> }).env;
export const PUBLIC_APP_URL = env?.VITE_PUBLIC_APP_URL || 'https://maxlen727.github.io/debating-ground/app/';
