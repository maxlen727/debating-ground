# 思辨场的标志：深绿圆角方块 + 衬线“〞”。字形本身偏在字框左上角，所以先渲染、按实际笔画裁切，再居中拼图标。
import sys, glob
from playwright.sync_api import sync_playwright
from PIL import Image, ImageDraw
# 用法：python3 scripts/android-icons.py android/app/src/main/res '#E8E7DE'（启动图底色用 --page）
import os, tempfile
RES, PAGE = sys.argv[1], sys.argv[2]
GLYPH = os.path.join(tempfile.gettempdir(), 'sibian-glyph.png')
BRAND, INK = (31, 59, 54, 255), '#E8EFD9'
with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page(viewport={'width': 1200, 'height': 1200})
    pg.set_content(f"<body style='margin:0'><div style='font:1000px/1 \"Noto Serif CJK SC\",serif;color:{INK};padding:100px'>〞</div></body>")
    pg.screenshot(path=GLYPH, omit_background=True); b.close()
g = Image.open(GLYPH).convert('RGBA')
g = g.crop(g.getbbox())
def mark(size, frac):  # 字的高度占 size 的比例，居中放在透明画布上
    h = round(size * frac); w = round(g.width * h / g.height)
    c = Image.new('RGBA', (size, size)); c.alpha_composite(g.resize((w, h), Image.LANCZOS), ((size - w) // 2, (size - h) // 2)); return c
def tile(size, radius_frac, frac=0.42):  # 圆角方块（radius_frac=0.5 是圆形），4 倍超采样
    big = size * 4; t = Image.new('RGBA', (big, big))
    ImageDraw.Draw(t).rounded_rectangle([0, 0, big - 1, big - 1], radius=big * radius_frac, fill=BRAND)
    t.alpha_composite(mark(big, frac)); return t.resize((size, size), Image.LANCZOS)
for d, k in {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}.items():
    s, f = round(48 * k), round(108 * k)
    mark(f, 0.31).save(f'{RES}/mipmap-{d}/ic_launcher_foreground.png')   # 自适应图标：只在中间安全区
    tile(s, 0.22).save(f'{RES}/mipmap-{d}/ic_launcher.png')
    tile(s, 0.5).save(f'{RES}/mipmap-{d}/ic_launcher_round.png')
pc = tuple(int(PAGE[i:i + 2], 16) for i in (1, 3, 5)) + (255,)
for path in glob.glob(f'{RES}/drawable*/splash.png'):
    w, h = Image.open(path).size; m = round(min(w, h) * 0.22)
    c = Image.new('RGBA', (w, h), pc); c.alpha_composite(tile(m, 0.22), ((w - m) // 2, (h - m) // 2)); c.convert('RGB').save(path)
