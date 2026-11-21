"""批量测试：每张壁纸跑 4 秒，截图，统计画出 / 跳过的图层，和包里的预览图比相似度。"""
import asyncio, glob, json, os, subprocess, sys, time
import numpy as np
from PIL import Image
from playwright.async_api import async_playwright
PAGE = os.environ.get('PAGE','bench.html')
ROOT = os.environ.get('BENCH', '/home/claude/bench')

def sim(render, prev):
    """预览图常是裁过的：在渲染图上横向滑一个同比例的框，取最像的那个位置（灰度相关系数）"""
    pr = prev.convert('L'); ap = pr.width / pr.height
    R = render.convert('L'); best = -1
    for f in np.linspace(0, 1, 13):
        if ap < R.width / R.height:
            cw = int(R.height * ap); x = int((R.width - cw) * f); box = (x, 0, x + cw, R.height)
        else:
            ch = int(R.width / ap); y = int((R.height - ch) * f); box = (0, y, R.width, y + ch)
        a = np.asarray(R.crop(box).resize((64, 64)), float).ravel(); b = np.asarray(pr.resize((64, 64)), float).ravel()
        a -= a.mean(); b -= b.mean(); d = np.sqrt((a * a).sum() * (b * b).sum())
        best = max(best, float((a * b).sum() / d) if d else 0)
    return best

async def main(names):
    srv = subprocess.Popen([sys.executable, '-m', 'http.server', '8766', '--bind', '127.0.0.1', '-d', ROOT], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1); out = {}
    try:
        async with async_playwright() as p:
            b = await p.chromium.launch(args=['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'])
            for n in names:
                pg = await b.new_page(viewport={'width': 1280, 'height': 720})
                logs = []; pg.on('console', lambda m: logs.append(m.text[:400]) if m.type in ('error',) else None); pg.on('pageerror', lambda e: logs.append('PAGEERR ' + str(e)[:400]))
                await pg.goto(f'http://127.0.0.1:8766/' + PAGE + f'?w={n}')
                st = None
                for _ in range(60):
                    st = await pg.evaluate('window.st')
                    if st['state'] != 'loading': break
                    await pg.wait_for_timeout(500)
                await pg.wait_for_timeout(2500)
                shot = f'{ROOT}/shots/{n}.png'; await pg.screenshot(path=shot)
                pv = (glob.glob(f'{ROOT}/w/{n}/preview.*') or [None])[0]
                s = None
                if pv:
                    try: s = round(sim(Image.open(shot), Image.open(pv)), 3)
                    except Exception as e: s = str(e)
                out[n] = {'state': st, 'sim': s, 'console': logs[:6]}
                await pg.close()
            await b.close()
    finally:
        srv.terminate()
    json.dump(out, open(f'{ROOT}/result.json', 'w'), ensure_ascii=False, indent=1)
    for n, r in out.items():
        st = r['state']; sk = st.get('skipped', [])
        print(f"\n## {n}  {st['state']}  图层 {st.get('layers','-')}  跳过 {len(sk)}  相似度 {r['sim']}" + (f"  {st.get('msg','')[:300]}" if st['state'] == 'err' else ''))
        for x in sk[:12]: print('   -', x.replace('\n', ' ')[:260])
        for x in r['console'][:3]: print('   console:', x.replace('\n', ' ')[:260])

os.makedirs(f'{ROOT}/shots', exist_ok=True)
asyncio.run(main(sys.argv[1:] or sorted(os.listdir(f'{ROOT}/w'))))
