import asyncio,time,sys
from pathlib import Path
from playwright.async_api import async_playwright
from fake_deepseek import handle,stats
ROOT=Path(__file__).resolve().parent.parent
BOOK=sys.argv[1] if len(sys.argv)>1 else str(ROOT/'tests/sample-xiangong.txt'); W=int(sys.argv[2]) if len(sys.argv)>2 else 390
async def main():
    async with async_playwright() as p:
        b=await p.chromium.launch(executable_path=__import__('shutil').which('google-chrome') or None,args=['--no-sandbox','--allow-file-access-from-files'])
        pg=await b.new_page(viewport={'width':W,'height':844})
        errs=[];pg.on('pageerror',lambda e: errs.append(str(e)))
        await pg.route('https://api.deepseek.com/**',handle)
        await pg.goto((ROOT/'dist/index.html').as_uri()); await pg.wait_for_timeout(1500)
        await pg.click('#settings'); await pg.select_option('#provider','deepseek'); await pg.fill('#token','sk-test')
        await pg.click('#testApi'); await pg.wait_for_selector('#modelRow:not([hidden])')
        await pg.click('#saveApi')
        await pg.wait_for_function("!document.getElementById('settingsDialog').open",timeout=60000)
        print('saved:',await pg.inner_text('#status'))
        t=time.time()
        await pg.set_input_files('#file',BOOK)
        await pg.wait_for_timeout(1500)
        if await pg.is_visible('#confirmDialog'): await pg.click('#confirmRun')
        shots=0
        while True:
            await pg.wait_for_timeout(1000)
            mode=await pg.get_attribute('#dropbox','data-mode')
            if shots<2 and mode=='model':
                await pg.locator('#dropbox').screenshot(path=f'/tmp/ring{shots}.png'); shots+=1
            if mode in ('done','error') or (mode is None and time.time()-t>8): break
            if time.time()-t>400: print('timeout'); break
        await pg.locator('#dropbox').screenshot(path='/tmp/ring_end.png')
        print('mode',mode,'|',await pg.inner_text('#ringLine1'),'|',await pg.inner_text('#ringLine2'))
        print('status:',await pg.inner_text('#status')); print('stats:',await pg.inner_text('#stats'))
        print('secs',round(time.time()-t,1),{k:v for k,v in stats.items()})
        chips=await pg.locator('.identity-line .chip').all_inner_texts(); print('chips',chips)
        print('bg:',(await pg.locator('.note').nth(0).inner_text())[:120].replace('\n',' / '))
        print('errs',errs)
        await b.close()
asyncio.run(main())
