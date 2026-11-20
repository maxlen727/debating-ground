// 全局状态：一个可订阅的对象，持久化到 localStorage。
// update(fn) 在副本上修改再整体替换，组件用 useApp() 订阅。
import { useSyncExternalStore } from 'react';
import type {
  Entry,
  ModeId,
  Persona,
  Previous,
  Reflection,
  Review,
  Seat,
  SeatConfig,
  Source,
  Stage,
  Team,
  TermCard,
  Turn,
  ExplainKind,
  MaterialExcerpt,
  ReadStep,
} from '../shared/types.ts';
import type { PointProposal, RoomMember, RoomStatus, Speaker } from '../shared/room.ts';
import { DEFAULT_VOICE } from '../shared/speech.ts';
import { holdingRenders, transition, type View } from './lib/motion.ts';
import {
  restoreCards,
  restoreConfig,
  restoreNotes,
  restorePlan,
  restoreSession,
} from './lib/restore.ts';
import { notesFromCards, type Note } from './lib/notes.ts';
import type { Material, MaterialMeta } from './lib/materials.ts';

export type Screen =
  | 'learn'
  | 'prepare'
  | 'session'
  | 'review'
  | 'library'
  | 'card'
  | 'settings'
  | 'join'
  | 'material';

export interface Config {
  concept: string;
  mode: ModeId;
  // demo：演示模板；api：本机服务；remote：自己部署的后端（Cloudflare Worker）
  aiMode: 'demo' | 'api' | 'remote';
  // 自己部署的后端：地址和访问口令保存在这台设备的浏览器里（不是 AI 服务商的密钥）
  remote: { url: string; password: string };
  name: string; // 多人讨论里显示的名字
  counts: Record<Team, number>;
  seats: Record<Team, SeatConfig[]>;
  stages: Stage[];
  turnSeconds: 0 | 30 | 60 | 90 | 120; // 多人房间里每次发言的提示时限，0 表示不计时
  voiceName: string; // 讯飞发音人 ID
}
export interface Plan {
  mode: ModeId;
  concept: string;
  topic: string;
  keyPoints: string[];
  opening: string;
  personas: Persona[];
  source: Source;
  model?: string;
  previous: Previous | null;
  context?: string; // 学习者交代的背景（具体事件类的话题）
  debaters?: Record<Team, string[]>; // 辩论：按论题生成的辩手背景，增加席位时从这里取
  rolesFor?: string; // 角色是按哪道题设定的；学习者改了题目后用来提示“角色还是旧的”
  material?: SessionMaterial; // 从材料里的一个技术点开始（#144）
}
// 讨论带着的原文节选，加上材料的 id（完整材料在 lib/materials.ts，只存在这台设备上）
export type SessionMaterial = MaterialExcerpt & { id: string };
export interface DebateSeat extends Seat {
  name: string;
}
// 多人讨论房间。房间状态以后端为准，这里是最近一次收到的副本。
// token 是这位成员在这个房间里的凭据（房间结束后失效），不是 AI 服务商的密钥，也不会进入导出文件或知识库。
export interface RoomLink {
  backend: string;
  roomId: string;
  token: string;
  invite: string; // invite 只有房主保存，用来再次分享
  memberId: string;
  host: boolean;
  status: RoomStatus;
  members: RoomMember[];
  speaker: Speaker;
  aiBusy: string | null;
  error: string;
  expiresAt: number;
  turnSeconds: number;
  turnDeadline: number;
  voiceAvailable?: boolean;
  listenAvailable?: boolean;
  aiPaused?: boolean; // turnDeadline 已换算成这台设备的时钟，0 表示不计时
  connected: boolean;
  closed: string; // closed：房间不可用的原因，不再重连
  reflections: (Reflection & { after: string })[]; // 小结只留在自己的设备上
}
export interface Session {
  id: string;
  created: number;
  mode: ModeId;
  concept: string;
  topic: string;
  keyPoints: string[];
  covered: number[];
  entries: Entry[];
  learnerTurns: number;
  nextRole: number;
  demoStep: number;
  finished: boolean;
  draft: string;
  previous: Previous | null;
  context?: string; // 学习者交代的背景，随讨论带给每个提示词
  awaitingReview?: boolean; // 辩论赛程走完、还没进入复盘：先让学习者读完最后的总结陈词
  proposedPoints?: PointProposal[]; // 待确认的新要点；多人房间里以后端为准
  personas: Persona[];
  seats: DebateSeat[];
  stages: Stage[];
  turns: Turn[];
  cursor: number;
  review: (Review & { source: Source; model?: string }) | null;
  room?: RoomLink;
  glossary?: TermCard[]; // 这次讨论里点开看过的术语
  branches?: Branch[]; // 知识画布：从某句发言展开的分支
  at?: string; // 知识画布：正在看的分支（没有就是主干）
  material?: SessionMaterial; // 从材料开始的讨论：这个技术点的原文节选（#144）
}
// 知识画布的一个分支：从 from 这条发言展开（它在 parent 那一层），gist 是学习者带回上一层的一句话
export interface Branch {
  id: string;
  parent?: string;
  from: string;
  topic: string;
  gist?: string;
  created: number;
}
export interface AiStatus {
  available: boolean;
  configured: boolean;
  verified: boolean;
  baseURL: string;
  model: string; // 本机服务
  remoteVerified: boolean;
  remoteModel: string; // 自己部署的后端
  ttsConfigured?: boolean;
  remoteTtsConfigured?: boolean; // 讯飞朗读：本机服务 / 自己部署的后端
  sttConfigured?: boolean;
  remoteSttConfigured?: boolean; // 讯飞语音输入
}
export interface Ui {
  screen: Screen;
  busy: string | null; // 正在进行的 AI 请求说明
  error: string;
  toast: string;
  hint: string | null;
  explain: { kind: ExplainKind; label: string; quote: string; text: string } | null;
  sheet: string | null; // 打开操作面板的消息 id
  openCard: string | null;
  join: { backend: string; roomId: string; invite: string } | null; // 打开邀请链接后，等待填写名字
  invite: boolean; // 显示邀请链接面板
  term: (TermCard & { messageId: string }) | null; // 打开的术语卡片；definition 为空表示正在查
  context: { ask: string; text: string } | null; // 准备时 AI 请学习者交代背景
  // 读材料（#144）：正在看的材料（完整内容在 IndexedDB，这里是读进内存的一份）、研读助手每一步在做什么、最近的材料。不存档
  material: Material | null;
  reading: ReadStep[] | null;
  materials: MaterialMeta[];
}
export interface AppState {
  config: Config;
  plan: Plan | null;
  session: Session | null;
  library: Note[]; // 概念笔记：一个概念一篇，最近更新的在前（#140）
  ai: AiStatus;
  ui: Ui;
}

// v4：知识库从“一次讨论一张卡片”改成“一个概念一篇笔记”（#140）。旧存档读进来时合并成笔记，旧的键不删
const STORE = 'sibian-v4';

export const defaultConfig = (): Config => ({
  concept: '机会成本',
  mode: 'socratic',
  aiMode: 'demo',
  remote: { url: '', password: '' },
  name: '',
  counts: { A: 2, B: 2 },
  seats: {
    A: [
      { type: 'human', bg: '' },
      { type: 'ai', bg: '经济学研究者，习惯用数据和模型说话。' },
    ],
    B: [
      { type: 'ai', bg: '中学老师，关注概念在日常生活里怎么被误用。' },
      { type: 'ai', bg: '创业者，从真实的经营取舍出发。' },
    ],
  },
  stages: [
    { name: '开篇立论', kind: 'opening', rounds: 1 },
    { name: '交叉质询', kind: 'cross', rounds: 1 },
    { name: '自由辩论', kind: 'free', rounds: 2 },
    { name: '总结陈词', kind: 'closing', rounds: 1 },
  ],
  turnSeconds: 0,
  voiceName: DEFAULT_VOICE,
});
const defaultUi = (): Ui => ({
  screen: 'learn',
  busy: null,
  error: '',
  toast: '',
  hint: null,
  explain: null,
  sheet: null,
  openCard: null,
  join: null,
  invite: false,
  term: null,
  context: null,
  material: null,
  reading: null,
  materials: [],
});

const isObj = (v: unknown): v is Record<string, unknown> =>
  !!v && typeof v === 'object' && !Array.isArray(v);

function load(): AppState {
  const base: AppState = {
    config: defaultConfig(),
    plan: null,
    session: null,
    library: [],
    ai: {
      available: false,
      configured: false,
      verified: false,
      baseURL: '',
      model: '',
      remoteVerified: false,
      remoteModel: '',
      ttsConfigured: false,
      remoteTtsConfigured: false,
    },
    ui: defaultUi(),
  };
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(STORE) ?? 'null');
    const v3: unknown = isObj(raw) ? null : JSON.parse(localStorage.getItem('sibian-v3') ?? 'null');
    const saved = isObj(raw) ? raw : isObj(v3) ? v3 : null;
    // v3 及以前的知识库是一张张卡片：按概念合并成笔记
    const library = (lib: unknown) =>
      isObj(raw) ? restoreNotes(lib) : notesFromCards(restoreCards(lib));
    // 学习页不再提供的讨论方式（反向讲解改成从笔记里开始，知识画布并进了追问和圆桌，#142）：
    // 选着它们的设置改成苏格拉底追问；旧的反向讲解计划作废；进行中的讨论和知识库照旧
    if (
      saved &&
      isObj(saved.config) &&
      (saved.config.mode === 'teach' || saved.config.mode === 'canvas')
    )
      saved.config.mode = 'socratic';
    if (saved && isObj(saved.plan) && saved.plan.mode === 'teach') saved.plan = null;
    if (saved) {
      // 存档可能缺字段或写坏了：先按结构检查，坏掉的那一块作废，不让界面一打开就崩溃
      const session = restoreSession(saved.session);
      return {
        ...base,
        config: restoreConfig(saved.config, base.config),
        plan: restorePlan(saved.plan),
        session,
        library: library(saved.library),
        ui: {
          ...base.ui,
          screen: saved.screen === 'session' && session && !session.finished ? 'session' : 'learn',
        },
      };
    }
    // v0.4 的知识卡片结构相同，迁移过来
    const v2: unknown = JSON.parse(localStorage.getItem('sibian-v2') ?? 'null');
    if (isObj(v2) && Array.isArray(v2.library))
      return { ...base, library: notesFromCards(restoreCards(v2.library)) };
  } catch {
    /* 读取失败就从头开始 */
  }
  return base;
}

let state: AppState = load();
const listeners = new Set<() => void>();
let saveFailed = false;
let saveTimer: ReturnType<typeof setTimeout> | undefined;
function persist() {
  clearTimeout(saveTimer);
  saveTimer = undefined;
  try {
    localStorage.setItem(
      STORE,
      JSON.stringify({
        config: state.config,
        plan: state.plan,
        session: state.session,
        library: state.library,
        screen: state.ui.screen,
      }),
    );
    saveFailed = false;
  } catch {
    if (!saveFailed) {
      saveFailed = true;
      queueMicrotask(() => toast('浏览器未能保存记录，请导出本次内容。'));
    }
  }
}
// 打字时不必每个字都落盘：停下来 400 毫秒再存；页面被切走或关掉之前补存一次，不丢草稿
const TYPING_SAVE_DELAY = 400;
const flush = () => {
  if (saveTimer !== undefined) persist();
};
if (typeof addEventListener === 'function') {
  addEventListener('pagehide', flush);
  if (typeof document !== 'undefined')
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden') flush();
    });
}

export const getState = () => state;
// 界面上“在哪一页、开着哪个面板”：它变了，就用连贯动效切换
const viewOf = (s: AppState): View => ({
  screen: s.ui.screen,
  overlay:
    s.ui.sheet ??
    (s.ui.term
      ? 'term:' + s.ui.term.term
      : s.ui.explain
        ? 'explain'
        : s.ui.invite
          ? 'invite'
          : null),
});
// 界面读的是 rendered，不是最新的 state：页面过渡拍下旧画面之前，界面一直显示旧状态，
// 哪怕前面还有一次没画完的更新（比如点开卡片时先记下卡片 id，再换页）
let rendered: AppState = state;
const notify = () => {
  rendered = state;
  listeners.forEach(l => l());
};
export function update(fn: (draft: AppState) => void) {
  const before = viewOf(state);
  const next = structuredClone(state);
  fn(next);
  state = next;
  persist();
  const after = viewOf(next);
  if (before.screen !== after.screen || before.overlay !== after.overlay)
    transition(before, after, notify);
  else if (!holdingRenders()) notify();
}
// 输入框每敲一个字调用一次：只改草稿、题目这类文字，不换页面、不动知识库。
// 知识库可能很大，这里不复制它，也不立刻写入 localStorage（update 每次都会做这两件事，打字时会卡）
export function typing(fn: (draft: Omit<AppState, 'library'>) => void) {
  const { library, ...rest } = state;
  const next = structuredClone(rest);
  fn(next);
  state = { ...next, library };
  if (saveTimer === undefined) saveTimer = setTimeout(persist, TYPING_SAVE_DELAY);
  if (!holdingRenders()) notify();
}
export function useApp(): AppState {
  // 第三个参数给服务端渲染用（测试里把页面渲染成 HTML 检查），和浏览器里读的是同一份
  return useSyncExternalStore(
    cb => {
      listeners.add(cb);
      return () => listeners.delete(cb);
    },
    () => rendered,
    () => rendered,
  );
}

let toastTimer: ReturnType<typeof setTimeout> | undefined;
export function toast(text: string) {
  update(s => {
    s.ui.toast = text;
  });
  clearTimeout(toastTimer);
  toastTimer = setTimeout(
    () =>
      update(s => {
        s.ui.toast = '';
      }),
    3000,
  );
}
// 换页时回到顶部由 transition 在新的一帧里做
export function go(screen: Screen) {
  update(s => {
    s.ui.screen = screen;
    s.ui.sheet = null;
    s.ui.explain = null;
  });
}
export const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);

// 测试用：重置为初始状态
export function resetForTests(next?: Partial<AppState>) {
  clearTimeout(saveTimer);
  saveTimer = undefined;
  state = { ...load(), ...next };
  notify();
}
