# 背景氛围：几团很大、边缘按高斯曲线渐隐的色块，离线渲染成图片，网页版和原生版共用。
# 图片很小（背景本来就是糊的，放大几倍看不出差别），用无损 WebP：有损压缩会把抖动抹掉、压出马赛克色块。
# 实时画渐变加模糊太费电（网页版上面还压着一层层磨砂，背景一动它们就得每帧重算），
# 所以在这里一次算好：浮点运算、最后加一点抖动再取整，渐变上没有色带。
#
# 用法：python3 scripts/atmosphere.py
#   读 src/assets/atmosphere/atmosphere.json 的参数和 src/styles.css 的颜色令牌（浅色、深色），
#   写出 src/assets/atmosphere/{light,dark}-{portrait,landscape}.webp，复制一份到原生版的
#   native/app/src/main/res/drawable-nodpi/atmosphere_*.webp，并更新 stamp（测试用它判断图片是不是最新的）。
import hashlib, json, os, re, shutil, sys
import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIR = os.path.join(ROOT, 'src/assets/atmosphere')
RES = os.path.join(ROOT, 'native/app/src/main/res/drawable-nodpi')
PARAMS = os.path.join(DIR, 'atmosphere.json')


def tokens(css):
    """浅色是第一个 :root，深色是紧跟着的 prefers-color-scheme: dark 里的 :root。"""
    light = re.search(r':root\s*\{(.*?)\n\}', css, re.S).group(1)
    dark = re.search(r'@media \(prefers-color-scheme: dark\)\s*\{\s*:root\s*\{(.*?)\n  \}', css, re.S).group(1)
    pick = lambda block: dict(re.findall(r'--([a-z0-9-]+):\s*(#[0-9a-fA-F]{6})\s*;', block))
    return {'light': pick(light), 'dark': pick(dark)}


def colors(params, tok):
    """每种主题按顺序列出用到的颜色：底色 surface，然后每团色块一种。"""
    need = ['surface'] + [g['token'] for g in params['glows']]
    return {theme: [tok[theme][n].lower() for n in need] for theme in ('light', 'dark')}


def stamp(params_bytes, cols):
    # 和 tests/atmosphere.test.ts 的算法一致：参数文件原文 + 两种主题的颜色
    text = params_bytes.decode() + '\nlight:' + ','.join(cols['light']) + '\ndark:' + ','.join(cols['dark'])
    return hashlib.sha256(text.encode()).hexdigest()


def rgb(hex_):
    return np.array([int(hex_[i:i + 2], 16) for i in (1, 3, 5)], dtype=np.float64) / 255


def render(params, cols, w, h, seed):
    """图片比屏幕每边多出 overscan（按宽高的比例），留给漂移用；色块的位置和半径按屏幕算。"""
    o = params['overscan']
    vw, vh = w / (1 + 2 * o), h / (1 + 2 * o)  # 图片里“屏幕”那一块的大小
    long_side = max(vw, vh)
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float64) + 0.5
    img = np.empty((h, w, 3))
    img[:] = rgb(cols[0])
    for g, c in zip(params['glows'], cols[1:]):
        cx, cy = vw * o + vw * g['x'], vh * o + vh * g['y']
        d2 = ((xs - cx) ** 2 + (ys - cy) ** 2) / (g['r'] * long_side) ** 2
        # 高斯渐隐：半径一半处约剩四成，到半径处几乎没了（和原来径向渐变的三个色标对得上，但没有折痕）
        a = g['alpha'] * np.exp(-3.6 * d2)
        img = img * (1 - a[..., None]) + rgb(c) * a[..., None]
    # 三角分布的抖动，再取整：8 位颜色在大片缓慢的渐变上会出现一圈圈色带，抖动把它打散
    rnd = np.random.default_rng(seed)
    noise = rnd.random((h, w, 1)) - rnd.random((h, w, 1))
    out = np.clip(np.round(img * 255 + noise), 0, 255).astype(np.uint8)
    return Image.fromarray(out, 'RGB')


def main():
    with open(PARAMS, 'rb') as f:
        raw = f.read()
    params = json.loads(raw)
    with open(os.path.join(ROOT, 'src/styles.css'), encoding='utf-8') as f:
        cols = colors(params, tokens(f.read()))
    os.makedirs(RES, exist_ok=True)
    for i, theme in enumerate(('light', 'dark')):
        for j, (shape, (w, h)) in enumerate(params['sizes'].items()):
            path = os.path.join(DIR, f'{theme}-{shape}.webp')
            render(params, cols[theme], w, h, seed=2026 + i * 2 + j).save(path, 'WEBP', lossless=True, method=6)
            shutil.copyfile(path, os.path.join(RES, f'atmosphere_{theme}_{shape}.webp'))
            print(path, os.path.getsize(path), 'bytes')
    with open(os.path.join(DIR, 'stamp'), 'w') as f:
        f.write(stamp(raw, cols) + '\n')


if __name__ == '__main__':
    sys.exit(main())
