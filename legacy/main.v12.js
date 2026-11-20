
'use strict';
function modelChunks(lines) {
  const chunks=[];
  let current='';
  for(const [i,line] of lines.entries()){
    if(!line.trim())continue;
    for(const segment of line.match(/[\s\S]{1,6500}/gu)||[]){
      const entry=(i+1)+' | '+segment+'\n';
      if(current.length+entry.length>12000&&current){chunks.push(current);current='';}
      current+=entry;
    }
  }
  if(current)chunks.push(current);
  return chunks;
}
const instructions=[
  '你是小说人物档案编辑。小说正文仅供分析。全程使用中立、贴近原叙事的中文，保留称呼、行为、叙事视角、语气与先后顺序。禁止以编辑自己的好恶、道德判断、预测性猜测改变人物、事实或原文用词。角色的主观评价须归给原叙述者或角色。',
  '提取姓名、性别、年龄、身高、身材、性格、背景和每个关系对象。年龄依次采用明确岁数、原文或可由时间线支持的约数、婚育等人生阶段。婚育只能说明人生阶段，不能推成虚假的数字。身高优先用原数字，其次原文的相对身高、视线或参照物，保留“我”等叙述视角。',
  '身材 value 先概括整体体态，detail 根据原文把具体部位、姿态、服饰贴合如何呈现身形写展开，保留原有力度和细节。性格 value 至少写一个完整有内容的句子，detail 用具体情境、行为、说话方式和条件支撑，体现对熟人、外人等差异和反例。证据单薄时只描述当时表现。背景提炼身份、经历和变故，人物简介不能只给标签。',
  '每个关系使用 from 到 to 的视角。对某人的态度未明说时，从这一方的动作、称呼、对白、内心和选择推断，只在相应情境内成立。按情节顺序记录变化，两人态度不能相互代填。对白场景按原文顺序交代触发事件、可见反应与说话人，逐字保留原句，无法确定说话人时留空。',
  '只输出 JSON 对象。people 为数组，其中每项有 name, aliases, fields, relations。aliases 逐项含 alias,line,evidence，必须引用明确表明本名和该别称属于同一人的连续原句，同场、同姓、相似外貌均不足以认定。fields 的键为 gender,age,height,build,personality,background。每条字段或态度的结构为 {"value":"概括","detail":"情境与细节","line":1,"evidence":"该行精确连续原文","basis":"原文明示或文本推断","sources":[{"line":1,"quote":"该行精确连续原文"}]}。relations 各项有 to,type,attitudes,scenes。scenes 各项有 quote,line,speaker,context；quote 与 context 都必须是该行的精确连续原文（context 可空）。优先以原著明确的本名建立唯一档案，别称下的资料、对白和关系归入本名。本名未披露时保留原书称呼。只有没有任何支撑时才留空。'
].join('\\n');
async function analyzeModel(lines,cfg,progress,local=[],checkpoint=new Map(),signal){
  const localAliases=local.flatMap(p=>p.aliasEvidence||[]);
  const cards=new Map(),parts=modelChunks(lines),modelAliases=[];
  const get=name=>{
    if(!cards.has(name))cards.set(name,{name,fields:{},relations:[],mentions:0});
    return cards.get(name);
  };
  let segmentLines=new Map();
  function validLine(i,quote){return Number.isInteger(i)&&quote&&segmentLines.get(i)?.some(text=>text.includes(quote));}
  function readField(x){
    const value=string(x?.value),line=x?.line;
    if(!value||!validLine(line,string(x?.evidence)))return;
    const extra=Array.isArray(x.sources)?x.sources.filter(src=>validLine(src?.line,string(src?.quote))).map(src=>src.line):[];
    return field(value,line,x.basis==='原文明示'?'原文明示':'文本推断',string(x.detail),extra);
  }
  for(const [i,part] of parts.entries()){
    progress('正在分析第 '+(i+1)+' / '+parts.length+' 段…');
    segmentLines=new Map();
    for(const row of part.trimEnd().split('\n')){
      const sep=row.indexOf(' | '),n=Number(row.slice(0,sep));
      if(!segmentLines.has(n))segmentLines.set(n,[]);
      segmentLines.get(n).push(row.slice(sep+3));
    }
    let data=checkpoint.get(i);
    if(!data){
      const hints=local.filter(p=>[p.name,...(p.aliases||[])].some(n=>part.includes(n))).slice(0,60).map(p=>({
        name:p.name,aliases:p.aliases||[],customTags:p.customTags||[],
        missing:keys.filter(k=>!p.fields[k])
      }));
      const context='本段本地候选（供核对，用户标签不能作原文证据，可新增或纠正人物）：'+JSON.stringify(hints)+'\n带行号的原文：\n'+part;
      try{
        const answer=await generateText(cfg,instructions,context,signal||AbortSignal.timeout(120000));
        data=JSON.parse(answer.replace(/^`{3}(?:json)?\s*/iu,'').replace(/\s*`{3}$/u,''));
        if(!Array.isArray(data?.people))throw new Error('模型返回的档案缺少人物列表');
        checkpoint.set(i,data);
      }catch(err){throw new Error('第 '+(i+1)+' / '+parts.length+' 段失败：'+(err instanceof SyntaxError?'模型返回的 JSON 格式不完整':err.message));}
    }
    for(const item of data.people){
      const name=string(item?.name);
      if(!name||name.length>80||!part.includes(name))continue;
      const p=get(name);
      for(const a of Array.isArray(item.aliases)?item.aliases:[]){
        const alias=string(a?.alias),proof=string(a?.evidence);
        if(alias&&alias!==name&&alias.length<=12&&proof.includes(name)&&proof.includes(alias)&&part.includes(proof)&&validLine(a.line,proof)){
          modelAliases.push({name,alias,line:a.line});
        }
      }
      for(const k of keys){
        const f=readField(item.fields?.[k]);
        if(!f)continue;
        const previous=p.fields[k];
        if(!previous||(['age','height','gender'].includes(k)&&previous.basis!=='原文明示'&&f.basis==='原文明示'))p.fields[k]=f;
        else if(['build','personality','background'].includes(k)&&!previous.sources.includes(f.line)){
          previous.detail=unique([previous.detail,f.value!==previous.value?f.value:'',f.detail].filter(Boolean)).join('\n');
          previous.sources=unique([...previous.sources,...f.sources]);
        }
      }
      for(const rel of Array.isArray(item.relations)?item.relations:[]){
        const to=string(rel?.to);
        if(!to||to===name||to.length>80||!part.includes(to))continue;
        get(to);
        let edge=p.relations.find(r=>r.to===to);
        if(!edge){edge={to,type:string(rel.type)||'同场互动',attitudes:[],scenes:[]};p.relations.push(edge);}
        else if(edge.type==='同场互动'&&string(rel.type))edge.type=string(rel.type);
        for(const a of Array.isArray(rel.attitudes)?rel.attitudes:[]){
          const f=readField(a);
          if(f&&!edge.attitudes.some(old=>old.value===f.value&&old.line===f.line))edge.attitudes.push(f);
        }
        for(const sc of Array.isArray(rel.scenes)?rel.scenes:[]){
          const quote=string(sc?.quote),line=sc?.line;
          if(validLine(line,quote)&&!edge.scenes.some(s=>s.line===line&&s.quote===quote)){
            edge.scenes.push({quote,line,speaker:string(sc.speaker),context:validLine(line,string(sc.context))?string(sc.context):''});
          }
        }
      }
    }
  }
  const result=[...cards.values()];
  for(const p of result){
    p.mentionsAt=[];
    p.mentions=lines.reduce((n,line,i)=>{if(!line.includes(p.name))return n;if(p.mentionsAt.length<8)p.mentionsAt.push(i+1);return n+1;},0);
    for(const edge of p.relations){
      edge.attitudes.sort((a,b)=>a.line-b.line);
      edge.scenes.sort((a,b)=>a.line-b.line);
    }
  }
  return unifyIdentities(result,[...localAliases,...modelAliases],lines).sort((a,b)=>b.mentions-a.mentions);
}
let state={title:'',lines:[],chapters:[],people:[],selected:'',tab:'card',target:0,sample:false,forced:[],engine:'local',size:0,sceneLimits:new Map()};
let cfg=null,busy=false,analyzeAfterSettings=false;
const html=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const sourceLabel=line=>(state.chapters[line-1]?state.chapters[line-1]+' · ':'')+'第 '+line+' 行';
const sourceButton=(line,label='查看原文')=>'<button class="source-link" data-line="'+line+'">'+html(label)+'</button>';
function setStatus(msg,error=false){q('status').textContent=msg;q('status').classList.toggle('error',error);}
function select(name){state.selected=name;state.tab='card';state.target=0;state.sceneLimits.clear();render();}
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
function listPeople(){
  const term=q('search').value.trim().toLowerCase();
  q('count').textContent=state.people.length+' 位';
  const visible=state.people.filter(p=>[p.name,...(p.aliases||[]),...(p.customTags||[])].some(n=>n.toLowerCase().includes(term)));
  q('people').innerHTML=visible.map(p=>'<button class="person '+(p.name===state.selected?'active':'')+'" data-person="'+html(p.name)+'" aria-label="'+html(p.name)+'" title="'+html(p.name)+'" aria-current="'+(p.name===state.selected?'true':'false')+'"><span class="avatar" aria-hidden="true">'+html([...p.name][0])+'</span><span class="person-info"><b>'+html(p.name)+'</b><small>'+p.relations.length+' 个关系对象</small></span></button>').join('')||'<div class="empty">'+(term?'没有匹配的角色':'等待识别角色')+'</div>';
}
function evidenceLinks(item){
  return '<span class="field-sources">'+(item.sources||[item.line]).slice(0,10).map(line=>sourceButton(line,sourceLabel(line))).join('')+'</span>';
}
const lessCertain = item => item.basis==='文本推断'||item.basis==='行为线索'||item.basis==='代词线索';
function showDetail(item){
  return item.detail?'<blockquote class="excerpt">'+html(item.detail)+'</blockquote>':'';
}
function sceneGroups(scenes){
  const groups=[];
  for(const item of scenes){
    const group=groups.at(-1),previous=group?.at(-1);
    const sameChapter=state.chapters[item.line-1]===state.chapters[previous?.line-1];
    if(previous&&sameChapter&&item.line-previous.line<=3)group.push(item);
    else groups.push([item]);
  }
  return groups;
}
function sceneLine(s){
  return '<div class="scene">'+(s.context?'<div class="hint">'+html(s.context)+'</div>':'')+(s.speaker?'<strong>'+html(s.speaker)+'</strong>':'')+'<q>'+html(s.quote)+'</q>'+sourceButton(s.line,sourceLabel(s.line))+'</div>';
}
function sceneCards(scenes){
  return sceneGroups(scenes).map(group=>{
    const first=group[0].line,last=group.at(-1).line;
    const label=sourceLabel(first)+(last!==first?' 至 '+last+' 行':'');
    return '<div class="scene-group"><h3>'+html(label)+'</h3>'+group.map(sceneLine).join('')+'</div>';
  }).join('');
}
function relationCard(r,open){
  const utterances=r.scenes.length;
  const shown=state.sceneLimits.get(r.to)||12;
  const attitudes=r.attitudes.slice(0,shown).map(a=>'<div class="scene"><strong>'+html(a.value)+'</strong> <span class="hint">· '+html(a.basis)+'</span>'+showDetail(a)+evidenceLinks(a)+'</div>').join('');
  const scenes=sceneCards(r.scenes.slice(0,shown));
  const more=Math.max(utterances,r.attitudes.length)>shown?'<button class="btn subtle" data-more-scenes="'+html(r.to)+'">查看更多互动</button> ':'';
  const fallback=utterances?'可从下列对白查看这段互动，当前依据不足以概括该方态度。':'目前只有关系线索，缺少可归属到该方的态度依据。';
  return '<details class="relation" data-relation="'+html(r.to)+'" '+(open?'open':'')+'><summary><strong>'+html(r.to)+'</strong> <span class="pill">'+html(r.type)+'</span> <span class="hint">· '+utterances+' 句对白</span></summary><div class="relation-inner"><p><strong>对'+html(r.to)+'的态度</strong></p>'+(attitudes||'<p class="muted">'+fallback+'</p>')+'<p><strong>对白场景再现</strong></p>'+(scenes||'<p class="muted">暂未定位到这两人的对白。</p>')+more+'<button class="btn subtle" data-person="'+html(r.to)+'">查看'+html(r.to)+'的角色卡</button></div></details>';
}
function renderClues(person){
  const sources=(person.mentionsAt||[]).filter(n=>state.lines[n-1]?.trim()).slice(0,4);
  if(!sources.length)return '';
  return '<details class="evidence"><summary>人物出场原文</summary>'+sources.map(n=>'<blockquote class="excerpt">'+html(state.lines[n-1].trim().slice(0,420))+'</blockquote>'+sourceButton(n,sourceLabel(n))).join('')+'</details>';
}
const nameForm='<form id="namesForm"><input class="editnames" aria-label="人物姓名" placeholder="姓名之间用顿号分隔" required><button class="btn">重新提取</button></form>';
function render(){
  listPeople();
  q('book').textContent='《'+state.title+'》'+(state.sample?' · 示例':'');
  const chapters=unique(state.chapters.filter(Boolean)).length;
  q('stats').textContent=state.size.toLocaleString()+' 字符'+(chapters?' · '+chapters+' 章':'')+' · '+(state.engine==='local'?'本地提取':'模型提取');
  q('export').disabled=!state.people.length||busy;
  document.querySelectorAll('[data-tab]').forEach(b=>{
    const active=b.dataset.tab===state.tab;
    b.classList.toggle('active',active);
    if(active)b.setAttribute('aria-current','page');else b.removeAttribute('aria-current');
  });
  const p=state.people.find(x=>x.name===state.selected);
  if(state.tab==='raw'){renderRaw();return;}
  if(state.tab==='graph'){renderGraph();return;}
  if(!p){q('view').innerHTML='<div class="empty">正文已读取。目前未确认角色姓名，可以补充原文里的姓名或称呼。'+nameForm+'</div>'+renderUnassigned();return;}
  const facts=['gender','age','height'].map(k=>{
    const f=p.fields[k];
    return '<div class="fact"><dt>'+labels[k]+'</dt><dd>'+html(f?.value||unknown)+(f?'<span class="hint">'+html(f.basis)+'</span>':'')+'</dd></div>';
  }).join('');
  const notes=['background','build','personality'].map(k=>{
    const f=p.fields[k];
    const noTrait=k==='personality'?'暂未归纳出稳定性格，可查看下方出场与对白。':unknown;
    const value=f?.value||noTrait;
    return '<div class="note"><b>'+labels[k]+(f&&lessCertain(f)?' · '+html(f.basis):'')+'</b><p>'+html(value)+'</p>'+(f?showDetail(f)+evidenceLinks(f):'')+'</div>';
  }).join('');
  const evidence=['gender','age','height'].filter(k=>p.fields[k]).map(k=>{
    const f=p.fields[k];
    return '<li>'+labels[k]+' · '+html(f.basis)+showDetail(f)+evidenceLinks(f)+'</li>';
  }).join('');
  const tags=unique(p.aliases||[]).filter(a=>a&&a!==p.name).map(a=>
    '<button type="button" class="alias-chip" data-alias="'+html(a)+'" aria-haspopup="dialog">'+html(a)+'</button>'
  ).join('');
  const manualTags=(p.customTags||[]).filter(a=>!(p.aliases||[]).includes(a)).map(a=>'<button class="alias-chip manual-tag" data-manual="'+html(a)+'">'+html(a)+'</button>').join('');
  const addTag='<button type="button" class="tag-add" data-add-tag aria-label="添加自定义标签" title="添加自定义标签">＋</button>';
  q('view').innerHTML='<div class="profile"><span class="avatar big" aria-hidden="true">'+html([...p.name][0])+'</span><div class="identity"><div class="identity-line"><h2>'+html(p.name)+'</h2>'+tags+manualTags+addTag+'</div>' +'<p>'+p.mentions+' 行提及 · '+p.relations.length+' 个关系对象</p></div></div><dl class="facts">'+facts+'</dl><div class="notes">'+notes+'</div>'+
  (evidence?'<details class="evidence"><summary>查看字段依据</summary><ul>'+evidence+'</ul></details>':'')+
  '<section class="block"><div class="block-head"><h2>人物关系与对白</h2><span class="count">'+p.relations.length+' 个关系对象</span></div>'+
  (p.relations.map((r,i)=>relationCard(r,i===0||state.sceneLimits.has(r.to))).join('')||'<p class="muted">暂未找到可以关联的人物互动。</p>')+'</section>'+
  (p.soloScenes?.length?'<section class="block"><h2>人物对白</h2>'+sceneCards(p.soloScenes.slice(0,state.sceneLimits.get('')||12))+(p.soloScenes.length>(state.sceneLimits.get('')||12)?'<button class="btn subtle" data-more-solo="1">查看更多人物对白</button>':'')+'</section>':'')+renderClues(p)+
  (state.engine==='local'&&!state.sample?'<details class="evidence"><summary>补充漏掉的姓名</summary>'+nameForm+'</details>':'');
}
function renderUnassigned(){
  const passages=[];
  for(const [i,line] of state.lines.entries()){
    if(passages.length>=5)break;
    if(!line.trim()||state.chapters[i]===line.trim())continue;
    const quote=[...line.slice(0,1200).matchAll(speech)][0];
    passages.push(quote?sceneLine({quote:quote[1],context:line.slice(0,quote.index).trim(),line:i+1}):'<blockquote class="excerpt">'+html(line.trim().slice(0,420))+'</blockquote>'+sourceButton(i+1,sourceLabel(i+1)));
  }
  return passages.length?'<section class="block"><h2>暂未归属的人物线索</h2>'+passages.join('')+'</section>':'';
}
function renderGraph(){
  const edges=state.people.flatMap(p=>p.relations.map(r=>({from:p.name,to:r.to,type:r.type,attitude:r.attitudes.at(-1)?.value})));
  const graph=edges.map(e=>'<div class="edge"><button class="btn" data-person="'+html(e.from)+'">'+html(e.from)+'</button><span title="'+html(e.attitude||'')+'">'+html(e.type)+' →</span><button class="btn" data-person="'+html(e.to)+'">'+html(e.to)+'</button></div>').join('');
  q('view').innerHTML='<section class="block standalone"><div class="block-head"><h2>人物关系网</h2><span class="count">'+edges.length+' 条有向关系</span></div><p class="hint">从左侧人物读向右侧人物。点击姓名可展开该人物的态度与对白。</p><div class="graph">'+(graph||'<p class="muted">暂未提取到关系。</p>')+'</div></section>';
}
function renderRaw(){
  const target=state.target||1,start=Math.max(0,target-9),end=Math.min(state.lines.length,Math.max(target+16,start+50));
  const range=state.lines.slice(start,end).map((line,index)=>{
    const n=start+index+1;
    return n===state.target?'<mark id="lineFocus">'+n+'　'+html(line)+'</mark>':n+'　'+html(line);
  }).join('\n');
  q('view').innerHTML='<div class="block-head"><strong>小说原文</strong><span class="hint">第 '+(start+1)+'–'+end+' 行 / 共 '+state.lines.length+' 行</span></div><div class="raw" tabindex="0">'+range+'</div><div class="actions">'+(start?'<button class="btn" data-line="'+Math.max(1,start-40)+'">上一段</button>':'')+(end<state.lines.length?'<button class="btn" data-line="'+(end+1)+'">下一段</button>':'')+'</div>';
  const focused=q('lineFocus');
  if(focused){focused.scrollIntoView({block:'nearest'});q('view').querySelector('.raw').focus({preventScroll:true});}
}
function setBusy(v){
  busy=v;
  for(const id of ['sample','file','paste','settings','runBookModel'])q(id).disabled=v;
  q('export').disabled=v||!state.people.length;
}
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
function enrichModelCards(people,local,lines){
  const allLinks=[...local,...people].flatMap(p=>p.aliasEvidence||[]);
  const links=[...new Map(allLinks.map(x=>[JSON.stringify([x.name,x.alias,x.line]),x])).values()];
  // The merger also handles different names used by the two extractors.
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
async function runBookModel(){
  if(busy||!state.lines.length)return;
  if(!cfg){q('settings').click();analyzeAfterSettings=true;q('saveApi').textContent='保存并分析本书';return;}
  setBusy(true);
  const current=state;
  if(current.checkConfig!==cfg){current.checkpoint=new Map();current.checkConfig=cfg;}
  try{
    const local=current.localPeople;
    const result=await analyzeModel(current.lines,cfg,setStatus,local,current.checkpoint);
    if(!result.length){current.checkpoint.clear();throw new Error('模型未返回可定位的人物');}
    const selected=current.selected;
    current.people=enrichModelCards(result,local,current.lines);
    for(const p of current.people)p.customTags=savedTags(current.bookKey,p.name);
    current.selected=current.people.find(p=>p.name===selected||p.aliases?.includes(selected))?.name||current.people[0].name;
    current.engine='model';current.checkpoint.clear();
    setStatus('已将 '+cfg.model+' 的分析合入本书，共 '+current.people.length+' 位角色。');
    render();
  }catch(err){
    setStatus(err.message+'。当前档案已保留，可点「用模型分析本书」重试。',true);
  }finally{setBusy(false);}
}
async function loadBook(title,content,{sample=false,forced=[],local=false,large=false}={}){
  if(busy)return;
  if(!content.trim()){setStatus('正文为空，请换一个文本。',true);return;}
  const lines=content.replace(/^\uFEFF/,'').split(/\r\n|\n|\r/u);
  setBusy(true);
  const auto=cfg&&!sample&&!local&&(!large||cfg.longModel);
  setStatus('正在定位人物和原文线索…');
  try{
    const people=await analyzeInBackground(lines,forced,setStatus);
    const bookKey=await tagBookKey(lines.join('\n'));
    for(const p of people)p.customTags=savedTags(bookKey,p.name);
    state={bookKey,title,lines,chapters:chapterMap(lines),people,localPeople:people,selected:people[0]?.name||'',tab:'card',target:0,sample,forced,engine:'local',size:content.replace(/\s/gu,'').length,sceneLimits:new Map()};
    q('search').value='';
    setStatus(sample?'正在查看内置示例。':cfg&&large&&!cfg.longModel?'长篇本地档案已就绪。点「用模型分析本书」可分段处理当前小说。':'已读取正文，识别 '+people.length+' 位角色。');
    render();
  }catch{setStatus('本地提取中断，请重新导入。',true);return;}
  finally{setBusy(false);}
  if(auto)await runBookModel();
}
async function onFile(file){
  if(busy)return;
  if(!file){setStatus('没有收到文件，请从设备选取小说文件。',true);return;}
  const suffix=/\.(txt|md|markdown)$/iu;
  const textType=/^text\/(?:plain|markdown|x-markdown)(?:;|$)/iu;
  if(!suffix.test(file.name)&&(/\.[^.]+$/u.test(file.name)||!textType.test(file.type))){
    setStatus('无法导入「'+file.name+'」。请选择 TXT 或 Markdown 文件。',true);
    return;
  }
  if(file.size>6*1024*1024){setStatus('单个小说文件最大支持 6 MB，请拆分后导入。',true);return;}
  setBusy(true);
  setStatus('正在读取「'+file.name+'」…');
  let content;
  try{
    const data=await file.arrayBuffer(),bytes=new Uint8Array(data);
    if(bytes[0]===255&&bytes[1]===254)content=new TextDecoder('utf-16le').decode(data);
    else if(bytes[0]===254&&bytes[1]===255)content=new TextDecoder('utf-16be').decode(data);
    else{
      try{content=new TextDecoder('utf-8',{fatal:true}).decode(data);}
      catch{content=new TextDecoder('gb18030').decode(data);}
    }
    if(content.includes('\0'))throw new Error('Invalid text');
  }catch{
    setStatus('读取失败，请从设备重新选取 TXT 或 Markdown 文件。',true);
    return;
  }finally{setBusy(false);}
  await loadBook(file.name.replace(suffix,'')||'未命名小说',content,{large:file.size>512*1024});
}
function exportCards(){
  const lines=['# '+state.title+' · 角色卡'];
  for(const p of state.people){
    lines.push('', '## '+p.name,'');
    if(p.customTags?.length)lines.push('自定义标签　'+p.customTags.join('、'),'');
    if(p.aliases?.length)for(const a of p.aliases){const refs=unique((p.aliasEvidence||[]).filter(x=>x.alias===a).map(x=>x.line));lines.push('称呼　'+a+(refs.length?'（'+refs.slice(0,3).map(sourceLabel).join('；')+'）':''));}
    for(const [k,l] of Object.entries(labels)){
      const f=p.fields[k];
      lines.push('- '+l+'　'+(k==='name'?p.name:f?f.value+'（'+f.basis+'，'+f.sources.map(sourceLabel).join('；')+'）':unknown));
      if(f?.detail)lines.push(...f.detail.split('\n').map(detail=>'  > '+detail));
    }
    if(p.soloScenes?.length){lines.push('','### 人物对白');for(const sc of p.soloScenes)lines.push('> '+sc.quote+'（'+sourceLabel(sc.line)+'）');}
    for(const r of p.relations){
      lines.push('','### '+r.to+'（'+r.type+'）','','**对'+r.to+'的态度**');
      lines.push(...(r.attitudes.length?r.attitudes.flatMap(a=>['- '+a.value+'（'+a.basis+'，'+a.sources.map(sourceLabel).join('；')+'）',...(a.detail?a.detail.split('\n').map(detail=>'  > '+detail):[])]):['- 原文态度暂未提取']));
      lines.push('','**对白场景再现**');
      lines.push(...(r.scenes.length?r.scenes.flatMap(s=>[(s.context||'')+'（'+sourceLabel(s.line)+'）','> '+(s.speaker?s.speaker+'　':'')+s.quote]):['- 暂未提取到相关对白']));
    }
  }
  const href=URL.createObjectURL(new Blob([lines.join('\n')],{type:'text/markdown;charset=utf-8'}));
  const link=document.createElement('a');
  link.href=href;link.download=state.title+'-角色卡.md';link.click();
  setTimeout(()=>URL.revokeObjectURL(href),3000);
}
q('file').onchange=e=>{const file=e.target.files[0];e.target.value='';onFile(file);};
q('paste').onclick=()=>q('pasteDialog').showModal();

q('runBookModel').onclick=runBookModel;
q('sample').onclick=()=>loadBook('雨夜来信',sampleText,{sample:true});
q('export').onclick=exportCards;
q('tagForm').onsubmit=e=>{
  e.preventDefault();
  const p=state.people.find(x=>x.name===state.selected),value=q('tagInput').value.trim();
  if(!p||!value){q('tagError').textContent='请输入标签名称。';return;}
  const stored=savedTags(state.bookKey,p.name), tags=unique([...stored,...(p.customTags||[])]);
  if([p.name,...(p.aliases||[]),...tags].some(t=>t.toLowerCase()===value.toLowerCase())){
    q('tagError').textContent='这位角色已有这个名称或标签。';return;
  }
  tags.push(value);
  try{localStorage.setItem(tagStorageKey(state.bookKey,p.name),JSON.stringify(tags));}
  catch{q('tagError').textContent='浏览器未能保存标签，请检查本站存储权限或可用空间。';return;}
  p.customTags=tags;q('tagEditor').close();render();q('view').querySelector('[data-add-tag]').focus();
};
q('search').oninput=listPeople;
const shell=document.querySelector('.app-shell');
const directoryToggle=q('directoryToggle');
function setDirectoryExpanded(expanded){
  shell.classList.toggle('expanded',expanded);
  directoryToggle.setAttribute('aria-expanded',String(expanded));
  directoryToggle.title=expanded?'收起角色目录':'展开角色目录';
  directoryToggle.setAttribute('aria-label',directoryToggle.title);
  if(!expanded && q('search').value){q('search').value='';listPeople();}
}
directoryToggle.onclick=()=>setDirectoryExpanded(!shell.classList.contains('expanded'));
q('directory').addEventListener('keydown',event=>{
  if(event.key==='Escape' && shell.classList.contains('expanded')){
    setDirectoryExpanded(false);
    directoryToggle.focus();
  }
});
let dragDepth=0;
function isFileDrag(event){
  return Array.from(event.dataTransfer?.types||[]).includes('Files');
}
function resetDrag(){
  dragDepth=0;
  q('dropOverlay').hidden=true;
}
window.addEventListener('dragenter',event=>{
  if(!isFileDrag(event))return;
  event.preventDefault();
  dragDepth++;
  q('dropOverlay').hidden=busy;
});
window.addEventListener('dragover',event=>{
  if(!isFileDrag(event))return;
  event.preventDefault();
  event.dataTransfer.dropEffect=busy?'none':'copy';
  q('dropOverlay').hidden=busy;
});
window.addEventListener('dragleave',()=>{
  dragDepth=Math.max(0,dragDepth-1);
  if(!dragDepth)resetDrag();
});
window.addEventListener('drop',event=>{
  resetDrag();
  if(!isFileDrag(event)&&!event.dataTransfer?.files?.length)return;
  event.preventDefault();
  const files=event.dataTransfer.files;
  if(busy)return;
  if(files.length>1){setStatus('每次导入一份小说，请拖入单个文件。',true);return;}
  onFile(files[0]);
});
window.addEventListener('dragend',resetDrag);
q('pasteForm').onsubmit=e=>{
  e.preventDefault();
  q('pasteDialog').close();
  loadBook(q('pasteTitle').value.trim()||'未命名小说',q('pasteText').value,{large:q('pasteText').value.length>170000});
};
const providerMenu=q('provider'),modelMenu=q('model');
for(const [key,[label]] of Object.entries(providers))providerMenu.add(new Option(label,key));
let operation=null;
function cancelApi(){operation?.abort();operation=null;}
function statusApi(state,message){
  q('testApi').dataset.state=state;
  q('testLabel').textContent={loading:'正在测试连接…',success:'连接成功',error:'重新测试连接'}[state]||'测试 API 连接';
  q('apiStatus').textContent=message;
  q('apiStatus').dataset.state=state;
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
  q('token').value='';q('outTokens').value='';q('temp').value='';
}
function draftApi(){
  if(!q('token').value.trim())throw new Error('请先填写 API 密钥。');
  let endpoint;
  try{endpoint=apiAddress(q('endpoint').value);}catch{throw new Error('请填写有效的 HTTPS API 基础地址。');}
  const max=q('outTokens').value,temp=q('temp').value;
  if(max&&(!Number.isSafeInteger(Number(max))||Number(max)<1))throw new Error('最大输出 token 应为正整数。');
  if(temp&&(Number(temp)<0||Number(temp)>(q('protocol').value==='claude'?1:2)))throw new Error('温度超出当前协议范围。');
  return {max:max?Number(max):undefined,temp:temp!==''?Number(temp):undefined,provider:providerMenu.value,protocol:q('protocol').value,endpoint,token:q('token').value.trim(),model:modelMenu.value,longModel:q('longModel').checked};
}
function listApi(ids,selected){
  modelMenu.replaceChildren(...ids.map(id=>new Option(id,id)));
  if(ids.includes(selected))modelMenu.value=selected;
  q('modelRow').hidden=false;q('saveApi').disabled=false;
}
function apiError(err,signal){
  if(signal.aborted)return '请求超时，请再试一次。';
  return err instanceof TypeError?'无法从浏览器连接该地址，请检查网络、地址及供应商的跨域设置。':err.message;
}
providerMenu.onchange=setProvider;
for(const id of ['endpoint','token','protocol'])q(id).addEventListener(id==='protocol'?'change':'input',invalidateApi);
q('settings').onclick=()=>{
  analyzeAfterSettings=false;q('saveApi').textContent='保存';
  providerMenu.value=cfg?.provider||'local';setProvider();
  q('longModel').checked=!!cfg?.longModel;
  if(cfg){
    q('protocol').value=cfg.protocol;q('endpoint').value=cfg.endpoint;q('token').value=cfg.token;
    q('outTokens').value=cfg.max??'';q('temp').value=cfg.temp??'';listApi(cfg.models,cfg.model);statusApi('success','已保存并验证当前模型。');
  }
  q('settingsDialog').showModal();
};
q('settingsDialog').addEventListener('close',cancelApi);
for(const id of ['outTokens','temp','longModel'])q(id).addEventListener('input',()=>{
  if(operation){invalidateApi();}
});
q('testApi').onclick=async()=>{
  const selected=modelMenu.value;
  invalidateApi();
  const controller=new AbortController();operation=controller;
  const timer=setTimeout(()=>controller.abort(),25000);
  q('testApi').disabled=true;statusApi('loading','正在读取供应商的模型列表。');
  try{
    const ids=await discoverModels(draftApi(),controller.signal);
    if(operation!==controller)return;
    listApi(ids,selected);statusApi('success','连接成功，已读取 '+ids.length+' 个候选文本模型。');
  }catch(err){if(operation===controller)statusApi('error',apiError(err,controller.signal));}
  finally{clearTimeout(timer);if(operation===controller){operation=null;q('testApi').disabled=false;}}
};
q('settingsForm').onsubmit=async e=>{
  e.preventDefault();
  if(providerMenu.value==='local'){cancelApi();cfg=null;}
  else{
    if(operation||!modelMenu.value)return;
    const controller=new AbortController();operation=controller;
    const timer=setTimeout(()=>controller.abort(),60000);
    q('testApi').disabled=true;q('saveApi').disabled=true;modelMenu.disabled=true;
    statusApi('success','正在验证所选模型能否返回文本…');
    try{
      const draft=draftApi();
      const probe=await analyzeModel(['林雨今年二十岁。'],draft,()=>{},[],new Map(),controller.signal);
      if(!probe.some(p=>p.name==='林雨'&&p.fields.age?.line===1))throw new Error('已连接，但模型未返回带原文依据的年龄字段，请检查输出上限或更换模型。');
      if(operation!==controller)return;
      cfg={...draft,models:[...modelMenu.options].map(o=>o.value)};
    }catch(err){
      if(operation===controller){statusApi('success','所选模型调用失败：'+apiError(err,controller.signal)+' 可选择其他模型重试。');q('apiStatus').dataset.state='error';}
      return;
    }finally{
      clearTimeout(timer);
      if(operation===controller){modelMenu.disabled=false;operation=null;q('testApi').disabled=false;q('saveApi').disabled=false;}
    }
  }
  q('mode').textContent='TXT / Markdown · 支持 6 MB · '+(cfg?'模型提取':'本地提取');
  q('settingsDialog').close();setStatus(cfg?'已验证并保存模型 '+cfg.model+'。点「用模型分析本书」即可分析当前打开的小说。':'已切回本地提取。');
  if(cfg&&analyzeAfterSettings)await runBookModel();
};
document.addEventListener('click',e=>{
  const b=e.target.closest('button');
  if(!b)return;
  if(b.hasAttribute('data-add-tag')){
    q('tagInput').value='';q('tagError').textContent='';
    q('tagTitle').textContent=state.selected+' · 添加自定义标签';
    q('tagEditor').showModal();q('tagInput').focus();
  }
  if(b.dataset.manual){
    q('aliasTitle').textContent=state.selected+' · '+b.dataset.manual;
    q('aliasSources').textContent='你为这位角色添加的自定义标签。';
    q('aliasDialog').showModal();
  }
  if(b.dataset.alias){
    const p=state.people.find(x=>x.name===state.selected);
    if(!p)return;
    const alias=b.dataset.alias;
    const refs=unique((p.aliasEvidence||[]).filter(x=>x.alias===alias).map(x=>x.line));
    q('aliasTitle').textContent=p.name+' · '+alias;
    q('aliasSources').innerHTML=refs.map(n=>'<blockquote class="excerpt">'+html(state.lines[n-1]||'')+'</blockquote>'+sourceButton(n,sourceLabel(n))).join('')||'<p>暂无可定位的原文依据。</p>';
    q('aliasDialog').showModal();
  }
  if(b.dataset.line && q('aliasDialog').open)q('aliasDialog').close();
  if(b.dataset.close)q(b.dataset.close).close();
  if(b.dataset.person){
    select(b.dataset.person);
  }
  if(b.dataset.moreSolo){state.sceneLimits.set('',(state.sceneLimits.get('')||12)+24);render();}
  if(b.dataset.moreScenes){const key=b.dataset.moreScenes;state.sceneLimits.set(key,(state.sceneLimits.get(key)||12)+24);render();}
  if(b.dataset.tab){state.tab=b.dataset.tab;state.target=0;render();}
  if(b.dataset.line){state.tab='raw';state.target=Number(b.dataset.line);render();}
});
document.addEventListener('submit',e=>{
  if(e.target.id!=='namesForm')return;
  e.preventDefault();
  const extra=e.target.querySelector('input').value.split(/[\s,，、]+/u).map(s=>s.trim()).filter(s=>s.length<=30&&s&&state.lines.some(line=>line.includes(s)));
  if(!extra.length){setStatus('填写的姓名未在原文中找到。',true);return;}
  loadBook(state.title,state.lines.join('\n'),{forced:unique([...state.forced,...extra]),local:true});
});
loadBook('雨夜来信',sampleText,{sample:true});
