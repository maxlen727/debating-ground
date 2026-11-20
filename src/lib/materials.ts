// 读材料（#144）：完整的材料只存在这台设备上（IndexedDB），不进存档、导出和讨论记录；
// 讨论里只带一个技术点的原文节选。没有 IndexedDB 的环境（测试、隐私模式）退回内存里，关掉页面就没了。
import type { MaterialMap, MaterialSection, ReadStep } from '../../shared/types.ts';

export interface Material {
  id: string;
  title: string;
  sections: MaterialSection[];
  map?: MaterialMap;
  steps?: ReadStep[];
  created: number;
}
export type MaterialMeta = Pick<Material, 'id' | 'title' | 'created'> & { points: number };

const DB = 'sibian-materials';
const STORE = 'materials';
const memory = new Map<string, Material>();

function open(): Promise<IDBDatabase | null> {
  if (typeof indexedDB === 'undefined') return Promise.resolve(null);
  return new Promise(resolve => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE, { keyPath: 'id' });
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => resolve(null);
  });
}
function run<T>(
  mode: IDBTransactionMode,
  f: (s: IDBObjectStore) => IDBRequest<T>,
): Promise<T | null> {
  return open().then(
    db =>
      db &&
      new Promise<T | null>(resolve => {
        const req = f(db.transaction(STORE, mode).objectStore(STORE));
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => resolve(null);
      }),
  );
}

export async function saveMaterial(m: Material) {
  memory.set(m.id, m);
  await run('readwrite', s => s.put(m));
}
export async function getMaterial(id: string): Promise<Material | null> {
  return (
    ((await run<Material>('readonly', s => s.get(id) as IDBRequest<Material>)) ?? memory.get(id)) ||
    null
  );
}
export async function deleteMaterial(id: string) {
  memory.delete(id);
  await run('readwrite', s => s.delete(id));
}
/** 最近的材料（新的在前）。 */
export async function listMaterials(): Promise<MaterialMeta[]> {
  const all = (await run<Material[]>('readonly', s => s.getAll() as IDBRequest<Material[]>)) ?? [
    ...memory.values(),
  ];
  return all
    .map(m => ({ id: m.id, title: m.title, created: m.created, points: m.map?.points.length ?? 0 }))
    .sort((a, b) => b.created - a.created);
}
