/* ================= 书架：存在本机 ================= */
// App 里经 window.CS 存成文件（filesDir/chaishu/<id>.json）；网页预览时退回 localStorage
const NATIVE=typeof window.CS!=='undefined';
const books=[];
const LS='chaishu.v2';
function strip(b){const o={...b};for(const k of Object.keys(o))if(k.startsWith('_'))delete o[k];return o}
function persist(b){
  if(!b||!b.id)return;
  const json=JSON.stringify(strip(b));
  if(NATIVE){try{CS.save(b.id,json)}catch(e){}}
  else{try{const all=JSON.parse(localStorage.getItem(LS)||'{}');all[b.id]=JSON.parse(json);localStorage.setItem(LS,JSON.stringify(all))}catch(e){}}
}
function remember(){try{localStorage.setItem(LS+'.cur',B?.id||'')}catch(e){}}
function normBook(b){
  b.chapters=(b.chapters||[]).map(c=>({...c,t:Array.isArray(c.t)?c.t:[String(c.t||''),'']}));
  b.order=b.order||[];b.unit=b.unit||'章';
  if(b.chars)for(const k in b.chars){const c=b.chars[k];c.ch=c.ch||[];c.al=c.al||[];c.ti=c.ti||[];c.rel=c.rel||[];c.tl=c.tl||{};c.n=c.n||k}
  return b;
}
function restore(){
  let list=[];
  if(NATIVE){try{list=JSON.parse(CS.list())}catch(e){}}
  else{try{list=Object.values(JSON.parse(localStorage.getItem(LS)||'{}'))}catch(e){}}
  list.forEach(x=>{try{books.push(normBook(x))}catch(e){}});
  let cur=null;try{cur=localStorage.getItem(LS+'.cur')}catch(e){}
  const b=books.find(x=>x.id===cur)||books[0];
  if(b)openBook(b,true);
}
function addBook(b){books.unshift(b);persist(b);}
function dropBook(b){
  const i=books.indexOf(b);if(i>=0)books.splice(i,1);
  b._stop=true;
  if(NATIVE){try{CS.remove(b.id)}catch(e){}}
  else{try{const all=JSON.parse(localStorage.getItem(LS)||'{}');delete all[b.id];localStorage.setItem(LS,JSON.stringify(all))}catch(e){}}
  if(b===B){if(books[0])openBook(books[0]);else showEmpty();}
}
function showEmpty(){
  B=null;sel=null;remember();
  app.classList.remove('has-book');
  $('#rail').querySelectorAll('.ri,.rail-sep').forEach(x=>x.remove());$('#railInd').style.opacity=0;
  $('#bookArea').hidden=true;$('#empty').hidden=false;
}
const coverBg=b=>{let h=b.sample?8:0;if(!b.sample)for(const ch of b.title)h=(h*31+ch.charCodeAt(0))%360;return `background:linear-gradient(160deg,hsl(${h} 38% 38%),hsl(${h} 42% 24%))`};
const bookSub=b=>{
  const p=b.sample?{done:b.analyzed,total:b.chapters.length}:(b.prog||{done:0,total:0});
  const st=(b.sample?b.analyzed>=b.chapters.length:b.done)?'已分析':p.done?`分析到 ${p.done}/${p.total}`:'未分析';
  return [b.chapters.length+' '+(b.unit||'章'),b.order?.length?b.order.length+' 人':null,st].filter(Boolean).join(' · ');
};

/* ================= 页头按钮 ================= */
$('#backBtn').onclick=()=>{ if(isOpen)return hideSheet(); if(NATIVE)CS.close(); };
$('#bookSw').onclick=()=>openSheet({k:'books'},$('#bookSw'));
$('#importBtn').onclick=()=>openSheet({k:'import'},$('#importBtn'));
$('#moreBtn').onclick=()=>{ if(B)openSheet({k:'more'},$('#moreBtn')); };

/* 「⋯」：重新分析、删除这本（删除点两次） */
let delArm=0;
function moreHTML(){
  delArm=0;
  return `<div style="display:flex;flex-direction:column;gap:8px;padding:2px 0 18px">
    ${B.sample?'':`<button class="btn2" data-act="redo">重新分析</button>`}
    <button class="btn2" data-act="del" id="delBtn">删除这本书</button></div>`;
}
function moreAct(t){
  const a=t.dataset.act;
  if(a==='redo'){hideSheet();resetAnalysis(B);return true;}
  if(a==='del'){
    if(Date.now()-delArm>3000){delArm=Date.now();t.textContent='再点一次删除';t.classList.add('danger');
      setTimeout(()=>{if(Date.now()-delArm>=3000&&document.contains(t)){t.textContent='删除这本书';t.classList.remove('danger')}},3000*K);return true;}
    const b=B;hideSheet(true);dropBook(b);toast(`删掉了《${b.title}》`);return true;
  }
  return false;
}

/* ================= 导入 ================= */
$('#pickFile').onclick=()=>pickFile();
$('#pickDemo').onclick=()=>loadDemo();
function pickFile(){ if(NATIVE)CS.pick(); else $('#file').click(); }
function loadDemo(){
  hideSheet(true);
  const had=books.find(x=>x.sample);
  if(had)return openBook(had);
  const b=makeDemo();addBook(b);openBook(b);
}
function makeDemo(){
  const b=structuredClone(HLM);
  b.id='sample';b.unit='回';
  b.analyzed=0;b.wc=b.chapters.reduce((s,c)=>s+c.wc,0);b.sample=true;
  for(const id in b.chars)b.chars[id].ch=[];
  b.chapters.forEach((c,i)=>c.cast.forEach(id=>b.chars[id].ch.push(i+1)));
  b.order=Object.keys(b.chars).sort((x,y)=>b.chars[y].ch.length-b.chars[x].ch.length||b.chars[x].ch[0]-b.chars[y].ch[0]);
  return b;
}
/* 读进来的文字 → 一本新书（还没分析） */
function fromText(name,text,id){
  text=String(text||'').replace(/\r\n?/g,'\n');
  const {chapters,unit}=splitChapters(text);
  const b={id:id||('b'+Date.now().toString(36)),title:name||'未命名',unit,chapters,wc:chapters.reduce((s,c)=>s+c.wc,0),chars:{},order:[],done:false,prog:{done:0,total:0}};
  b._text=text;
  addBook(b);openBook(b);
  toast(`切出 ${chapters.length} ${unit}`);
}
$('#file').onchange=async e=>{
  const f=e.target.files[0];e.target.value='';if(!f)return;
  hideSheet(true);
  let text='';try{text=f.name.toLowerCase().endsWith('.epub')?await epubText(f):decode(await f.arrayBuffer())}catch(err){}
  if(!text.trim())return toast('这个文件里读不出文字');
  fromText(f.name.replace(/\.[^.]+$/,''),text);
};
