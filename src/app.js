/* ---------- 基础 ---------- */
let state={title:'',lines:[],chapters:[],people:[],selected:'',tab:'card',target:0,sample:false,forced:[],engine:'local',size:0,sceneLimits:new Map()};
let cfg=null,busy=false,analyzeAfterSettings=false,screen='landing',modelCtl=null,graphCache=null;
const html=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const sourceLabel=line=>(state.chapters[line-1]?state.chapters[line-1]+' · ':'')+'第 '+line+' 行';
const chev='<svg class="chev" viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg>';
const sleep=(ms,signal)=>new Promise(r=>{const t=setTimeout(r,ms);signal?.addEventListener('abort',()=>{clearTimeout(t);r();},{once:true});});
const fmt=n=>Number(n).toLocaleString('zh-CN');

addEventListener('resize',()=>{if(state.tab==='graph'&&state.lines.length){const w=q('view').clientWidth;if(Math.abs(w-(renderGraph.w||0))>40){renderGraph.w=w;renderGraph();}}});
function setStatus(msg,error=false){
  const s=q('status');s.textContent=msg;s.classList.toggle('error',error);
  s.style.animation='none';void s.offsetWidth;s.style.animation='';
}
const toast=setStatus;

/* ---------- 本地存储 ---------- */
async function tagBookKey(text){
  const bytes=new TextEncoder().encode(text);
  if(globalThis.crypto?.subtle){
    const hash=new Uint8Array(await crypto.subtle.digest('SHA-256',bytes));
    return Array.from(hash,x=>x.toString(16).padStart(2,'0')).join('');
  }
  let h=2166136261;
  for(const byte of bytes)h=Math.imul(h^byte,16777619);
  return bytes.length+'-'+(h>>>0);
}
function tagStorageKey(bookKey,name){return 'renwuzhi.tags.1.'+bookKey+'.'+name;}
function savedTags(bookKey,name){
  try{const v=JSON.parse(localStorage.getItem(tagStorageKey(bookKey,name))||'[]');return Array.isArray(v)?v.filter(t=>typeof t==='string'):[];}catch{return [];}
}
const store=(()=>{
  let opening;
  const open=()=>opening||=new Promise((res,rej)=>{
    try{const r=indexedDB.open('renwuzhi',1);r.onupgradeneeded=()=>r.result.createObjectStore('kv');r.onsuccess=()=>res(r.result);r.onerror=()=>rej(r.error);}catch(e){rej(e);}
  });
  async function run(mode,fn){
    try{const d=await open();return await new Promise((res,rej)=>{const t=d.transaction('kv',mode),req=fn(t.objectStore('kv'));t.oncomplete=()=>res(req?.result);t.onerror=()=>rej(t.error);});}
    catch{return undefined;}
  }
  return{get:k=>run('readonly',s=>s.get(k)),set:(k,v)=>run('readwrite',s=>s.put(v,k)),del:k=>run('readwrite',s=>s.delete(k))};
})();
const ckKey=(book,c)=>'ck|'+book+'|'+c.provider+'|'+c.endpoint+'|'+c.model;

/* ---------- 读取小说 ---------- */
function analyzeInBackground(lines,forced,progress){
  if(!window.Worker)return Promise.resolve().then(()=>analyzeLocal(lines,forced,progress));
  const script=q('localEngine').textContent+'\nself.onmessage=({data})=>{const progress=message=>self.postMessage({message});try{self.postMessage({people:analyzeLocal(data.lines,data.forced,progress)});}catch(error){self.postMessage({error:error.message});}};';
  const url=URL.createObjectURL(new Blob([script],{type:'text/javascript'}));
  return new Promise((resolve,reject)=>{
    let worker;
    function cleanup(){worker?.terminate();URL.revokeObjectURL(url);}
    function runLocally(){cleanup();Promise.resolve().then(()=>analyzeLocal(lines,forced,progress)).then(resolve,reject);}
    try{worker=new Worker(url);}catch{runLocally();return;}
    worker.onmessage=({data})=>{
      if(data.message){progress(data.message);return;}
      cleanup();
      if(data.error)reject(new Error(data.error));else resolve(data.people);
    };
    worker.onerror=runLocally;
    worker.postMessage({lines,forced});
  });
}
function setBusy(v){
  busy=v;
  for(const id of ['sample','file','paste'])q(id).disabled=v;
  q('busybar').hidden=!v;
  syncActions();
}
function syncActions(){
  const running=!!modelCtl;
  q('export').disabled=busy||!state.people.length;
  q('runBookModel').disabled=busy||running;
  q('runBookModel').querySelector('span').textContent=state.engine==='model'?'用模型重新分析':'用模型分析本书';
  q('runBookModel').title=q('runBookModel').querySelector('span').textContent;
}
async function loadBook(title,content,{sample=false,forced=[],local=false,large=false}={}){
  if(busy)return;
  if(!content.trim()){toast('正文为空，请换一个文本。',true);return;}
  if(modelCtl){modelCtl.superseded=true;modelCtl.abort();modelCtl=null;q('modelPill').hidden=true;}
  runSeq++;ringHide();
  const lines=content.replace(/^﻿/,'').split(/\r\n|\n|\r/u);
  setBusy(true);
  const keep=local&&state.lines.length;
  setStatus('正在定位人物和原文线索…');
  if(!keep)ringShow('local',{ratio:.25,line1:'正在读取《'+title+'》',line2:'定位人物和原文线索'});
  try{
    const [people,bookKey]=await Promise.all([analyzeInBackground(lines,forced,setStatus),tagBookKey(lines.join('\n'))]);
    for(const p of people)p.customTags=savedTags(bookKey,p.name);
    localTitles(people,lines);
    const prev=keep?state.selected:'';
    state={bookKey,title,lines,chapters:chapterMap(lines),people,localPeople:people,selected:people.find(p=>p.name===prev)?.name||people[0]?.name||'',tab:'card',target:0,sample,forced,engine:'local',size:content.replace(/\s/gu,'').length,sceneLimits:new Map()};
    graphCache=null;q('search').value='';
    let restored=false;
    if(!sample&&!forced.length){
      const saved=await store.get('res|'+bookKey);
      if(saved?.people?.length){
        for(const p of saved.people)p.customTags=savedTags(bookKey,p.name);
        state.people=saved.people;state.engine='model';state.selected=saved.people[0].name;restored=saved.model;
      }
    }
    if(ringMode==='local')ringHide();
    render(true);
    setStatus(sample?'正在查看内置示例。':restored?'已恢复上次由 '+restored+' 分析的档案，共 '+state.people.length+' 位角色。':cfg&&large&&!cfg.longModel?'长篇本地档案已就绪。点「用模型分析本书」可分段处理当前小说。':'已读取正文，识别 '+state.people.length+' 位角色。');
    if(cfg&&!sample&&!local&&!restored&&(!large||cfg.longModel)){setBusy(false);await runBookModel(true);}
  }catch{
    ringHide();setStatus('本地提取中断，请重新导入。',true);
  }finally{setBusy(false);}
}
async function onFile(file){
  if(busy)return;
  if(!file){toast('没有收到文件，请重新选择。',true);return;}
  const suffix=/\.(txt|md|markdown)$/iu;
  const textType=/^text\/(?:plain|markdown|x-markdown)(?:;|$)/iu;
  if(!suffix.test(file.name)&&(/\.[^.]+$/u.test(file.name)||!textType.test(file.type))){toast('无法导入「'+file.name+'」，请选择 TXT 或 Markdown 文件。',true);return;}
  if(file.size>6*1024*1024){toast('单个文件最大 6 MB，这本是 '+(file.size/1048576).toFixed(1)+' MB，请拆分后导入。',true);return;}
  let content;
  try{
    const data=await file.arrayBuffer(),bytes=new Uint8Array(data);
    if(bytes[0]===255&&bytes[1]===254)content=new TextDecoder('utf-16le').decode(data);
    else if(bytes[0]===254&&bytes[1]===255)content=new TextDecoder('utf-16be').decode(data);
    else{try{content=new TextDecoder('utf-8',{fatal:true}).decode(data);}catch{content=new TextDecoder('gb18030').decode(data);}}
    if(content.includes('\0'))throw new Error('Invalid text');
  }catch{toast('读取失败，请重新选择 TXT 或 Markdown 文件。',true);return;}
  await loadBook(file.name.replace(suffix,'')||'未命名小说',content,{large:file.size>512*1024});
}

/* ---------- 模型精读：并发预取 + 断点续跑 ---------- */
function timeoutSignal(signal,ms){
  if(AbortSignal.any&&AbortSignal.timeout)return AbortSignal.any([signal,AbortSignal.timeout(ms)]);
  const c=new AbortController(),t=setTimeout(()=>c.abort(),ms);
  signal.addEventListener('abort',()=>{clearTimeout(t);c.abort();},{once:true});
  return c.signal;
}
function parseModelJSON(answer){
  const body=answer.replace(/^`{3}(?:json)?\s*/iu,'').replace(/\s*`{3}$/u,'');
  let data;
  try{data=JSON.parse(body);}
  catch(e){const a=body.indexOf('{'),b=body.lastIndexOf('}');if(a<0||b<=a)throw e;data=JSON.parse(body.slice(a,b+1));}
  if(!Array.isArray(data?.people))throw new SyntaxError('模型返回的档案缺少人物列表');
  return data;
}
function chunkHints(part,local){
  return local.filter(p=>[p.name,...(p.aliases||[])].some(n=>part.includes(n))).slice(0,60).map(p=>({
    name:p.name,aliases:p.aliases||[],titles:(p.titles||[]).map(t=>t.title).slice(0,12),customTags:p.customTags||[],missing:keys.filter(k=>!p.fields[k])
  }));
}
async function askChunk(part,c,local,signal){
  const context='本段本地候选（供核对，用户标签不能作原文证据，可新增或纠正人物）：'+JSON.stringify(chunkHints(part,local))+'\n带行号的原文：\n'+part;
  return parseModelJSON(await generateText(c,instructions,context,timeoutSignal(signal,300000)));
}
function halves(part){
  const rows=part.trimEnd().split('\n');
  if(rows.length<2){const t=rows[0]||'',k=t.indexOf(' | '),head=t.slice(0,k+3),body=t.slice(k+3),m=Math.ceil(body.length/2);return body.length<400?null:[head+body.slice(0,m)+'\n',head+body.slice(m)+'\n'];}
  let size=0,total=part.length,cut=1;
  for(let i=0;i<rows.length;i++){size+=rows[i].length+1;if(size>=total/2){cut=Math.max(1,Math.min(rows.length-1,i+1));break;}}
  return [rows.slice(0,cut).join('\n')+'\n',rows.slice(cut).join('\n')+'\n'];
}
async function fetchChunk(part,c,local,signal,depth=0,onSplit){
  try{return await askChunk(part,c,local,signal);}
  catch(err){
    const splittable=(err?.name==='TruncatedError'||err instanceof SyntaxError)&&depth<3&&!signal.aborted;
    const pair=splittable&&halves(part);
    if(!pair)throw err;
    onSplit?.();
    const a=await fetchChunk(pair[0],c,local,signal,depth+1,onSplit);
    const b=await fetchChunk(pair[1],c,local,signal,depth+1,onSplit);
    return{people:[...a.people,...b.people]};
  }
}
function retryable(err,signal){
  if(signal.aborted)return false;
  if(err instanceof SyntaxError||['TruncatedError','TimeoutError','AbortError','EmptyError'].includes(err?.name))return true;
  if(err instanceof TypeError)return true;
  return /HTTP (?:408|409|425|429|5\d\d)/.test(err?.message||'');
}
async function prefetch(parts,c,local,checkpoint,signal,tick,persist){
  const todo=parts.map((_,i)=>i).filter(i=>!checkpoint.has(i));
  let next=0,failure=null;
  async function lane(){
    while(next<todo.length&&!failure&&!signal.aborted){
      const i=todo[next++];
      tick(i,'run');
      for(let attempt=1;;attempt++){
        try{
          const data=await fetchChunk(parts[i],c,local,signal,0,()=>tick(i,'split'));
          checkpoint.set(i,data);tick(i,'ok');persist();break;
        }catch(err){
          if(attempt<4&&retryable(err,signal)){tick(i,'retry');await sleep((/HTTP 429/.test(err?.message)?6000:2500)*attempt,signal);if(signal.aborted)break;continue;}
          if(!signal.aborted){tick(i,'bad');failure=failure||Object.assign(new Error(friendlyError(err)),{chunk:i+1});}
          break;
        }
      }
    }
  }
  await Promise.all(Array.from({length:Math.max(1,Math.min(c.conc||3,todo.length))},lane));
  if(signal.aborted)throw new DOMException('stopped','AbortError');
  if(failure)throw failure;
}
function friendlyError(err){
  const m=err?.message||String(err);
  if(err instanceof SyntaxError)return '模型返回的 JSON 不完整，已拆小重试仍失败，可换一个模型再试';
  if(err?.name==='TruncatedError')return '模型输出总是超过长度上限，请在生成参数里调高输出上限';
  if(err?.name==='TimeoutError')return '模型响应超时（5 分钟），请稍后继续';
  if(err?.name==='EmptyError')return '模型多次返回空内容，请检查所选模型是否为文本对话模型';
  if(err instanceof TypeError)return Date.now()-cspBlock<5000?'当前页面的安全策略禁止连接外部接口，部署后或在 App 中即可使用':'网络连接失败，请检查网络后继续';
  return m.replace(/[。.]+$/,'');
}
function enrichModelCards(people,local,lines){
  const allLinks=[...local,...people].flatMap(p=>p.aliasEvidence||[]);
  const links=[...new Map(allLinks.map(x=>[JSON.stringify([x.name,x.alias,x.line]),x])).values()];
  if(links.length)return unifyIdentities([...people,...local],links,lines);
  const result=new Map(local.map(p=>[p.name,p]));
  for(const p of people){
    const old=result.get(p.name);
    if(old){
      p.fields={...old.fields,...p.fields};
      p.soloScenes=old.soloScenes||[];
      const edges=new Map(old.relations.map(r=>[r.to,r]));
      for(const edge of p.relations){
        const prior=edges.get(edge.to);
        if(prior){
          if(edge.type==='同场互动')edge.type=prior.type;
          for(const [key,prop] of [['attitudes','value'],['scenes','quote']]){
            const byKey=new Map([...prior[key],...edge[key]].map(x=>[JSON.stringify([x.line,x[prop],x.speaker]),x]));
            edge[key]=[...byKey.values()].sort((a,b)=>a.line-b.line);
          }
        }
        edges.set(edge.to,edge);
      }
      p.relations=[...edges.values()];
    }
    result.set(p.name,p);
  }
  return [...result.values()].sort((a,b)=>b.mentions-a.mentions);
}

/* ---------- 剧情称呼标签 ---------- */
const titleWords=/(?:人称|被称为|被称作|称作|称为|尊称(?:为|她为|他为)?|敬称|号称|绰号|外号|诨号|昵称|美称|雅号|唤作|唤为)[「“『"]?([\p{Script=Han}·]{2,8})[」”』"]?|(?:以|作)[「“『"]([\p{Script=Han}]{1,8})[」”』"](?:昵称|称|相称|呼之|唤)/gu;
function addTitle(p,title,line,by,src){
  if(!title||title===p.name||(p.aliases||[]).includes(title)||title.length>12)return;
  p.titles||=[];
  let t=p.titles.find(x=>x.title===title);
  if(!t){t={title,lines:[],by:[],src};p.titles.push(t);}
  if(!t.lines.includes(line))t.lines.push(line);
  if(by&&!t.by.includes(by))t.by.push(by);
  if(src==='model')t.src='model';
}
function localTitles(people,lines){
  const forms=people.flatMap(p=>[p.name,...(p.aliases||[])].map(n=>[n,p])).filter(([n])=>n.length>=2).sort((a,b)=>b[0].length-a[0].length);
  for(const [i,line] of lines.entries()){
    if(!/人称|称为|称作|尊称|敬称|号称|绰号|外号|诨号|昵称|美称|雅号|唤作|唤为|相称|呼之/u.test(line))continue;
    for(const sentence of line.split(/(?<=[。！？!?])/u)){
      for(const m of sentence.matchAll(titleWords)){
        const title=m[1]||m[2];
        let best=null,bestD=Infinity;
        for(const [n,p] of forms){
          let k=-1;
          while((k=sentence.indexOf(n,k+1))>=0){
            const d=k<m.index?m.index-k:(k-m.index)*1.5+40;
            if(d<bestD){bestD=d;best=p;}
          }
        }
        if(best&&!forms.some(([n])=>n===title))addTitle(best,title,i+1,'叙述','local');
      }
    }
  }
}
function modelTitles(people,parts,checkpoint){
  const ownerOf=new Map();
  for(const p of people)for(const n of [p.name,...(p.aliases||[])])if(!ownerOf.has(n))ownerOf.set(n,p);
  for(const [i,part] of parts.entries()){
    const data=checkpoint.get(i);if(!data)continue;
    const seg=new Map();
    for(const row of part.trimEnd().split('\n')){const s=row.indexOf(' | '),n=Number(row.slice(0,s));if(!seg.has(n))seg.set(n,[]);seg.get(n).push(row.slice(s+3));}
    for(const item of data.people||[]){
      const p=ownerOf.get(string(item?.name));if(!p)continue;
      for(const t of Array.isArray(item.titles)?item.titles:[]){
        const title=string(t?.title),proof=string(t?.evidence),line=t?.line;
        if(!title||!proof.includes(title)||!Number.isInteger(line)||!seg.get(line)?.some(x=>x.includes(proof)))continue;
        if(ownerOf.has(title)&&ownerOf.get(title)!==p)continue;
        addTitle(p,title,line,string(t.by),'model');
      }
    }
  }
}

let runSeq=0;
async function runBookModel(confirmed=false){
  if(busy||modelCtl||!state.lines.length)return;
  if(!cfg){analyzeAfterSettings=true;openSettings();return;}
  const current=state,parts=modelChunks(current.lines);
  if(current.checkConfig!==cfg){current.checkpoint=new Map();current.checkConfig=cfg;}
  const key=ckKey(current.bookKey,cfg);
  const saved=await store.get(key);
  if(Array.isArray(saved))for(const [i,d] of saved)if(!current.checkpoint.has(i))current.checkpoint.set(i,d);
  if(!confirmed&&parts.length>3){
    const done=current.checkpoint.size;
    q('confirmStats').innerHTML='<div><b>'+fmt(current.size)+'</b><span>字</span></div><div><b>'+fmt(parts.length)+'</b><span>段</span></div><div><b>'+(cfg.conc||3)+'</b><span>并发</span></div>';
    q('confirmNote').textContent=(done?'上次已完成 '+done+' 段，将从断点继续。':'')+'按章节分段，每段不超过 1.2 万字，发往 '+cfg.model+'，按段计费。';
    q('confirmRun').textContent=done?'继续':'开始';
    q('confirmDialog').showModal();return;
  }
  const ctl=modelCtl=new AbortController(),run=++runSeq;
  const live=()=>run===runSeq&&modelCtl===ctl;
  const total=parts.length,started=performance.now(),startDone=current.checkpoint.size;
  const status=parts.map((_,i)=>current.checkpoint.has(i)?'ok':'');
  const begun=new Map(),spent=[];
  let retries=0,splits=0,saveTimer,timer;
  q('chunkGrid').innerHTML=status.map(s=>'<i class="'+s+'"></i>').join('');
  const cells=q('chunkGrid').children;
  const progress=()=>{
    const now=performance.now(),avg=spent.length?spent.reduce((x,y)=>x+y,0)/spent.length:45000;
    let partial=0;for(const t of begun.values())partial+=Math.min(.92,(now-t)/avg*.92);
    const done=current.checkpoint.size;
    return{done,total,ratio:Math.min(.995,(done+partial)/total),running:begun.size,elapsed:(now-started)/1000,
      eta:done>startDone||begun.size?Math.max(0,((total-done)*avg/1000)/Math.max(1,Math.min(cfg.conc||3,total-done))-(begun.size?Math.min(avg,(now-[...begun.values()][0]))/1000:0)):null};
  };
  const paint=()=>{
    if(!live())return;
    const p=progress(),pct=Math.floor(p.ratio*100);
    q('pillText').textContent=pct+'%';
    q('pillArc').style.strokeDashoffset=String(50.3*(1-p.ratio));
    ringShow('model',{ratio:p.ratio,pct,line1:p.total>1?'已完成 '+p.done+' / '+p.total+' 段':'模型正在阅读全文',line2:'已用 '+clock(p.elapsed)+(p.eta!==null&&p.eta>5?' · 约剩 '+clock(p.eta):''),note:(retries?'已自动重试 '+retries+' 次':'')+(splits?(retries?' · ':'')+'拆小 '+splits+' 次':'')});
    q('progLine').innerHTML=pct+'%<small>'+fmt(p.done)+' / '+fmt(p.total)+' 段 · 已用 '+clock(p.elapsed)+'</small>';
    q('progNote').textContent='并发 '+(cfg.conc||3)+(retries?' · 自动重试 '+retries+' 次':'')+(splits?' · 输出过长时拆小 '+splits+' 次':'')+' · 完成的段落即时保存，中断后可续跑';
  };
  q('modelPill').hidden=false;syncActions();paint();timer=setInterval(paint,500);
  setStatus('正在用 '+cfg.model+' 分析本书，已完成的段落会即时保存。');
  const persist=()=>{clearTimeout(saveTimer);saveTimer=setTimeout(()=>store.set(key,[...current.checkpoint]),400);};
  try{
    await prefetch(parts,cfg,current.localPeople,current.checkpoint,ctl.signal,(i,s)=>{
      if(s==='run'){begun.set(i,performance.now());cells[i].className='run';}
      else if(s==='ok'){spent.push(performance.now()-(begun.get(i)||performance.now()));begun.delete(i);cells[i].className='ok';}
      else if(s==='bad'){begun.delete(i);cells[i].className='bad';}
      else if(s==='retry')retries++;
      else if(s==='split')splits++;
      paint();
    },persist);
    clearTimeout(saveTimer);await store.set(key,[...current.checkpoint]);
    if(!live())return;
    ringShow('model',{ratio:.995,pct:99,line1:'正在合并档案',line2:'核对原文与称呼'});
    await sleep(30);
    const result=await analyzeModel(current.lines,cfg,()=>{},current.localPeople,current.checkpoint);
    if(!result.length){current.checkpoint.clear();await store.del(key);throw new Error('模型没有返回可在原文中定位的人物');}
    const selected=current.selected;
    current.people=enrichModelCards(result,current.localPeople,current.lines);
    for(const p of current.people){p.customTags=savedTags(current.bookKey,p.name);p.titles=[];}
    localTitles(current.people,current.lines);
    modelTitles(current.people,parts,current.checkpoint);
    for(const p of current.people)p.titles.sort((x,y)=>(y.src==='model')-(x.src==='model')||y.lines.length-x.lines.length);
    current.selected=current.people.find(p=>p.name===selected||p.aliases?.includes(selected))?.name||current.people[0].name;
    current.engine='model';current.checkpoint.clear();
    await store.set('res|'+current.bookKey,{people:current.people,model:cfg.model,at:Date.now()});
    await store.del(key);
    if(state===current){graphCache=null;render();}
    if(q('progressDialog').open)q('progressDialog').close();
    if(live()){
      ringShow('done',{ratio:1,pct:100,line1:'已合入 '+current.people.length+' 位角色',line2:cfg.model+' · 用时 '+clock((performance.now()-started)/1000)});
      setStatus('已将 '+cfg.model+' 的分析合入本书，共 '+current.people.length+' 位角色。');
      setTimeout(()=>{if(!modelCtl&&ringMode==='done')ringHide();},4200);
    }
  }catch(err){
    clearTimeout(saveTimer);store.set(key,[...current.checkpoint]);
    if(!live()||ctl.superseded)return;
    if(err.name==='AbortError'){ringHide();setStatus('已停止。完成的 '+current.checkpoint.size+' 段已保存，下次从断点继续。');}
    else{
      const msg=(err.chunk&&total>1?'第 '+err.chunk+' 段：':'')+(err.chunk?err.message:friendlyError(err));
      ringShow('error',{ratio:current.checkpoint.size/total,pct:Math.floor(current.checkpoint.size/total*100),line1:'分析中断',line2:msg});
      setStatus(msg+'。已完成的段落已保存，可点「继续」从断点接着分析。',true);
    }
  }finally{
    clearInterval(timer);
    if(modelCtl===ctl){modelCtl=null;q('modelPill').hidden=true;syncActions();}
  }
}
function clock(sec){sec=Math.round(sec);const m=Math.floor(sec/60),s=sec%60;return m?(m+' 分 '+String(s).padStart(2,'0')+' 秒'):s+' 秒';}
/* 进度圈：导入框原位变形 */
let ringMode='';
const RING_C=2*Math.PI*52;
function ringShow(mode,o){
  ringMode=mode;
  const box=q('dropbox');box.classList.add('running');box.dataset.mode=mode;
  q('ringFill').style.strokeDashoffset=String(RING_C*(1-(o.ratio??0)));
  q('ringPct').textContent=mode==='local'?'':(o.pct??0)+(mode==='done'?'':'%');
  q('ringLine1').textContent=o.line1||'';q('ringLine2').textContent=o.line2||'';q('ringNote').textContent=o.note||'';
  q('ringStop').hidden=mode!=='model';q('ringResume').hidden=mode!=='error';q('ringClose').hidden=mode!=='error';
}
function ringHide(){ringMode='';const box=q('dropbox');box.classList.remove('running');delete box.dataset.mode;}
q('ringStop').onclick=()=>{if(!modelCtl)return;modelCtl.abort();ringShow('model',{ratio:0,pct:0,line1:'正在停止',line2:'保存已完成的段落'});q('ringStop').hidden=true;};
q('ringResume').onclick=()=>{ringHide();runBookModel(true);};
q('ringClose').onclick=ringHide;
q('ringDetail').onclick=()=>{if(modelCtl)q('progressDialog').showModal();};
q('modelPill').onclick=()=>q('progressDialog').showModal();
q('cancelModel').onclick=()=>{modelCtl?.abort();q('progressDialog').close();};
q('confirmRun').onclick=()=>{q('confirmDialog').close();runBookModel(true);q('dropbox').scrollIntoView({block:'center',behavior:'smooth'});};
q('runBookModel').onclick=()=>runBookModel(false);

/* ---------- 目录 ---------- */
function listPeople(){
  const term=q('search').value.trim().toLowerCase();
  q('count').textContent=state.people.length+' 位';
  const max=Math.max(1,...state.people.map(p=>p.mentions||0));
  const visible=state.people.filter(p=>[p.name,...(p.aliases||[]),...(p.titles||[]).map(t=>t.title),...(p.customTags||[])].some(n=>n.toLowerCase().includes(term)));
  q('people').innerHTML=visible.map(p=>{
    const on=p.name===state.selected;
    const sub=unique([...(p.aliases||[]).filter(a=>a!==p.name),...(p.titles||[]).map(t=>t.title),...(p.customTags||[])]).slice(0,2).join(' · ')||p.relations.length+' 个关系对象';
    return '<button class="person'+(on?' active':'')+'" data-person="'+html(p.name)+'" aria-label="'+html(p.name)+'" title="'+html(p.name)+'" aria-current="'+on+'"><span class="avatar" aria-hidden="true">'+html([...p.name][0])+'</span><span class="person-info"><b>'+html(p.name)+'</b><small>'+html(sub)+'</small></span><span class="heat"><i style="width:'+Math.max(10,Math.round((p.mentions||0)/max*100))+'%"></i></span></button>';
  }).join('')||'<div class="empty">'+(term?'没有匹配的角色':'等待识别角色')+'</div>';
}
function select(name,tab='card'){
  state.selected=name;state.tab=tab;state.target=0;state.sceneLimits.clear();state.showAllTitles='';
  if(matchMedia('(max-width:760px)').matches)setDir(false);
  render(true);
  const top=q('view').closest('.panel').getBoundingClientRect().top;
  if(tab==='card'&&top<0)window.scrollBy({top:top-8,behavior:'smooth'});
}

/* ---------- 渲染 ---------- */
function render(animate=false){
  listPeople();
  q('book').textContent='《'+state.title+'》'+(state.sample?' · 示例':'');
  const chapters=unique(state.chapters.filter(Boolean)).length;
  q('stats').textContent=fmt(state.size)+' 字符'+(chapters?' · '+chapters+' 章':'')+' · '+(state.engine==='local'?'本地提取':'模型提取');
  syncActions();
  document.querySelectorAll('[data-tab]').forEach(b=>b.setAttribute('aria-selected',String(b.dataset.tab===state.tab)));
  const v=q('view');
  if(state.tab==='raw')renderRaw();
  else if(state.tab==='graph')renderGraph();
  else renderCard();
  if(animate){v.classList.remove('enter');void v.offsetWidth;v.classList.add('enter');}
}
const sure=f=>f.basis==='原文明示';
const basisTag=f=>'<span class="basis'+(sure(f)?' sure':'')+'">'+html(f.basis==='原文明示'?'原文明示':f.basis)+'</span>';
function refBtn(lines,title,label){
  const list=unique((lines||[]).filter(n=>Number.isInteger(n)&&n>0));
  if(!list.length)return '';
  return '<button class="ref" data-refs="'+list.join(',')+'" data-title="'+html(title)+'">'+(label||'原文'+(list.length>1?' '+list.length:''))+'</button>';
}
function srcLinks(lines,title){
  const list=unique((lines||[]).filter(n=>Number.isInteger(n)&&n>0)).slice(0,10);
  return list.length?'<span class="field-sources">'+list.map(n=>'<button class="source-link" data-refs="'+n+'" data-title="'+html(title)+'">'+html(sourceLabel(n))+'</button>').join('')+'</span>':'';
}
function detailBlock(text){
  if(!text)return '';
  const long=text.length>220||text.split('\n').length>4;
  return '<p class="detail'+(long?' clamp':'')+'">'+html(text)+'</p>'+(long?'<button class="more-text" data-expand>展开全文</button>':'');
}
function sceneGroups(scenes){
  const groups=[];
  for(const item of scenes){
    const group=groups.at(-1),previous=group?.at(-1);
    const sameChapter=state.chapters[item.line-1]===state.chapters[previous?.line-1];
    if(previous&&sameChapter&&item.line-previous.line<=3)group.push(item);else groups.push([item]);
  }
  return groups;
}
function sayLine(s,title){
  return '<div class="say"><span class="who'+(s.speaker?'':' anon')+'">'+html(s.speaker||'—')+'</span><div>'+(s.context?'<span class="ctx">'+html(s.context)+'</span>':'')+'<q>'+html(s.quote)+'</q><button class="source-link" data-refs="'+s.line+'" data-title="'+html(title)+'">第 '+s.line+' 行</button></div></div>';
}
function scenesHTML(scenes,title){
  return sceneGroups(scenes).map(g=>{
    const a=g[0].line,b=g.at(-1).line;
    return '<div class="scene-head">'+html((state.chapters[a-1]||'')+(state.chapters[a-1]?' · ':'')+(a===b?'第 '+a+' 行':'第 '+a+'–'+b+' 行'))+'</div>'+g.map(s=>sayLine(s,title)).join('');
  }).join('');
}
function relationHTML(p,r,open){
  const shown=state.sceneLimits.get(r.to)||12;
  const title=p.name+' → '+r.to;
  const att=r.attitudes.slice(0,shown).map(a=>'<li><div class="v">'+html(a.value)+'<span class="basis'+(sure(a)?' sure':'')+'">'+html(a.basis)+'</span></div>'+detailBlock(a.detail)+srcLinks(a.sources||[a.line],title)+'</li>').join('');
  const more=Math.max(r.scenes.length,r.attitudes.length)>shown?'<button class="btn" data-more-scenes="'+html(r.to)+'">查看更多互动</button>':'';
  const fallback=r.scenes.length?'可从下列对白查看这段互动，当前依据不足以概括该方态度。':'目前只有关系线索，缺少可归属到该方的态度依据。';
  return '<details class="rel" data-relation="'+html(r.to)+'"'+(open?' open':'')+'><summary><span class="to">'+html(r.to)+'</span><span class="kin">'+html(r.type)+'</span><span class="n">'+r.scenes.length+' 句对白</span>'+chev+'</summary>'+
    '<div class="rel-body"><h3>对'+html(r.to)+'的态度</h3>'+(att?'<ol class="timeline">'+att+'</ol>':'<p class="muted">'+fallback+'</p>')+
    '<h3>对白场景再现</h3>'+(r.scenes.length?'<div class="script">'+scenesHTML(r.scenes.slice(0,shown),title)+'</div>':'<p class="muted">暂未定位到这两人的对白。</p>')+
    '<div class="rel-foot">'+more+'<button class="btn" data-person="'+html(r.to)+'">查看'+html(r.to)+'的角色卡</button></div></div></details>';
}
const namesForm='<form class="names-form" id="namesForm"><input class="field" aria-label="人物姓名" placeholder="姓名之间用顿号分隔" required><button class="btn primary">重新提取</button></form>';
function renderCard(){
  const p=state.people.find(x=>x.name===state.selected);
  if(!p){q('view').innerHTML='<div class="empty"><h2>还没认出人物</h2><p>正文已读取。可以补充原文里的姓名或称呼。</p>'+namesForm+'</div>'+renderUnassigned();return;}
  const aliases=unique(p.aliases||[]).filter(a=>a&&a!==p.name).map(a=>'<button class="chip" data-alias="'+html(a)+'">'+html(a)+'</button>').join('');
  const known=new Set([p.name,...(p.aliases||[])]);
  const titles=(p.titles||[]).filter(t=>!known.has(t.title)).slice(0,state.showAllTitles===p.name?99:8);
  const moreTitles=(p.titles||[]).length>8&&state.showAllTitles!==p.name?'<button class="chip add more" data-all-titles>+'+((p.titles||[]).length-8)+'</button>':'';
  const titleChips=titles.map(t=>'<button class="chip title" data-title-chip="'+html(t.title)+'" title="'+html((t.by.length?t.by.join('、')+'这样称呼':'原文称呼'))+'">'+html(t.title)+'</button>').join('')+moreTitles;
  const tags=(p.customTags||[]).filter(a=>!(p.aliases||[]).includes(a)).map(a=>'<button class="chip tag" data-manual="'+html(a)+'">'+html(a)+'</button>').join('');
  const facts=['gender','age','height'].map(k=>{
    const f=p.fields[k];
    return '<div class="fact"><dt>'+labels[k]+'</dt><dd'+(f?'':' class="none"')+'>'+(f?html(f.value)+refBtn(f.sources||[f.line],p.name+' · '+labels[k])+'<br>'+basisTag(f):unknown)+'</dd></div>';
  }).join('');
  const notes=['background','build','personality'].map(k=>{
    const f=p.fields[k];
    const none=k==='personality'?'暂未归纳出稳定性格，可查看下方出场与对白。':unknown;
    return '<div class="note"><b>'+labels[k]+(f&&!sure(f)?basisTag(f):'')+'</b>'+(f?'<p class="lead">'+html(f.value)+'</p>'+detailBlock(f.detail)+srcLinks(f.sources||[f.line],p.name+' · '+labels[k]):'<p class="lead none">'+none+'</p>')+'</div>';
  }).join('');
  const evidence=['gender','age','height'].filter(k=>p.fields[k]).map(k=>{const f=p.fields[k];return '<li>'+labels[k]+' · '+html(f.basis)+detailBlock(f.detail)+srcLinks(f.sources||[f.line],p.name+' · '+labels[k])+'</li>';}).join('');
  const rels=p.relations.map((r,i)=>relationHTML(p,r,i===0||state.sceneLimits.has(r.to))).join('');
  const soloLimit=state.sceneLimits.get('')||12;
  const solo=p.soloScenes?.length?'<section class="block"><div class="block-head"><h2>人物对白</h2><span class="count">'+p.soloScenes.length+' 句</span></div><div class="script">'+scenesHTML(p.soloScenes.slice(0,soloLimit),p.name)+'</div>'+(p.soloScenes.length>soloLimit?'<div class="rel-foot"><button class="btn" data-more-solo>查看更多人物对白</button></div>':'')+'</section>':'';
  const clueLines=(p.mentionsAt||[]).filter(n=>state.lines[n-1]?.trim()).slice(0,4);
  q('view').innerHTML='<div class="profile"><span class="avatar big" aria-hidden="true">'+html([...p.name][0])+'</span><div class="identity"><div class="identity-line"><h2>'+html(p.name)+'</h2>'+aliases+titleChips+tags+'<button class="chip add" data-add-tag aria-label="添加自定义标签" title="添加自定义标签">+</button></div><p>'+fmt(p.mentions||0)+' 行提及 · '+p.relations.length+' 个关系对象</p></div></div>'+
    '<dl class="facts">'+facts+'</dl><div class="notes">'+notes+'</div>'+
    (evidence?'<details class="evidence"><summary>查看字段依据</summary><ul>'+evidence+'</ul></details>':'')+
    '<section class="block"><div class="block-head"><h2>人物关系与对白</h2><span class="count">'+p.relations.length+' 个关系对象</span></div>'+(rels||'<p class="muted">暂未找到可以关联的人物互动。</p>')+'</section>'+solo+
    (clueLines.length?'<details class="evidence"><summary>人物出场原文</summary>'+clueLines.map(n=>'<p class="excerpt">'+html(state.lines[n-1].trim().slice(0,420))+'</p>'+srcLinks([n],p.name+' · 出场')).join('<br>')+'</details>':'')+
    (state.engine==='local'&&!state.sample?'<details class="evidence"><summary>补充漏掉的姓名</summary>'+namesForm+'</details>':'');
}
function renderUnassigned(){
  const out=[];
  for(const [i,line] of state.lines.entries()){
    if(out.length>=5)break;
    if(!line.trim()||state.chapters[i]===line.trim())continue;
    const m=[...line.slice(0,1200).matchAll(speech)][0];
    out.push(m?sayLine({quote:m[1],context:line.slice(0,m.index).trim(),line:i+1},'线索'):'<p class="excerpt">'+html(line.trim().slice(0,420))+'</p>');
  }
  return out.length?'<section class="block"><div class="block-head"><h2>暂未归属的人物线索</h2></div><div class="script">'+out.join('')+'</div></section>':'';
}

/* ---------- 关系网 ---------- */
function graphModel(){
  const view=q('view'),pad=parseFloat(getComputedStyle(view).paddingLeft)||0;
  const W=Math.round(Math.max(300,view.clientWidth-pad*2)),H=Math.round(matchMedia('(max-width:860px)').matches?innerHeight*.62:Math.min(innerHeight*.68,640));
  if(graphCache&&graphCache.W===W&&graphCache.H===H)return graphCache;
  const byName=new Map(state.people.map(p=>[p.name,p]));
  const linked=state.people.filter(p=>p.relations.length||state.people.some(o=>o.relations.some(r=>r.to===p.name)));
  const pick=linked.slice(0,W<600?24:42);
  if(state.selected&&!pick.some(p=>p.name===state.selected)&&byName.has(state.selected))pick.push(byName.get(state.selected));
  const idx=new Map(pick.map((p,i)=>[p.name,i]));
  const max=Math.max(1,...pick.map(p=>p.mentions||0));
  const nodes=pick.map((p,i)=>{const a=i*2.399963,r=30+Math.sqrt(i)*40;return{name:p.name,r:7+13*Math.sqrt((p.mentions||0)/max),x:W/2+Math.cos(a)*r,y:H/2+Math.sin(a)*r,vx:0,vy:0};});
  const edges=[];
  for(const p of pick)for(const r of p.relations)if(idx.has(r.to))edges.push({s:idx.get(p.name),t:idx.get(r.to),type:r.type,last:r.attitudes.at(-1)?.value||''});
  const k=Math.sqrt(W*H/Math.max(nodes.length,1))*.55;
  for(let it=0;it<420;it++){
    const temp=12*(1-it/420)+.5;
    for(const n of nodes){n.vx=0;n.vy=0;}
    for(let i=0;i<nodes.length;i++)for(let j=i+1;j<nodes.length;j++){
      const a=nodes[i],b=nodes[j];let dx=a.x-b.x,dy=a.y-b.y,d=Math.hypot(dx,dy)||.01;
      const f=k*k/d/d;dx/=d;dy/=d;a.vx+=dx*f;a.vy+=dy*f;b.vx-=dx*f;b.vy-=dy*f;
    }
    for(const e of edges){
      const a=nodes[e.s],b=nodes[e.t];let dx=b.x-a.x,dy=b.y-a.y,d=Math.hypot(dx,dy)||.01;
      const f=d/k*.9;dx/=d;dy/=d;a.vx+=dx*f;a.vy+=dy*f;b.vx-=dx*f;b.vy-=dy*f;
    }
    for(const n of nodes){
      n.vx+=(W/2-n.x)*.012;n.vy+=(H/2-n.y)*.012;
      const m=Math.hypot(n.vx,n.vy)||1,s=Math.min(m,temp);
      n.x+=n.vx/m*s;n.y+=n.vy/m*s;
    }
  }
  const xs=nodes.map(n=>n.x),ys=nodes.map(n=>n.y);
  const x0=Math.min(...xs),x1=Math.max(...xs),y0=Math.min(...ys),y1=Math.max(...ys);
  const mx=Math.min(70,W*.12),my=50;
  const s=Math.min((W-2*mx)/Math.max(x1-x0,1),(H-2*my)/Math.max(y1-y0,1),nodes.length<6?2.2:6);
  for(const n of nodes){n.x=W/2+(n.x-(x0+x1)/2)*s;n.y=H/2-8+(n.y-(y0+y1)/2)*s;}
  return graphCache={W,H,nodes,edges};
}
function edgePath(a,b,bend=.18){
  const mx=(a.x+b.x)/2,my=(a.y+b.y)/2,dx=b.x-a.x,dy=b.y-a.y,d=Math.hypot(dx,dy)||1;
  const cx=mx-dy*bend,cy=my+dx*bend;
  const ex=b.x-(b.x-cx)/Math.hypot(b.x-cx,b.y-cy)*(b.r+4),ey=b.y-(b.y-cy)/Math.hypot(b.x-cx,b.y-cy)*(b.r+4);
  return{d:'M'+a.x.toFixed(1)+' '+a.y.toFixed(1)+'Q'+cx.toFixed(1)+' '+cy.toFixed(1)+' '+ex.toFixed(1)+' '+ey.toFixed(1),lx:(mx+cx)/2,ly:(my+cy)/2};
}
function renderGraph(){
  const g=graphModel();
  if(!g.edges.length){q('view').innerHTML='<div class="empty"><h2>还没有关系可画</h2><p>精读后，人物之间的往来会出现在这里。</p></div>';return;}
  const marker=(id,color)=>'<marker id="'+id+'" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 1L9 5L0 9z" style="fill:'+color+'"/></marker>';
  const edges=g.edges.map((e,i)=>{const p=edgePath(g.nodes[e.s],g.nodes[e.t]);return '<path class="g-edge" data-e="'+i+'" d="'+p.d+'" marker-end="url(#ah)"><title>'+html(g.nodes[e.s].name+' → '+g.nodes[e.t].name+' · '+e.type+(e.last?' · '+e.last:''))+'</title></path>';}).join('');
  const labelsSvg=g.edges.map((e,i)=>{const p=edgePath(g.nodes[e.s],g.nodes[e.t]);return '<text class="g-label" data-l="'+i+'" x="'+p.lx.toFixed(1)+'" y="'+p.ly.toFixed(1)+'">'+html(e.type)+'</text>';}).join('');
  const nodes=g.nodes.map((n,i)=>'<g class="g-node" data-n="'+i+'" transform="translate('+n.x.toFixed(1)+' '+n.y.toFixed(1)+')" tabindex="0" role="button" aria-label="'+html(n.name)+'"><circle r="'+n.r.toFixed(1)+'"/><text y="'+(n.r+16).toFixed(1)+'">'+html(n.name)+'</text></g>').join('');
  q('view').innerHTML='<div class="graph-wrap" id="graphWrap"><svg viewBox="0 0 '+g.W+' '+g.H+'" id="graphSvg"><defs>'+marker('ah','var(--line2)')+marker('ahot','var(--zhu)')+'</defs><g id="gEdges">'+edges+'</g><g>'+labelsSvg+'</g><g id="gNodes">'+nodes+'</g></svg><span class="graph-legend">拖动人物调整位置 · 点选查看往来</span></div><div class="graph-side" id="graphSide"></div>';
  focusGraph(state.selected);
  wireGraph(g);
}
function focusGraph(name){
  const g=graphModel(),wrap=q('graphWrap');if(!wrap)return;
  const i=g.nodes.findIndex(n=>n.name===name);
  wrap.classList.toggle('focus',i>=0);
  const hotN=new Set([i]);
  g.edges.forEach((e,k)=>{
    const hot=e.s===i||e.t===i;if(hot){hotN.add(e.s);hotN.add(e.t);}
    const el=wrap.querySelector('[data-e="'+k+'"]');el.classList.toggle('hot',hot);el.setAttribute('marker-end',hot?'url(#ahot)':'url(#ah)');
    wrap.querySelector('[data-l="'+k+'"]').classList.toggle('hot',hot);
  });
  wrap.querySelectorAll('.g-node').forEach(el=>{const n=+el.dataset.n;el.classList.toggle('hot',hotN.has(n));el.classList.toggle('sel',n===i);});
  const p=state.people.find(x=>x.name===name);
  q('graphSide').innerHTML=p?'<button class="gcard" data-person="'+html(p.name)+'" style="border-color:var(--zhu)"><b>'+html(p.name)+'</b><small>打开角色卡 →</small></button>'+p.relations.map(r=>'<button class="gcard" data-gfocus="'+html(r.to)+'"><b>'+html(r.to)+'</b> <span class="kin">'+html(r.type)+'</span><small>'+html(r.attitudes.at(-1)?.value||(r.scenes.length?r.scenes.length+' 句对白':'同场出现'))+'</small></button>').join(''):'';
}
function wireGraph(g){
  const svg=q('graphSvg');let drag=null,moved=false;
  const pt=e=>{const r=svg.getBoundingClientRect(),s=Math.max(g.W/r.width,g.H/r.height);return{x:(e.clientX-r.left)*s-(r.width*s-g.W)/2,y:(e.clientY-r.top)*s-(r.height*s-g.H)/2};};
  svg.addEventListener('pointerdown',e=>{const el=e.target.closest('.g-node');if(!el)return;drag=+el.dataset.n;moved=false;svg.setPointerCapture(e.pointerId);});
  svg.addEventListener('pointermove',e=>{
    if(drag===null)return;moved=true;
    const n=g.nodes[drag],p=pt(e);n.x=Math.min(g.W-20,Math.max(20,p.x));n.y=Math.min(g.H-20,Math.max(20,p.y));
    svg.querySelector('[data-n="'+drag+'"]').setAttribute('transform','translate('+n.x.toFixed(1)+' '+n.y.toFixed(1)+')');
    g.edges.forEach((e2,k)=>{if(e2.s!==drag&&e2.t!==drag)return;const ep=edgePath(g.nodes[e2.s],g.nodes[e2.t]);svg.querySelector('[data-e="'+k+'"]').setAttribute('d',ep.d);const l=svg.querySelector('[data-l="'+k+'"]');l.setAttribute('x',ep.lx.toFixed(1));l.setAttribute('y',ep.ly.toFixed(1));});
  });
  const up=e=>{
    if(drag===null)return;const n=drag;drag=null;
    if(!moved){state.selected=g.nodes[n].name;listPeople();focusGraph(state.selected);}
  };
  svg.addEventListener('pointerup',up);svg.addEventListener('pointercancel',()=>drag=null);
  svg.addEventListener('dblclick',e=>{const el=e.target.closest('.g-node');if(el)select(g.nodes[+el.dataset.n].name);});
  svg.addEventListener('keydown',e=>{const el=e.target.closest('.g-node');if(el&&(e.key==='Enter'||e.key===' ')){e.preventDefault();state.selected=g.nodes[+el.dataset.n].name;listPeople();focusGraph(state.selected);}});
}

/* ---------- 原文 ---------- */
function chapterStarts(){
  const out=[];let last=null;
  state.chapters.forEach((c,i)=>{if(c&&c!==last){out.push([c,i+1]);last=c;}});
  return out;
}
function renderRaw(){
  const total=state.lines.length,target=state.target||1;
  const start=Math.max(1,target-30),end=Math.min(total,start+179);
  const p=state.people.find(x=>x.name===state.selected);
  const names=p?unique([p.name,...(p.aliases||[])]).filter(Boolean).sort((a,b)=>b.length-a.length).map(n=>html(n).replace(/[.*+?^${}()|[\]\\]/g,'\\$&')):[];
  const rx=names.length?new RegExp(names.join('|'),'gu'):null;
  const chaps=chapterStarts(),cur=state.chapters[target-1];
  let body='';
  for(let n=start;n<=end;n++){
    const t=state.lines[n-1];if(!t?.trim())continue;
    let h=html(t.trim());if(rx)h=h.replace(rx,'<mark>$&</mark>');
    const chap=state.chapters[n-1]&&state.chapters[n-1]===t.trim();
    body+='<p id="L'+n+'" class="'+(chap?'chap':'')+(n===state.flash?' focus':'')+'"><span class="ln">'+n+'</span>'+h+'</p>';
  }
  q('view').innerHTML='<div class="raw-bar">'+(chaps.length?'<select class="field" id="chapterPick" aria-label="章节">'+chaps.map(([c,l])=>'<option value="'+l+'"'+(c===cur?' selected':'')+'>'+html(c)+'</option>').join('')+'</select>':'')+'<span class="muted">第 '+fmt(start)+'–'+fmt(end)+' 行 / '+fmt(total)+'</span></div><div class="raw">'+body+'</div><div class="raw-nav">'+(start>1?'<button class="btn" data-line="'+Math.max(1,start-150)+'" data-quiet>← 上文</button>':'')+(end<total?'<button class="btn" data-line="'+(end+31)+'" data-quiet>下文 →</button>':'')+'</div>';
  const pick=q('chapterPick');if(pick)pick.onchange=()=>{state.target=+pick.value;state.flash=0;renderRaw();};
  const f=state.flash&&q('L'+state.flash);if(f)requestAnimationFrame(()=>f.scrollIntoView({block:'center'}));
}

/* ---------- 原文出处抽屉 ---------- */
function neighbor(n,dir){for(let i=n-1+dir,c=0;i>=0&&i<state.lines.length&&c<6;i+=dir,c++)if(state.lines[i].trim())return i+1;return 0;}
function openProofs(lines,title){
  const p=state.people.find(x=>x.name===state.selected);
  const names=p?unique([p.name,...(p.aliases||[]),...(p.titles||[]).map(t=>t.title)]).filter(Boolean).sort((a,b)=>b.length-a.length):[];
  const mark=t=>{let h=html(t.length>700?t.slice(0,700)+'…':t);for(const n of names)h=h.split(html(n)).join('<mark>'+html(n)+'</mark>');return h;};
  q('drawerTitle').textContent=title;
  const extraN=Math.max(0,lines.length-40);lines=lines.slice(0,40);
  q('drawerBody').innerHTML=lines.map(n=>{
    const a=neighbor(n,-1),b=neighbor(n,1);
    return '<div class="proof"><div class="loc"><span>'+html(sourceLabel(n))+'</span><button data-line="'+n+'">在原文中读 →</button></div>'+(a?'<p>'+mark(state.lines[a-1].trim())+'</p>':'')+'<p class="hit">'+mark(state.lines[n-1]?.trim()||'')+'</p>'+(b?'<p>'+mark(state.lines[b-1].trim())+'</p>':'')+'</div>';
  }).join('')+(extraN?'<p class="muted" style="padding:16px 0">另有 '+extraN+' 处出处，导出时会全部列出。</p>':'');
  q('drawer').hidden=false;q('drawerBody').scrollTop=0;
  if(matchMedia('(max-width:760px)').matches)q('scrim').hidden=false;
  q('drawer').querySelector('[data-close-drawer]').focus({preventScroll:true});
}
function closeDrawer(){q('drawer').hidden=true;q('scrim').hidden=true;}
function setDir(open){
  q('shell').classList.toggle('expanded',open);
  const t=q('directoryToggle');t.setAttribute('aria-expanded',String(open));
  t.title=t.ariaLabel=open?'收起角色目录':'展开角色目录';
  if(!open&&q('search').value){q('search').value='';listPeople();}
  if(open)setTimeout(()=>q('search').focus({preventScroll:true}),200);
}
/* ---------- 导出 ---------- */
function exportCards(){
  const lines=['# '+state.title+' · 小说分析'];
  for(const p of state.people){
    lines.push('','## '+p.name,'');
    if(p.customTags?.length)lines.push('自定义标签　'+p.customTags.join('、'),'');
    if(p.titles?.length){for(const t of p.titles)lines.push('剧情称呼　'+t.title+'（'+(t.by.length?t.by.join('、')+'；':'')+t.lines.slice(0,3).map(sourceLabel).join('；')+'）');lines.push('');}
    if(p.aliases?.length)for(const a of p.aliases){const refs=unique((p.aliasEvidence||[]).filter(x=>x.alias===a).map(x=>x.line));lines.push('称呼　'+a+(refs.length?'（'+refs.slice(0,3).map(sourceLabel).join('；')+'）':''));}
    for(const [k,l] of Object.entries(labels)){
      const f=p.fields[k];
      lines.push('- '+l+'　'+(k==='name'?p.name:f?f.value+'（'+f.basis+'，'+f.sources.map(sourceLabel).join('；')+'）':unknown));
      if(f?.detail)lines.push(...f.detail.split('\n').map(d=>'  > '+d));
    }
    if(p.soloScenes?.length){lines.push('','### 人物对白');for(const sc of p.soloScenes)lines.push('> '+sc.quote+'（'+sourceLabel(sc.line)+'）');}
    for(const r of p.relations){
      lines.push('','### '+r.to+'（'+r.type+'）','','**对'+r.to+'的态度**');
      lines.push(...(r.attitudes.length?r.attitudes.flatMap(a=>['- '+a.value+'（'+a.basis+'，'+a.sources.map(sourceLabel).join('；')+'）',...(a.detail?a.detail.split('\n').map(d=>'  > '+d):[])]):['- 原文态度暂未提取']));
      lines.push('','**对白场景再现**');
      lines.push(...(r.scenes.length?r.scenes.flatMap(s=>[(s.context||'')+'（'+sourceLabel(s.line)+'）','> '+(s.speaker?s.speaker+'　':'')+s.quote]):['- 暂未提取到相关对白']));
    }
  }
  const href=URL.createObjectURL(new Blob([lines.join('\n')],{type:'text/markdown;charset=utf-8'}));
  const link=document.createElement('a');link.href=href;link.download=state.title+'-小说分析.md';link.click();
  setTimeout(()=>URL.revokeObjectURL(href),3000);
}

/* ---------- 模型设置 ---------- */
const providerMenu=q('provider'),modelMenu=q('model');
for(const [key,[label]] of Object.entries(providers))providerMenu.add(new Option(label,key));
let operation=null;
function cancelApi(){operation?.abort();operation=null;}
function statusApi(st,message){
  q('testApi').dataset.state=st;
  q('testLabel').textContent={loading:'正在测试连接…',success:'连接成功',error:'重新测试连接'}[st]||'测试 API 连接';
  q('apiStatus').textContent=message;q('apiStatus').dataset.state=st;
}
function invalidateApi(){
  cancelApi();modelMenu.replaceChildren();q('modelRow').hidden=true;
  modelMenu.disabled=false;q('testApi').disabled=false;q('saveApi').disabled=providerMenu.value!=='local';statusApi('idle','');
}
function setProvider(){
  invalidateApi();
  const preset=providers[providerMenu.value];
  q('onlineFields').hidden=!preset;
  q('protocolRow').hidden=providerMenu.value!=='custom';
  q('endpoint').readOnly=!!preset&&providerMenu.value!=='custom';
  q('endpoint').value=preset?.[1]||'';q('protocol').value=preset?.[2]||'chat';
  q('token').value='';q('outTokens').value='';q('temp').value='';q('concurrency').value='';
}
function draftApi(){
  if(!q('token').value.trim())throw new Error('请先填写 API 密钥。');
  let endpoint;
  try{endpoint=apiAddress(q('endpoint').value);}catch{throw new Error('请填写有效的 HTTPS API 地址。');}
  const max=q('outTokens').value,temp=q('temp').value,conc=q('concurrency').value;
  if(max&&(!Number.isSafeInteger(Number(max))||Number(max)<1))throw new Error('输出上限应为正整数。');
  if(temp&&(Number(temp)<0||Number(temp)>(q('protocol').value==='claude'?1:2)))throw new Error('温度超出当前协议范围。');
  return {max:max?Number(max):undefined,temp:temp!==''?Number(temp):undefined,conc:Math.min(8,Math.max(1,Math.round(Number(conc)||3))),provider:providerMenu.value,protocol:q('protocol').value,endpoint,token:q('token').value.trim(),model:modelMenu.value,longModel:q('longModel').checked};
}
function listApi(ids,selected){
  modelMenu.replaceChildren(...ids.map(id=>new Option(id,id)));
  if(ids.includes(selected))modelMenu.value=selected;
  q('modelRow').hidden=false;q('saveApi').disabled=false;
}
let cspBlock=0;
document.addEventListener('securitypolicyviolation',e=>{if(e.effectiveDirective==='connect-src'||/connect-src/.test(e.violatedDirective||''))cspBlock=Date.now();});
function apiError(err,signal){
  if(signal.aborted)return '请求超时，请再试一次。';
  if(err instanceof TypeError&&Date.now()-cspBlock<5000)return '当前页面的安全策略禁止连接外部接口（预览沙盒的限制，不是地址或密钥的问题）。部署到自己的站点后即可使用。';
  return err instanceof TypeError?'浏览器无法连接该地址，请检查网络、地址和供应商的跨域设置。':err.message;
}
function updateEngine(){
  q('mode').textContent='TXT / Markdown · 支持 6 MB · '+(cfg?'模型提取':'本地提取');
  q('engineDot').classList.toggle('on',!!cfg);
  q('settings').title=cfg?'已接入 '+cfg.model:'未接入模型';
  syncActions();
}
function openSettings(){
  if(!analyzeAfterSettings)q('saveApi').textContent='保存';else q('saveApi').textContent='保存并分析本书';
  providerMenu.value=cfg?.provider||'local';setProvider();
  q('longModel').checked=cfg?!!cfg.longModel:true;
  if(cfg){
    q('protocol').value=cfg.protocol;q('endpoint').value=cfg.endpoint;q('token').value=cfg.token;
    q('outTokens').value=cfg.max??'';q('temp').value=cfg.temp??'';q('concurrency').value=cfg.conc??'';
    listApi(cfg.models,cfg.model);statusApi('success','已验证当前模型。');
  }
  q('settingsDialog').showModal();
}
providerMenu.onchange=setProvider;
for(const id of ['endpoint','token','protocol'])q(id).addEventListener(id==='protocol'?'change':'input',invalidateApi);
q('settings').onclick=()=>{analyzeAfterSettings=false;openSettings();};
q('settingsDialog').addEventListener('close',()=>{cancelApi();});
q('testApi').onclick=async()=>{
  const selected=modelMenu.value;
  invalidateApi();
  const controller=new AbortController();operation=controller;
  const timer=setTimeout(()=>controller.abort(),25000);
  q('testApi').disabled=true;statusApi('loading','');
  try{
    const ids=await discoverModels(draftApi(),controller.signal);
    if(operation!==controller)return;
    listApi(ids,selected);statusApi('success','找到 '+ids.length+' 个文本模型。');
  }catch(err){await sleep(120);if(operation===controller)statusApi('error',apiError(err,controller.signal));}
  finally{clearTimeout(timer);if(operation===controller){operation=null;q('testApi').disabled=false;}}
};
q('settingsForm').onsubmit=async e=>{
  e.preventDefault();
  if(providerMenu.value==='local'){cancelApi();cfg=null;}
  else{
    if(operation||!modelMenu.value)return;
    const controller=new AbortController();operation=controller;
    const timer=setTimeout(()=>controller.abort(),150000);
    q('testApi').disabled=true;q('saveApi').disabled=true;modelMenu.disabled=true;
    statusApi('loading','正在用一句示例验证模型…');
    try{
      const draft=draftApi();
      const probe=await analyzeModel(['林雨今年二十岁。'],draft,()=>{},[],new Map(),controller.signal);
      if(!probe.some(p=>p.name==='林雨'&&p.fields.age?.line===1))throw new Error('已连接，但模型没有返回带原文依据的年龄，请调高输出上限或换一个模型。');
      if(operation!==controller)return;
      cfg={...draft,models:[...modelMenu.options].map(o=>o.value)};
    }catch(err){await sleep(120);
      if(operation===controller)statusApi('error','验证未通过：'+apiError(err,controller.signal));
      return;
    }finally{
      clearTimeout(timer);
      if(operation===controller){modelMenu.disabled=false;operation=null;q('testApi').disabled=false;q('saveApi').disabled=false;}
    }
  }
  q('settingsDialog').close();updateEngine();
  setStatus(cfg?'已验证并保存模型 '+cfg.model+'。点「用模型分析本书」即可分析当前打开的小说。':'已切回本地提取。');
  if(cfg&&analyzeAfterSettings&&state.lines.length){analyzeAfterSettings=false;await runBookModel(false);}
};

/* ---------- 交互 ---------- */
q('file').onchange=e=>{const file=e.target.files[0];e.target.value='';onFile(file);};
q('paste').onclick=e=>{e.preventDefault();e.stopPropagation();q('pasteDialog').showModal();};
q('pasteForm').onsubmit=e=>{e.preventDefault();q('pasteDialog').close();loadBook(q('pasteTitle').value.trim()||'未命名小说',q('pasteText').value,{large:q('pasteText').value.length>170000});};
q('sample').onclick=()=>loadBook('雨夜来信',sampleText,{sample:true});
q('export').onclick=openExport;
q('search').oninput=listPeople;
q('directoryToggle').onclick=()=>setDir(!q('shell').classList.contains('expanded'));
q('directory').addEventListener('keydown',e=>{if(e.key==='Escape'&&q('shell').classList.contains('expanded')){setDir(false);q('directoryToggle').focus();}});
q('scrim').onclick=closeDrawer;
document.addEventListener('pointerdown',e=>{if(q('shell').classList.contains('expanded')&&matchMedia('(max-width:760px)').matches&&!e.target.closest('#directory'))setDir(false);});
q('tagForm').onsubmit=e=>{
  e.preventDefault();
  const p=state.people.find(x=>x.name===state.selected),value=q('tagInput').value.trim();
  if(!p||!value){q('tagError').textContent='请输入标签名称。';return;}
  const tags=unique([...savedTags(state.bookKey,p.name),...(p.customTags||[])]);
  if([p.name,...(p.aliases||[]),...tags].some(t=>t.toLowerCase()===value.toLowerCase())){q('tagError').textContent='已有这个名称或标签。';return;}
  tags.push(value);
  try{localStorage.setItem(tagStorageKey(state.bookKey,p.name),JSON.stringify(tags));}
  catch{q('tagError').textContent='浏览器未能保存标签，请检查存储权限。';return;}
  p.customTags=tags;q('tagEditor').close();render();
};
document.addEventListener('click',e=>{
  const b=e.target.closest('button');if(!b)return;
  if(b.dataset.close){q(b.dataset.close).close();return;}
  if(b.hasAttribute('data-close-drawer')){closeDrawer();return;}
  if(b.hasAttribute('data-add-tag')){q('tagInput').value='';q('tagError').textContent='';q('tagTitle').textContent='给'+state.selected+'加标签';q('tagEditor').showModal();q('tagInput').focus();return;}
  if(b.hasAttribute('data-expand')){const d=b.previousElementSibling;const open=d.classList.toggle('clamp');b.textContent=open?'展开全文':'收起';return;}
  if(b.dataset.refs){openProofs(b.dataset.refs.split(',').map(Number),b.dataset.title||'原文');return;}
  if(b.dataset.manual){openProofs([],state.selected+' · '+b.dataset.manual);q('drawerBody').innerHTML='<p class="muted" style="padding-top:16px">这是你添加的标签。</p>';return;}
  if(b.dataset.alias){
    const p=state.people.find(x=>x.name===state.selected);if(!p)return;
    const refs=unique((p.aliasEvidence||[]).filter(x=>x.alias===b.dataset.alias).map(x=>x.line));
    openProofs(refs,p.name+' · '+b.dataset.alias);
    if(!refs.length)q('drawerBody').innerHTML='<p class="muted" style="padding-top:16px">暂无可定位的原文依据。</p>';
    return;
  }
  if(b.hasAttribute('data-all-titles')){state.showAllTitles=state.selected;render();return;}
  if(b.dataset.titleChip){
    const p=state.people.find(x=>x.name===state.selected);const t=p?.titles?.find(x=>x.title===b.dataset.titleChip);if(!t)return;
    openProofs(t.lines.slice().sort((x,y)=>x-y),p.name+' · '+t.title);
    q('drawerBody').insertAdjacentHTML('afterbegin','<p class="muted" style="padding-top:14px;font-size:13px">'+html(t.by.length?t.by.join('、')+'这样称呼'+p.name+'。':'原文中对'+p.name+'的称呼。')+'</p>');
    return;
  }
  if(b.dataset.gfocus){state.selected=b.dataset.gfocus;listPeople();focusGraph(state.selected);return;}
  if(b.dataset.person){select(b.dataset.person,state.tab==='graph'&&b.classList.contains('person')?'graph':'card');if(state.tab==='graph')focusGraph(state.selected);return;}
  if(b.hasAttribute('data-more-solo')){state.sceneLimits.set('',(state.sceneLimits.get('')||12)+24);render();return;}
  if(b.dataset.moreScenes){const k=b.dataset.moreScenes;state.sceneLimits.set(k,(state.sceneLimits.get(k)||12)+24);render();return;}
  if(b.dataset.tab){state.tab=b.dataset.tab;state.flash=0;render(true);return;}
  if(b.dataset.line){
    const quiet=b.hasAttribute('data-quiet');
    closeDrawer();state.tab='raw';state.target=Number(b.dataset.line);state.flash=quiet?0:state.target;render();
    if(quiet)q('view').closest('.panel').scrollIntoView({block:'start'});
  }
});
document.addEventListener('submit',e=>{
  if(e.target.id!=='namesForm')return;
  e.preventDefault();
  const extra=e.target.querySelector('input').value.split(/[\s,，、]+/u).map(s=>s.trim()).filter(s=>s.length<=30&&s&&state.lines.some(line=>line.includes(s)));
  if(!extra.length){toast('填写的姓名没有在原文中找到。',true);return;}
  loadBook(state.title,state.lines.join('\n'),{forced:unique([...state.forced,...extra]),local:true});
});
document.addEventListener('keydown',e=>{
  if(e.key==='Escape'&&!q('drawer').hidden&&!document.querySelector('dialog[open]')){closeDrawer();return;}
  if(!state.lines.length||e.target.closest('input,textarea,select,dialog'))return;
  if(e.key==='/'){e.preventDefault();setDir(true);return;}
  if((e.key==='j'||e.key==='k')&&state.people.length&&state.tab==='card'){
    const i=state.people.findIndex(p=>p.name===state.selected),n=state.people[(i+(e.key==='j'?1:-1)+state.people.length)%state.people.length];
    select(n.name);q('people').querySelector('.active')?.scrollIntoView({block:'nearest'});
  }
});
let dragDepth=0;
const isFileDrag=e=>Array.from(e.dataTransfer?.types||[]).includes('Files');
const resetDrag=()=>{dragDepth=0;q('dropOverlay').hidden=true;};
window.addEventListener('dragenter',e=>{if(!isFileDrag(e))return;e.preventDefault();dragDepth++;q('dropOverlay').hidden=busy;});
window.addEventListener('dragover',e=>{if(!isFileDrag(e))return;e.preventDefault();e.dataTransfer.dropEffect=busy?'none':'copy';});
window.addEventListener('dragleave',()=>{dragDepth=Math.max(0,dragDepth-1);if(!dragDepth)resetDrag();});
window.addEventListener('drop',e=>{
  resetDrag();
  if(!isFileDrag(e)&&!e.dataTransfer?.files?.length)return;
  e.preventDefault();
  if(busy)return;
  if(e.dataTransfer.files.length>1){toast('一次放入一部小说。',true);return;}
  onFile(e.dataTransfer.files[0]);
});
window.addEventListener('dragend',resetDrag);
/*ST*/
updateEngine();
loadBook('雨夜来信',sampleText,{sample:true});
