/* ================= 模型分析 =================
 * 三步，每一步做完就存（停了能续跑）：
 *   一、逐段抽取：按章节切成 9000 字左右一段，3 段并发；每段要章节梗概、出场人物、人物材料、对待别人的方式与原话、世界线索。
 *       模型给的 JSON 不完整就把这一段对半拆开重来（最多拆两层）。
 *   二、成卡：出场两章以上的人（最多 30 个）各写一张卡，3 个并发。材料按人归并，别名合到本名下。
 *   三、世界：用各章梗概和世界线索写世界背景。
 * 原句逐字核对：卡片里的原句、对白在原文里找不到就去掉，<q> 引文找不到就改成普通字。
 */
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
let reqN=0;const pending=new Map();
function ask(system,user,max){
  return new Promise((res,rej)=>{
    if(!NATIVE)return rej(Object.assign(new Error('网页预览里不能调模型，请在 App 里分析'),{fatal:true}));
    const id='r'+(++reqN);pending.set(id,{res,rej});
    try{CS.ask(id,system,user,max)}catch(e){pending.delete(id);rej(e)}
  });
}
function onReply(id,ok,text,fatal){const p=pending.get(id);if(!p)return;pending.delete(id);ok?p.res(text):p.rej(Object.assign(new Error(text),{fatal:!!fatal}))}
function parseJSON(t){
  t=String(t||'').replace(/^\s*```(?:json)?/i,'').replace(/```\s*$/,'');
  const a=t.indexOf('{'),b=t.lastIndexOf('}');if(a<0||b<=a)return null;
  const s=t.slice(a,b+1);
  try{return JSON.parse(s)}catch(e){try{return JSON.parse(s.replace(/,\s*([}\]])/g,'$1'))}catch(e2){return null}}
}
async function askJSON(b,system,user,max){
  let last;
  for(let k=0;k<3;k++){
    if(b._stop)throw Object.assign(new Error('stop'),{stop:true});
    try{const o=parseJSON(await ask(system,user,max));if(o)return o;last=Object.assign(new Error('模型返回的 JSON 不完整'),{bad:true});}
    catch(e){if(e.fatal)throw e;last=e;}
    await sleep(1500*(k+1));
  }
  throw last;
}

/* ---------- 原文 ---------- */
async function textOf(b){
  if(b._text)return b._text;
  if(NATIVE){const r=await fetch('/text/'+b.id);if(r.ok){b._text=await r.text();return b._text}}
  throw Object.assign(new Error('这本书的原文不在了，请重新导入'),{fatal:true});
}
const norm=s=>String(s||'').replace(/[\s　]+/g,'');
/** 把原文压成去空白的一长串，记下每章在里面的起点，用来核对原句、找原句在第几章。 */
function index(b,text){
  if(b._flat)return;
  const lines=text.split('\n');b._lines=lines;
  const starts=b.chapters.map(c=>c.line||0);
  let flat='',pos=[],ci=0;
  for(let i=0;i<lines.length;i++){
    while(ci<starts.length&&starts[ci]<=i){pos[ci]=flat.length;ci++}
    flat+=norm(lines[i]);
  }
  while(ci<starts.length){pos[ci]=flat.length;ci++}
  b._flat=flat;b._pos=pos;
}
function locate(b,q){
  const n=norm(q);if(n.length<4)return 0;
  const at=b._flat.indexOf(n);if(at<0)return 0;
  let c=0;for(let i=0;i<b._pos.length;i++)if(b._pos[i]<=at)c=i+1;
  return c||1;
}

/* ---------- 切段 ---------- */
function chunks(b,text){
  const lines=b._lines||text.split('\n');
  const out=[];let cur=null;
  const flush=()=>{if(cur&&cur.text.trim())out.push(cur);cur=null};
  b.chapters.forEach((c,i)=>{
    const from=c.line||0,to=i+1<b.chapters.length?(b.chapters[i+1].line||lines.length):lines.length;
    const body=lines.slice(from+(c.head?1:0),to).join('\n');
    const head=`【第${i+1}${b.unit} ${c.t[0]}${c.t[1]?' '+c.t[1]:''}】\n`;
    if(body.length>14000){ // 一章太长：按行再切
      flush();
      const ls=body.split('\n');let part='';
      for(const l of ls){part+=l+'\n';if(part.length>9000){out.push({a:i+1,b:i+1,text:head+part});part=''}}
      if(part.trim())out.push({a:i+1,b:i+1,text:head+part});
      return;
    }
    if(!cur)cur={a:i+1,b:i+1,text:''};
    cur.text+=head+body+'\n';cur.b=i+1;
    if(cur.text.length>=9000)flush();
  });
  flush();
  return out;
}

/* ---------- 提示词 ---------- */
const SYS='你是小说人物档案编辑，只做整理，不做评判。全程使用中立、贴近原叙事的中文：保留原文的称呼、用词、叙事视角（包括第一人称“我”）、语气和先后顺序；人物的主观评价要写明出自谁之口；不加入你自己的好恶、道德判断和猜测。引用原文时逐字照抄，一个字都不能改。只输出一个 JSON 对象，不要任何解释，不要代码块。';
function p1(b,ck){
  return `下面是《${b.title}》第${ck.a}${ck.a===ck.b?'':'–'+ck.b}${b.unit}的原文。按这个结构输出 JSON：
{"chapters":[{"n":章序号,"sum":"本章梗概，60到100字","ev":["关键情节，每条12字以内，最多5条"],"cast":["本章出场人物的本名"]}],
"people":[{"name":"本名（原文没交代本名时用原书称呼）","aliases":["别名、小名、外号"],"titles":[["别人对他的称呼","谁这样叫"]],
"gender":["性别","明示或推断","依据原句"],"age":["岁数或人生阶段","明示或推断","依据原句"],"height":["身高或相对身高","明示或推断","依据原句"],
"bg":["身份背景：家里是干什么的、出身哪里、阶层、经历与变故，每条附原句"],"look":["外貌、身材、服饰的原文描写，逐字"],"pers":["性格表现：在什么情境下怎样做、怎样说"],
"events":[{"n":章序号,"t":"他在这一章做了什么，一句"}],
"toward":[{"to":"对方本名；对一群人时写群体名，如下人、员工、同学、陌生人","group":false,"type":"关系","how":"怎样对待","why":"为什么这样对待：从出身、地位、过往、利害里找，附原句","lines":[{"ctx":"说话前的场景，原文逐字","q":"说的原话，逐字","who":"说话人本名"}]}]}],
"world":["时代、地点、社会阶层、行业、风俗方面的线索，每条附原句"]}
规则：
1. 只写这段原文里有的；没有的字段省略，不要编。
2. 年龄依次用：明确岁数、原文约数、婚育等人生阶段（如已为人妻、生过两个孩子），不要编数字。身高优先原数字，其次相对身高或参照物（如比“我”高半个头），保留叙述视角。
3. 别名要有原句明确表明是同一人；同姓、同场都不算。
4. toward 要写清怎样对待、为什么；对群体（下属、员工、同学、陌生人……）的态度也单列。lines 只挑最能体现这种对待方式、最有个人特点的原话。
5. 原句、ctx、q 必须是原文里连续的原字。

原文：
${ck.text}`;
}
function p2(b,name,mat){
  return `根据下面从《${b.title}》各${b.unit}抽出来的材料，为「${name}」写一张角色卡。按这个结构输出 JSON：
{"r":"一句话定位：身份与处境，15字以内",
"fx":{"gender":["值","明示或推断",章序号],"age":["值","明示或推断",章序号],"height":["值","明示或推断",章序号]},
"bg":{"v":"身份背景，一句","d":"展开：家里是干什么的、出身哪里、经历与变故。按原文笔法写，照搬原文的句子用<q></q>包起来","s":[[章序号,"原句"]]},
"build":{"v":"先概括整体体态，一句","d":"再娓娓道来：五官、身形、衣着怎样衬出身形。可以照搬原文描写，用<q></q>包起来","s":[[章序号,"原句"]]},
"pers":{"v":"性格，一个完整的句子","d":"用具体情境、言行、对熟人和外人的差别来写，可以照搬原句","s":[[章序号,"原句"]]},
"rels":[{"to":"对方本名（对群体时空着）","g":"群体名（对个人时空着）","type":"关系","v":"态度，一句","why":"写成：怎样对待，因为……所以……。同时……也是为了……。最后落到具体怎么表现。原因从这个人的出身、地位、过往、利害里找；原文明说的用<q></q>引原句","tone":"对这个对象说话的方式，一句","att":[{"v":"这一阶段的态度","d":"情境与细节","c":章序号}],"sc":[{"ctx":"说话前的场景，原文","q":"原话，逐字","who":"说话人","c":章序号}]}],
"tl":{"章序号":"这一${b.unit}里他做了什么，一句"}}
规则：
1. 客观中立、沉浸式，按小说原文的文笔写，不评判、不篡改；角色的主观评价归给说话的人。
2. 材料里没有的就写 null 或省略，不要编；性别年龄身高没有依据时写 null。
3. rels 按重要程度排，最多 8 个；对群体（下人、员工、同学、陌生人……）的态度也单列一项。
4. 每个对象的 sc 选 1 到 3 句最能体现“他对这个人怎样说话”的原话，必须逐字取自材料。

材料：
${JSON.stringify(mat)}`;
}
function p3(b,notes){
  return `根据下面《${b.title}》的章节梗概和世界线索，整理这本书的世界背景。按这个结构输出 JSON：
{"sum":"用 · 隔开的三四个短语，如：民国 · 上海租界 · 商贾人家","items":[["时代","一两句",[章序号,"原句"]],["地点","…",[章序号,"原句"]],["阶层","…",[章序号,"原句"]],["风俗","…",[章序号,"原句"]]]}
没有依据的项省略，原句必须逐字取自线索。

${JSON.stringify(notes)}`;
}

/* ---------- 归并材料 ---------- */
function rawOf(b){
  if(!b.raw)b.raw={people:{},alias:{},world:[]};
  return b.raw;
}
const uniq=a=>[...new Set(a.filter(Boolean).map(x=>typeof x==='string'?x.trim():x))];
function keyOf(raw,name){name=String(name||'').trim();return raw.alias[name]||name}
function absorb(b,ck,o){
  const raw=rawOf(b),tag=`[${ck.a}${ck.a===ck.b?'':'-'+ck.b}]`;
  (o.chapters||[]).forEach(c=>{const i=(+c.n||0)-1;if(i<0||i>=b.chapters.length)return;
    const ch=b.chapters[i];ch.sum=c.sum||ch.sum;ch.ev=(c.ev||ch.ev||[]).slice(0,5);ch.cast=uniq([...(ch.cast||[]),...(c.cast||[])]);});
  (o.people||[]).forEach(p=>{
    if(!p||!p.name)return;
    let k=keyOf(raw,p.name);
    for(const a of p.aliases||[])if(raw.people[keyOf(raw,a)]&&!raw.people[k]){k=keyOf(raw,a);break}
    const r=raw.people[k]||(raw.people[k]={name:k,aliases:[],titles:[],gender:[],age:[],height:[],bg:[],look:[],pers:[],events:[],toward:{},ch:[]});
    for(const a of [p.name,...(p.aliases||[])]){const t=String(a||'').trim();if(t&&t!==k){raw.alias[t]=k;if(!r.aliases.includes(t))r.aliases.push(t)}}
    for(const t of p.titles||[])if(Array.isArray(t)&&t[0]&&!r.titles.some(x=>x[0]===t[0]))r.titles.push([String(t[0]),String(t[1]||'')]);
    for(const f of ['gender','age','height'])if(Array.isArray(p[f])&&p[f][0])r[f].push([...p[f].slice(0,3),ck.a]);
    for(const f of ['bg','look','pers'])for(const x of p[f]||[])r[f].push(tag+' '+x);
    for(const e of p.events||[])if(e&&e.t){r.events.push({n:+e.n||ck.a,t:e.t});r.ch.push(+e.n||ck.a)}
    for(const t of p.toward||[]){
      if(!t||!t.to)continue;
      const tk=t.group?String(t.to).trim():keyOf(raw,t.to);
      const w=r.toward[tk]||(r.toward[tk]={to:tk,group:!!t.group,type:t.type||'',how:[],why:[],lines:[]});
      if(t.type&&!w.type)w.type=t.type;
      if(t.how)w.how.push(tag+' '+t.how);if(t.why)w.why.push(tag+' '+t.why);
      for(const l of t.lines||[])if(l&&l.q)w.lines.push({ctx:l.ctx||'',q:l.q,who:l.who||'',n:ck.a});
    }
  });
  for(const w of o.world||[])raw.world.push(tag+' '+w);
}
/** 每个人出场的章：章节出场名单 + 他做事的章。 */
function castMap(b){
  const raw=rawOf(b),m={};
  b.chapters.forEach((c,i)=>(c.cast||[]).forEach(n=>{const k=keyOf(raw,n);(m[k]=m[k]||new Set()).add(i+1)}));
  for(const k in raw.people)for(const n of raw.people[k].ch)(m[k]=m[k]||new Set()).add(n);
  return m;
}
const cap=(a,n)=>a.length>n?a.slice(0,n):a;
function material(b,k){
  const r=rawOf(b).people[k];
  const tw=Object.values(r.toward).sort((x,y)=>(y.how.length+y.lines.length)-(x.how.length+x.lines.length)).slice(0,12)
    .map(w=>({to:w.to,group:w.group,type:w.type,how:cap(w.how,6),why:cap(w.why,6),lines:cap(w.lines,6)}));
  const m={name:r.name,aliases:r.aliases,titles:cap(r.titles,10),gender:cap(r.gender,4),age:cap(r.age,4),height:cap(r.height,4),
    bg:cap(r.bg,12),look:cap(r.look,12),pers:cap(r.pers,16),events:cap(r.events,40),toward:tw};
  while(JSON.stringify(m).length>18000&&(m.events.length>10||m.toward.length>4)){m.events=m.events.slice(0,Math.max(10,m.events.length-10));m.toward=m.toward.slice(0,Math.max(4,m.toward.length-2))}
  return m;
}

/* ---------- 定稿 ---------- */
const hue=s=>{let h=0;for(const ch of s)h=(h*31+ch.charCodeAt(0))%360;return h};
const glyph=n=>{const a=[...n];return a.length===3?a[1]:a.length===2?a[1]:a[0]};
function skeleton(b){
  const raw=rawOf(b),cm=castMap(b),chars={};
  for(const k in raw.people){
    const r=raw.people[k],ch=[...(cm[k]||[])].sort((x,y)=>x-y);
    if(!ch.length&&!r.events.length&&!Object.keys(r.toward).length)continue;
    const old=b.chars?.[k];
    chars[k]=Object.assign({n:k,a:glyph(k),h:hue(k),r:'',al:cap(r.aliases,6),ti:cap(r.titles,8),ch,
      tl:Object.fromEntries(r.events.map(e=>[e.n,e.t])),
      rel:Object.values(r.toward).filter(w=>!w.group).map(w=>[w.to,w.type||''])},old&&old.fx?{r:old.r,fx:old.fx,bg:old.bg,build:old.build,pers:old.pers,rels:old.rels,tl:old.tl}:{});
  }
  b.chars=chars;
  b.chapters.forEach(c=>{c.cast=uniq((c.cast||[]).map(n=>keyOf(raw,n))).filter(n=>chars[n])});
  b.order=Object.keys(chars).sort((x,y)=>chars[y].ch.length-chars[x].ch.length||(chars[x].ch[0]||0)-(chars[y].ch[0]||0));
}
/** 卡片里的原句逐字核对；章号以原文里真找到的位置为准。 */
function qclean(b,s){return String(s||'').replace(/<q>([\s\S]*?)<\/q>/g,(m,q)=>locate(b,q)?`<q>${q}</q>`:q)}
function fixCard(b,k,o){
  const raw=rawOf(b),c=b.chars[k],first=c.ch[0]||1;
  const fx={};for(const f of ['gender','age','height']){const v=o.fx?.[f];if(Array.isArray(v)&&v[0])fx[f]=[String(v[0]),v[1]==='推断'?'推断':'明示',+v[2]||first]}
  const sec=x=>x&&x.v?{v:String(x.v),d:qclean(b,x.d),s:(x.s||[]).map(([n,q])=>{const at=locate(b,q);return at?[at,q]:null}).filter(Boolean).slice(0,4)}:null;
  const rels=(o.rels||[]).slice(0,8).map(r=>{
    let to=r.to?keyOf(raw,r.to):'',g=r.g||'';
    if(to&&!b.chars[to]){g=g||r.to;to=''}
    if(!to&&!g)return null;
    return {to:to||undefined,g:to?undefined:g,type:r.type||'',v:r.v||'',why:qclean(b,r.why),tone:r.tone||'',
      att:(r.att||[]).filter(a=>a&&a.v).map(a=>({v:a.v,d:qclean(b,a.d),c:+a.c||first})),
      sc:(r.sc||[]).map(x=>{const at=x&&locate(b,x.q);return at?{ctx:x.ctx||'',q:x.q,who:x.who||c.n,c:at}:null}).filter(Boolean).slice(0,3)};
  }).filter(Boolean);
  Object.assign(c,{r:o.r||c.r||'',fx,bg:sec(o.bg)||{v:'',d:'',s:[]},build:sec(o.build)||{v:'',d:'',s:[]},pers:sec(o.pers)||{v:'',d:'',s:[]},rels});
  if(o.tl&&typeof o.tl==='object')for(const n in o.tl)if(+n)c.tl[+n]=o.tl[n];
}

/* ---------- 调度 ---------- */
function setProg(b){
  if(B!==b)return;
  const p=b.prog;
  $('#runLbl').textContent=b._busy?`分析中 ${p.done}/${p.total}`:p.done?`继续分析 ${p.done}/${p.total}`:'用模型分析本书';
  $('#runBar').style.transform=`scaleX(${p.total?p.done/p.total:0})`;
}
async function pool(items,n,fn,b){
  let i=0;
  const worker=async()=>{while(i<items.length&&!b._stop){const it=items[i++];await fn(it)}};
  await Promise.all(Array.from({length:Math.min(n,items.length)},worker));
}
function picksForCards(b){
  const short=b.chapters.length<=3;
  return b.order.filter(k=>short||b.chars[k].ch.length>=2).slice(0,short?12:30);
}
async function analyze(b){
  const text=await textOf(b);index(b,text);
  const raw=rawOf(b);
  const cks=chunks(b,text);
  raw.done=raw.done||{};
  // 预估总量：段数 + 卡片数（成卡前按 30 估）+ 世界 1
  const est=()=>cks.length+(raw.cards?raw.cards.length:Math.min(30,Object.keys(raw.people).length||30))+1;
  b.prog={done:Object.keys(raw.done).length+(raw.cardsDone?raw.cardsDone.length:0)+(b.world?1:0),total:est()};setProg(b);
  // 一、逐段抽取
  const todo=cks.map((ck,i)=>({...ck,key:ck.a+'-'+ck.b+'-'+i})).filter(ck=>!raw.done[ck.key]);
  const run1=async(ck,depth=0)=>{
    try{absorb(b,ck,await askJSON(b,SYS,p1(b,ck),6000))}
    catch(e){
      if(e.fatal||e.stop)throw e;
      if(depth<2&&ck.text.length>3000){const h=ck.text.length>>1,cut=ck.text.indexOf('\n',h);const at=cut>0?cut:h;
        await run1({...ck,text:ck.text.slice(0,at)},depth+1);await run1({...ck,text:ck.text.slice(at)},depth+1);return;}
    }
    if(depth===0){raw.done[ck.key]=1;b.prog.done++;b.prog.total=est();setProg(b);persist(b)}
  };
  await pool(todo,3,run1,b);
  if(b._stop)return;
  // 二、成卡
  skeleton(b);persist(b);
  if(B===b){openBook(b,true);}
  raw.cards=raw.cards||picksForCards(b);raw.cardsDone=raw.cardsDone||[];
  b.prog.total=est();setProg(b);
  const left=raw.cards.filter(k=>!raw.cardsDone.includes(k)&&raw.people[k]);
  await pool(left,3,async k=>{
    try{fixCard(b,k,await askJSON(b,SYS,p2(b,k,material(b,k)),7000))}catch(e){if(e.fatal||e.stop)throw e}
    raw.cardsDone.push(k);b.prog.done++;setProg(b);persist(b);lightUp(b,k);
  },b);
  if(b._stop)return;
  // 三、世界
  if(!b.world){
    try{
      const notes={chapters:b.chapters.slice(0,60).map((c,i)=>({n:i+1,sum:c.sum||''})),world:cap(raw.world,40)};
      const o=await askJSON(b,SYS,p3(b,notes),3000);
      b.world={sum:String(o.sum||''),items:(o.items||[]).filter(x=>Array.isArray(x)&&x[0]&&x[1]).map(([k,t,src])=>{
        const q=Array.isArray(src)?src[1]:'';const at=q?locate(b,q):0;return [k,t,at?[at,q]:[0,'']]})};
    }catch(e){if(e.fatal||e.stop)throw e;b.world={sum:'',items:[]}}
    if(!b.world.sum&&!b.world.items.length)b.world=null;
    b.prog.done++;
  }
  b.done=true;delete b.raw;b.prog={done:b.prog.total,total:b.prog.total};
  persist(b);
}
function lightUp(b,k){
  if(B!==b)return;
  const el=$('#rail').querySelector(`.ri[data-id="${CSS.escape(k)}"]`);
  if(el)el.classList.toggle('pend',!ready(k));
  if(sel===k){renderCard();$('#cardwrap').querySelectorAll('.tile').forEach(t=>t.classList.add('fresh'))}
}
function resetAnalysis(b){
  if(b.sample)return;
  b._stop=true;
  setTimeout(()=>{
    b.chars={};b.order=[];b.world=null;b.done=false;delete b.raw;b.prog={done:0,total:0};
    b.chapters.forEach(c=>{delete c.sum;delete c.ev;c.cast=[]});
    b._stop=false;b._busy=false;persist(b);if(B===b)openBook(b,true);
  },300);
}
function runState(){
  const b=B,rw=$('#runWrap'),rb=$('#runBtn');
  const done=b.sample?b.analyzed>=b.chapters.length:!!b.done;
  rw.classList.toggle('gone',done);rb.className='run';rb.disabled=false;
  if(b.sample){$('#runLbl').textContent=b.analyzed?`继续分析 ${b.analyzed}/${b.chapters.length}`:'用模型分析本书';$('#runBar').style.transform=`scaleX(${b.analyzed/b.chapters.length})`;}
  else{b.prog=b.prog||{done:0,total:0};setProg(b);}
}
async function finishRun(b){
  if(B!==b)return;
  stats();
  $('#runLbl').textContent='完成';
  await wait(500);
  $('#runBtn').classList.add('done');
  await wait(200);
  $('#runWrap').classList.add('gone');
  openBook(b,true);
}
$('#runBtn').onclick=async()=>{
  const b=B,btn=$('#runBtn');
  if(b._busy){b._stop=true;btn.classList.remove('err');$('#runLbl').textContent='停下了';return;}
  if(b.sample)return runSample(b);
  b._busy=true;b._stop=false;btn.classList.remove('err');setProg(b);
  try{await analyze(b);b._busy=false;if(b.done)finishRun(b);else setProg(b);}
  catch(e){
    b._busy=false;b._stop=true;persist(b);
    if(B===b){setProg(b);if(!e.stop){btn.classList.add('err');$('#runLbl').textContent='没分析完 · 点这里接着来';}}
    if(!e.stop)toast(String(e.message||e).slice(0,60));
  }
};
/* 示例书：资料是写好的，按章亮起来演示一遍 */
async function runSample(b){
  const btn=$('#runBtn');if(b._busy)return;b._busy=true;
  const n=b.chapters.length;
  for(let i=b.analyzed;i<n;i++){
    $('#runLbl').textContent=`分析中 ${i}/${n}`;
    await wait(620);
    if(B!==b){b._busy=false;return;}
    b.analyzed=i+1;persist(b);
    $('#runBar').style.transform=`scaleX(${(i+1)/n})`;
    $('#runLbl').textContent=`分析中 ${i+1}/${n}`;
    $('#rail').querySelectorAll('.ri:not(.world)').forEach(el=>{
      const was=el.classList.contains('pend');el.classList.toggle('pend',!ready(el.dataset.id));
      if(was&&!el.classList.contains('pend')&&el.dataset.id===sel){renderCard();$('#cardwrap').querySelectorAll('.tile').forEach(t=>t.classList.add('fresh'))}
    });
  }
  b._busy=false;b.done=true;persist(b);
  stats();if(sel&&sel!=='world'&&!B.chars[sel].fx)renderCard();
  $('#runLbl').textContent='完成';await wait(500);btn.classList.add('done');await wait(200);$('#runWrap').classList.add('gone');
}
