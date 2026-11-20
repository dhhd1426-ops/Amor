"""从已审核的原型生成 App 里的拆书页面：去掉演示用的聊天页、我的页和演示控件，换上本机存储、真实分析引擎。"""
import re, sys, os
HERE = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(HERE, 'prototype.html')).read()
out_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '../../app/src/main/assets/chaishu/index.html')

def cut(s, a, b):
    i = s.index(a); j = s.index(b, i)
    return s[:i] + s[j:]
def R(s, a, b, n=1):
    assert s.count(a) == n, (s.count(a), a[:70]); return s.replace(a, b)

s = src
# ---------- head ----------
s = R(s, '<link rel="preconnect" href="https://fonts.googleapis.com">\n<link href="https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600&display=swap" rel="stylesheet">\n',
      '')
s = R(s, '<title>拆书 · 原型</title>', '<title>拆书</title>')
s = R(s, '<style>\n:root{', '''<style>
@font-face{font-family:"DM Sans";src:url(/font/400.ttf);font-weight:400}
@font-face{font-family:"DM Sans";src:url(/font/500.ttf);font-weight:500}
@font-face{font-family:"DM Sans";src:url(/font/600.ttf);font-weight:600}
:root{--top:0px;--bottom:0px;''')
# 在 App 里铺满，不画手机框
s = R(s, '@media (min-width:560px){.app{width:390px;height:min(844px,calc(100vh - 48px));border-radius:44px;box-shadow:0 0 0 10px #000,0 30px 80px rgba(0,0,0,.5)}}', '')
s = R(s, 'body{background:#0b0b0b;', 'body{background:var(--page);')
s = R(s, 'body[data-skin=light]{background:#dcdbd5}', '')
# 安全区
s = R(s, '.rail{flex:none;width:58px;background:var(--rail);display:flex;flex-direction:column;padding-top:calc(64px + env(safe-area-inset-top));',
         '.rail{flex:none;width:58px;background:var(--rail);display:flex;flex-direction:column;padding-top:calc(64px + var(--top));')
s = R(s, '.bar{display:flex;align-items:center;gap:2px;height:56px;padding:0 6px 0 4px;flex:none;margin-top:env(safe-area-inset-top)}',
         '.bar{display:flex;align-items:center;gap:2px;height:56px;padding:0 6px 0 4px;flex:none;margin-top:var(--top)}')
s = R(s, '.scroll{flex:1;overflow-y:auto;overflow-x:hidden;padding-bottom:48px}', '.scroll{flex:1;overflow-y:auto;overflow-x:hidden;padding-bottom:calc(48px + var(--bottom))}')
s = R(s, '  padding-bottom:env(safe-area-inset-bottom)}', '  padding-bottom:var(--bottom)}')
s = R(s, '.toast{position:absolute;left:50%;bottom:calc(28px + env(safe-area-inset-bottom));', '.toast{position:absolute;left:50%;bottom:calc(28px + var(--bottom));')
# 去掉演示用的聊天页、我的页样式
s = cut(s, '/* 聊天页 */', '/* 页面 */')
s = cut(s, '/* 我的（透明磨砂） */', '/* 书名即切换按钮 */')
# 删除确认
s = R(s, '/* 书名即切换按钮 */', '''.btn2.danger{background:color-mix(in oklab,#d0574f 18%,var(--card));color:#e0786f}
.run.err{color:#e0786f;background:color-mix(in oklab,#d0574f 14%,transparent)}
/* 书名即切换按钮 */''')

# ---------- markup：只留拆书页 ----------
s = cut(s, '  <section class="pg" id="chat">', '  <section class="pg" id="mod"')
s = R(s, '<section class="pg" id="mod" aria-hidden="true">', '<section class="pg" id="mod">')
s = cut(s, '<div class="ctl" role="group"', '<script>')
s = R(s, '<button class="ibtn" aria-label="更多"><svg', '<button class="ibtn" id="moreBtn" aria-label="更多"><svg')
s = R(s, '<button class="btn2" id="pickDemo">红楼梦示例</button>', '<button class="btn2" id="pickDemo">看示例</button>')
s = R(s, '<small>TXT · EPUB</small>', '<small>TXT · EPUB · 12 MB 以内</small>')
s = R(s, '<input type="file" id="file" accept=".txt,.epub,text/plain" hidden>\n', '<input type="file" id="file" accept=".txt,.epub,text/plain" hidden>\n')

# ---------- 脚本 ----------
s = R(s, "const cnNum=n=>n<=10?CN[n]:n<20?'十'+(n%10?CN[n%10]:''):CN[Math.floor(n/10)]+'十'+(n%10?CN[n%10]:'');",
         "const cnNum=n=>n>=100?String(n):n<=10?CN[n]:n<20?'十'+(n%10?CN[n%10]:''):CN[Math.floor(n/10)]+'十'+(n%10?CN[n%10]:'');\nconst U=()=>B?.unit||'回';")
s = R(s, "const ready=id=>!!B.chars&&B.analyzed>=Math.max(...B.chars[id].ch);",
         "const ready=id=>!!B.chars&&(B.sample?B.analyzed>=Math.max(...B.chars[id].ch):!!B.chars[id].fx||!!B.done);")
s = R(s, ">第${cnNum(c)}回</button>`;", ">第${cnNum(c)}${U()}</button>`;")
s = R(s, "<small>${f[1]==='推断'?'<i>推断</i> · ':''}第${cnNum(f[2])}回</small>", "<small>${f[1]==='推断'?'<i>推断</i> · ':''}第${cnNum(f[2])}${U()}</small>")
s = R(s, "if(v.k==='ch')return B.chars?`第${cnNum(v.i+1)}回`:`第 ${v.i+1} 章`;", "if(v.k==='ch')return `第${cnNum(v.i+1)}${U()}`;\n  if(v.k==='more')return B?.title||'';")
s = R(s, "<p>出场 ${c.ch.length} 回 · ", "<p>出场 ${c.ch.length} ${U()} · ")

# 书架、导航、导入：整段换掉
a = s.index('/* ================= 书架：多本书，存在本机 ================= */')
b = s.index('function openBook(b,instant){')
s = s[:a] + open(os.path.join(HERE, 'page_store.js')).read() + '\n' + s[b:]
s = R(s, "  B=b;sel=null;save();mineRow();", "  B=b;sel=null;remember();")
s = R(s, "  const n=b.chapters.length,done=!b.chars||b.analyzed>=n;\n  rw.classList.toggle('gone',done);rb.className='run';rb.disabled=!b.chars;\n  $('#runLbl').textContent=b.analyzed?`继续分析 ${b.analyzed}/${n}`:'用模型分析本书';$('#runBar').style.transform=`scaleX(${b.analyzed/n})`;",
         "  runState();")
s = R(s, "r.innerHTML=`<div class=\"no\">${b.chars?cnNum(i+1):i+1}</div>", "r.innerHTML=`<div class=\"no\">${cnNum(i+1)}</div>")
s = R(s, "  $('#bStats').textContent=[(b.wc/1e4).toFixed(1)+' 万字',b.chapters.length+(b.chars?' 回':' 章'),b.chars?b.order.length+' 人':null,b.analyzed>=b.chapters.length&&b.chars?'模型分析':'本地提取'].filter(Boolean).join(' · ');",
         "  $('#bStats').textContent=[(b.wc/1e4).toFixed(1)+' 万字',b.chapters.length+' '+U(),b.order.length?b.order.length+' 人':null,b.done||b.sample&&b.analyzed>=b.chapters.length?'模型分析':'本地提取'].filter(Boolean).join(' · ');")
s = R(s, "  if(!ok&&B.analyzed>=B.chapters.length)w.querySelectorAll('.tile').forEach(t=>{t.classList.remove('wait');t.querySelector('.sk').outerHTML='<p>示例只写了主要人物</p>'});",
         "  if(!ok&&(B.done||B.sample&&B.analyzed>=B.chapters.length))w.querySelectorAll('.tile').forEach(t=>{t.classList.remove('wait');t.querySelector('.sk').outerHTML='<p>出场少，没有单独成卡</p>'});")
s = R(s, "  if(!B.chars){w.innerHTML='<div class=\"note\">用模型分析后，这里会出现角色卡</div>';return;}",
         "  if(!B.chars||!sel){w.innerHTML='<div class=\"note\">用模型分析后，这里会出现角色卡</div>';return;}")
s = R(s, "    :c.rel.map(([id,t])=>", "    :(c.rel||[]).filter(([id])=>B.chars[id]).map(([id,t])=>")
s = R(s, "<p>出场 ${c.ch.length} ${U()} · ${(ok?c.rels:c.rel).length} 个关系对象</p>", "<p>出场 ${c.ch.length} ${U()} · ${(ok?c.rels:(c.rel||[])).length} 个关系对象</p>")
s = R(s, "    ${ok&&(c.al.length||c.ti)?", "    ${ok&&((c.al||[]).length||(c.ti||[]).length)?")
s = R(s, "${c.al.map(a=>`<span>${esc(a)}</span>`).join('')}${(c.ti||[]).map(([t,by])=>`<span>${esc(t)}<small>${esc(by)}</small></span>`).join('')}</div>`:''}\n    <div class=\"tiles\">",
         "${(c.al||[]).map(a=>`<span>${esc(a)}</span>`).join('')}${(c.ti||[]).map(([t,by])=>`<span>${esc(t)}<small>${esc(by)}</small></span>`).join('')}</div>`:''}\n    <div class=\"tiles\">")
s = R(s, "  if(t.classList.contains('add'))return toast('添加自定义标签');", "  if(t.classList.contains('add'))return toast('自定义标签下一版加');")
# 关系网：非卡片人物用 rel
s = R(s, "  const rel=c.fx&&ready(id)?c.rels.filter(r=>r.to).map(r=>[r.to,r.type]):c.rel;",
         "  const rel=(c.fx&&ready(id)?c.rels.filter(r=>r.to&&B.chars[r.to]).map(r=>[r.to,r.type]):(c.rel||[]).filter(([x])=>B.chars[x])).slice(0,10);")
s = R(s, "  if(!B.chars)return null;\n  const id=(!sel||sel==='world')?B.order[0]:sel", "  if(!B.chars||!B.order.length)return null;\n  const id=(!sel||sel==='world')?B.order[0]:sel")

# 模型分析按钮：整段换成引擎
a = s.index('/* ================= 模型分析 ================= */')
b = s.index('/* ================= 标签页 ================= */')
s = s[:a] + open(os.path.join(HERE, 'page_engine.js')).read() + '\n' + s[b:]

# 弹层：去掉 ＋ 按钮相关
s = R(s, "  sheet.style.height=['import','books','plus','pick'].includes(v.k)?'auto':'';\n  $('#plus').setAttribute('aria-expanded',v.k==='plus'||v.k==='pick');",
         "  sheet.style.height=['import','books','more'].includes(v.k)?'auto':'';")
s = R(s, "  if(!isOpen)return;isOpen=false;$('#plus').setAttribute('aria-expanded',false);", "  if(!isOpen)return;isOpen=false;")
a = s.index("  if(v.k==='plus'){"); b = s.index("  }else if(v.k==='books'){")
s = s[:a] + "  if(v.k==='more'){\n    el.style.position='static';\n    el.innerHTML=moreHTML();\n" + s[b:]
s = R(s, "${books.some(b=>b.sample)?'':'<button class=\"btn2\" style=\"flex:1\" data-act=\"demo\">红楼梦示例</button>'}", "${books.some(b=>b.sample)?'':'<button class=\"btn2\" style=\"flex:1\" data-act=\"demo\">看示例</button>'}")
s = R(s, "<button class=\"btn2\" style=\"flex:1\" data-act=\"demo\">红楼梦示例</button></div>`;", "<button class=\"btn2\" style=\"flex:1\" data-act=\"demo\">看示例</button></div>`;")
s = R(s, "    const t=e.target.closest('[data-ch],[data-goto],[data-act],[data-book],[data-pick]');if(!t)return;\n    if(t.dataset.pick){const id=t.dataset.pick,on=!picks.has(id);on?picks.add(id):picks.delete(id);t.setAttribute('aria-checked',on);\n      $('#sTitle').textContent=title({k:'pick'});save();mineRow();return;}\n    if(t.dataset.act==='pick'){hideSheet(true);setTimeout(()=>openSheet({k:'pick'},$('#plus')),T(230));return;}",
         "    const t=e.target.closest('[data-ch],[data-goto],[data-act],[data-book]');if(!t)return;\n    if(t.dataset.act&&moreAct(t))return;")
s = R(s, "    if(t.dataset.act==='file'){hideSheet(true);$('#file').click();}", "    if(t.dataset.act==='file'){hideSheet(true);pickFile();}")
s = R(s, "  if(v.k==='pick')return `拆书 (${books.filter(b=>picks.has(b.id)).length}/${books.length})`;\n", "")
s = R(s, "  if(v.k==='plus')return '';\n", "")
# 章节弹层：没分析到的章
s = R(s, "    el.innerHTML=B.chars&&B.analyzed>v.i?", "    el.innerHTML=B.chars&&c.sum?")
s = R(s, "<h3>出场</h3><div class=\"cast\">${c.cast.map(id=>", "<h3>出场</h3><div class=\"cast\">${(c.cast||[]).filter(id=>B.chars[id]).map(id=>")
s = R(s, "<h3>情节</h3>${c.ev.map(e=>", "<h3>情节</h3>${(c.ev||[]).map(e=>")
s = R(s, "${(c.wc/1000).toFixed(1)}k 字${B.chars?' · 模型分析后显示梗概与出场人物':''}</p>`;", "${(c.wc/1000).toFixed(1)}k 字</p>`;")

# 切章：记下每章起始行号，判断「回」还是「章」
a = s.index('/* ================= 文件读取与切章 ================= */')
b = s.index('/* ================= 演示控件 ================= */')
s = s[:a] + open(os.path.join(HERE, 'page_split.js')).read() + '\n' + s[b:]
# 尾巴：演示控件、聊天演示 → 启动
a = s.index('/* ================= 演示控件 ================= */')
b = s.rindex('</script>')
s = s[:a] + open(os.path.join(HERE, 'page_boot.js')).read() + '\n' + s[b:]

# 引文显示：统一过 qhtml（转义后只放行 <q>）
s = R(s, '<p class="why" style="margin-top:0">${r.why}</p>', '<p class="why" style="margin-top:0">${qhtml(r.why)}</p>', 2)
s = R(s, '<p class="det">${f.d}</p>', '<p class="det">${qhtml(f.d)}</p>')
s = R(s, '<p class="det">${a.d}</p>', '<p class="det">${qhtml(a.d)}</p>')
s = R(s, '        ${f.s.map(([n,q])=>', '        ${(f.s||[]).map(([n,q])=>')
s = R(s, '<dd>${esc(t)}</dd><div class="srcs">${srcBtn(c,q)}</div></dl>', '<dd>${esc(t)}</dd>${c?`<div class="srcs">${srcBtn(c,q)}</div>`:\'\'}</dl>')
s = R(s, "${r.sc.slice(0,3).map(", "${(r.sc||[]).slice(0,3).map(")
s = R(s, "<h3>前后变化</h3>${r.att.map(", "${(r.att||[]).length?'<h3>前后变化</h3>':''}${(r.att||[]).map(")
s = R(s, "      ${r.sc.length?`<h3>对白</h3>${r.sc.map(", "      ${(r.sc||[]).length?`<h3>对白</h3>${r.sc.map(")
s = R(s, "  const rels=ok?`<div class=\"targets\" role=\"tablist\">${c.rels.map(", "  const rels=ok&&(c.rels||[]).length?`<div class=\"targets\" role=\"tablist\">${c.rels.map(")
s = R(s, "<p>出场 ${c.ch.length} ${U()} · ${(ok?c.rels:(c.rel||[])).length} 个关系对象</p>", "<p>${esc(c.r||'')}${c.r?' · ':''}出场 ${c.ch.length} ${U()}</p>")
# 手机角色页与分析模型入口，沿用已有抽屉和数据渲染。
s = R(s, '</style>', open(os.path.join(HERE, 'page_layout.css')).read() + '</style>')
s = R(s, '<div style="flex:1"></div>\n      <button class="chip-btn" id="importBtn">', '<strong class="bar-title">拆书</strong>\n      <button class="chip-btn" id="importBtn">')
s = R(s, '<div class="run-wrap" id="runWrap"><div>', '<div class="run-wrap" id="runWrap"><div class="run-tools">')
s = R(s, '<span class="lbl" id="runLbl">用模型分析本书</span></button>', '<span class="lbl" id="runLbl">分析本书</span></button>\n            <button class="runModel press" id="runModel" type="button" aria-haspopup="dialog" hidden></button>')
s = R(s, "B.sample?B.analyzed>=Math.max(...B.chars[id].ch)", "B.sample?!!B.chars[id].fx")
s = R(s, "b.done||b.sample&&b.analyzed>=b.chapters.length?'模型分析':'本地提取'", "b.sample?'示例':b.done?'模型分析':'本地提取'")
s = R(s, "w.innerHTML='<div class=\"note\">用模型分析后，这里会出现角色卡</div>';", "w.innerHTML='<div class=\"note\">用模型分析后，这里会出现角色卡</div>'+directoryButton();")
s = R(s, '${B.world.items.map(([k,t,[c,q]])=>', '${directoryButton()}${B.world.items.map(([k,t,[c,q]])=>')
s = R(s, "'<div class=\"sk\"><i></i><i></i></div>'", "'<p class=\"unavailable\">'+(B._busy?'等待分析':'暂无资料')+'</p>'")
s = R(s, "t.querySelector('.sk').outerHTML='<p>出场少，没有单独成卡</p>'", "t.querySelector('.unavailable').textContent='出场少，暂无详细资料'")
a = s.index('  w.innerHTML=`<div class="hero"><div class="sq" style="--h:${c.h}')
b = s.index('    ${ok&&',a)
s = s[:a] + """  w.innerHTML=`<div class="hero hero-profile"><div class="sq" aria-hidden="true" style="--h:${c.h}">${esc(c.a)}</div><div class="person"><h2>${esc(c.n)}</h2>
      <p>${esc(c.r||'')}${c.r?' · ':''}出场 ${c.ch.length} ${U()}</p>
      <div class="person-tools">${directoryButton()}<button class="add press" aria-label="添加标签">+</button></div></div></div>
    ${(B.tags?.[sel]||[]).length?`<div class="chips">${B.tags[sel].map(tag=>`<span>${esc(tag)}</span>`).join('')}</div>`:''}
    <div class="sec"><h3>人物资料</h3></div>
""" + s[b:]
s = R(s, '[data-tile],[data-rel],[data-goto],[data-ch],[data-tg],.add', '[data-tile],[data-rel],[data-goto],[data-ch],[data-tg],.add,.switch-person')
s = R(s, "  if(t.classList.contains('add'))return toast('自定义标签下一版加');", "  if(t.classList.contains('switch-person'))return openSheet({k:'directory'},t);\n  if(t.classList.contains('add'))return openSheet({k:'tags'},t);")
s = R(s, "  if(v.k==='more')return B?.title||'';", "  if(v.k==='more')return B?.title||'';\n  if(v.k==='models')return '选择分析模型';\n  if(v.k==='directory')return '角色目录';\n  if(v.k==='tags')return '添加标签';")
s = R(s, "['import','books','more'].includes(v.k)", "['import','books','more','models','directory','tags'].includes(v.k)")
s = R(s, "  if(v.k==='more'){\n    el.style.position='static';", """  if(v.k==='models'||v.k==='directory'||v.k==='tags'){
    el.style.position='static';
    if(v.k==='models')el.innerHTML='<div class="choices">'+modelChoices()+'</div>';
    if(v.k==='directory'){
      el.innerHTML='<input class="field-input" type="search" placeholder="搜索角色" aria-label="搜索角色"><div class="directory"></div>';
      const search=el.querySelector('input'),list=el.querySelector('.directory');
      const update=()=>{list.innerHTML=directoryRows(search.value)};
      search.addEventListener('input',update);update();
    }
    if(v.k==='tags'){
      el.innerHTML='<form><input class="field-input" aria-label="新标签" placeholder="输入标签" maxlength="16" required><button class="choice press" type="submit">添加</button></form>';
      el.querySelector('form').addEventListener('submit',e=>{
        e.preventDefault();const tag=el.querySelector('input').value.trim();
        if(!tag)return;
        B.tags=B.tags||{};B.tags[sel]=[...new Set([...(B.tags[sel]||[]),tag])];persist(B);
        hideSheet();renderCard();$('#cardwrap .add')?.focus({preventScroll:true});
      });
    }
  }else if(v.k==='more'){
    el.style.position='static';""")
for bad in ['#plus', '#csSub', 'mineRow(', "'#send'", 'picks.', 'env(safe-area']:
    assert bad not in s, (bad, s[s.find(bad)-200:s.find(bad)+80])
assert not re.search(r'(?<![.\w])push\(', s)
open(out_path, 'w').write(s)
print('ok', len(s))
