#!/usr/bin/env python3
"""把 src/ 组装成单文件网页。
产出：dist/index.html（正式版）、prototype/index.html（含演示模型，仅用于原型体验）。
"""
from pathlib import Path
R = Path(__file__).resolve().parent
S = R / 'src'
core = (R / 'legacy/main.v12.js').read_text(encoding='utf-8')
core = core[:core.index('let state=')]                      # 只取原版的分段与合并核心
anchor = "只有没有任何支撑时才留空。'"
assert anchor in core
core = core.replace(anchor, "只有没有任何支撑时才留空。另输出 titles 数组：本段叙述者或其他角色对该人物使用的称呼、昵称、尊称、头衔、外号（不含本名与 aliases 已列项），每项含 title,by,line,evidence；by 为使用该称呼的角色，叙述中出现填“叙述”；evidence 为该行含此称呼的精确连续原文，且须能从上下文确定指的就是这个人物，代词与泛称（如他、她、少年、那人）不算。'", 1)
s0, s1 = core.index('function modelChunks('), core.index('const instructions=')
core = core[:s0] + r'''function modelChunks(lines){
  const MAX=12000,MIN=6000,chapters=chapterMap(lines),chunks=[];
  let current='';
  for(const [i,line] of lines.entries()){
    if(!line.trim())continue;
    const heading=chapters[i]&&chapters[i]===line.trim();
    if(heading&&current.length>=MIN){chunks.push(current);current='';}
    for(const segment of line.match(/[\s\S]{1,6500}/gu)||[]){
      const entry=(i+1)+' | '+segment+'\n';
      if(current.length+entry.length>MAX&&current){chunks.push(current);current='';}
      current+=entry;
    }
  }
  if(current)chunks.push(current);
  return chunks;
}
''' + core[s1:]
read = lambda n: (S / n).read_text(encoding='utf-8')
page = (read('shell.html')
        .replace('/*STYLE*/', read('style.css'))
        .replace('/*PROVIDERS*/', read('providers.js'))
        .replace('/*ENGINE*/', read('engine.js'))
        .replace('/*APP*/', core + '\n' + read('app.js').replace('/*ST*/', read('cards.js'))))
(R / 'dist/index.html').write_text(page, encoding='utf-8')
proto = R / 'prototype'
proto.mkdir(exist_ok=True)
(proto / 'index.html').write_text(page.replace('<script id="localEngine">', '<script>\n' + read('demo.js') + '\n</script>\n<script id="localEngine">', 1), encoding='utf-8')
print('dist/index.html', len(page), '字符')
