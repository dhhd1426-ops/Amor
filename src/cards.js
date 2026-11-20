/* ---------- 酒馆角色卡（SillyTavern · Character Card V3 + V2 兼容） ---------- */
const CRC_TABLE=(()=>{const t=new Uint32Array(256);for(let n=0;n<256;n++){let c=n;for(let k=0;k<8;k++)c=c&1?0xEDB88320^(c>>>1):c>>>1;t[n]=c>>>0;}return t;})();
function crc32(bytes,crc=0xFFFFFFFF){for(let i=0;i<bytes.length;i++)crc=CRC_TABLE[(crc^bytes[i])&255]^(crc>>>8);return crc;}
const crcDone=c=>(c^0xFFFFFFFF)>>>0;
function b64utf8(str){const bytes=new TextEncoder().encode(str);let bin='';for(let i=0;i<bytes.length;i+=0x8000)bin+=String.fromCharCode.apply(null,bytes.subarray(i,i+0x8000));return btoa(bin);}
const clip=(s,n)=>s.length>n?s.slice(0,n-1)+'…':s;
const ownLines=p=>{
  const all=[...(p.soloScenes||[]),...p.relations.flatMap(r=>r.scenes)].filter(s=>s.speaker===p.name||(p.aliases||[]).includes(s.speaker));
  const seen=new Set();return all.filter(s=>{const k=s.line+'|'+s.quote;if(seen.has(k))return false;seen.add(k);return true;}).sort((a,b)=>a.line-b.line);
};
function fieldText(f){return f?(f.value+(f.detail&&f.detail!==f.value?'\n'+f.detail:'')):'';}
function cardData(p){
  const book=state.title,titles=(p.titles||[]).slice(0,10);
  const desc=['{{char}}，《'+book+'》中的人物。'];
  const call=unique([...(p.aliases||[]).filter(a=>a!==p.name),...titles.map(t=>t.title)]);
  if(call.length)desc.push('称呼：'+call.join('、'));
  for(const k of ['gender','age','height','build','background']){const f=p.fields[k];if(f)desc.push(labels[k]+'：'+fieldText(f).replace(/\n/g,'\n  '));}
  const rel=p.relations.slice(0,12).map(r=>r.to+'（'+r.type+'）');
  if(rel.length)desc.push('人际：'+rel.join('、'));
  const lines=ownLines(p);
  const say=s=>(s.context?'*'+s.context.replace(/[：:]\s*$/,'')+'*\n':'')+'“'+s.quote+'”';
  const first=lines[0]?say(lines[0]):(p.mentionsAt||[]).map(n=>state.lines[n-1]?.trim()).filter(Boolean).slice(0,1).map(t=>'*'+clip(t,300)+'*')[0]||'';
  const groups=[];
  for(const s of lines){const g=groups.at(-1);if(g&&s.line-g.at(-1).line<=3&&g.length<3)g.push(s);else groups.push([s]);}
  const mes=groups.slice(0,5).map(g=>'<START>\n'+g.map(s=>'{{char}}: '+(s.context?'*'+s.context.replace(/[：:]\s*$/,'')+'* ':'')+'“'+s.quote+'”').join('\n')).join('\n');
  const entries=p.relations.slice(0,40).map((r,i)=>{
    const other=state.people.find(x=>x.name===r.to);
    const keys=unique([r.to,...(other?.aliases||[]),...((other?.titles||[]).slice(0,3).map(t=>t.title))]).filter(k=>k&&k.length>=2);
    const att=r.attitudes.slice(-4).map(a=>'· '+a.value+'（'+sourceLabel(a.line)+'）').join('\n');
    const quote=r.scenes.filter(s=>s.speaker===p.name).slice(-2).map(s=>'{{char}}对'+r.to+'说：“'+s.quote+'”').join('\n');
    return{keys:keys.length?keys:[r.to],content:clip('{{char}}与'+r.to+'：'+r.type+'。'+(att?'\n{{char}}对'+r.to+'的态度：\n'+att:'')+(quote?'\n'+quote:''),900),extensions:{},enabled:true,insertion_order:100+i,case_sensitive:false,use_regex:false,name:r.to,priority:10,id:i,comment:p.name+' → '+r.to,selective:false,secondary_keys:[],constant:false,position:'after_char'};
  });
  const tags=unique(['人物志','《'+book+'》',...call.slice(0,6),...(p.customTags||[])]);
  const srcs=['gender','age','height','build','personality','background'].map(k=>p.fields[k]).filter(Boolean).flatMap(f=>f.sources||[f.line]);
  return{
    name:p.name,description:desc.join('\n'),personality:fieldText(p.fields.personality),
    scenario:'故事出自《'+book+'》。',first_mes:first,mes_example:mes,
    creator_notes:'由「人物志」从《'+book+'》'+(state.engine==='model'?'经模型分析':'本地提取')+'整理。资料依据原文'+(srcs.length?'第 '+unique(srcs).sort((a,b)=>a-b).slice(0,12).join('、')+' 行等':'')+'，可在酒馆内继续修改。',
    system_prompt:'',post_history_instructions:'',alternate_greetings:lines.slice(1,4).map(say),
    tags,creator:'人物志',character_version:'1.0',
    character_book:entries.length?{name:'《'+book+'》· '+p.name+'的人际',description:'',scan_depth:4,token_budget:1024,recursive_scanning:false,extensions:{},entries}:undefined,
    extensions:{renwuzhi:{book,engine:state.engine,version:13}}
  };
}
function cardV3(p){const d=cardData(p);const now=Math.floor(Date.now()/1000);return{spec:'chara_card_v3',spec_version:'3.0',data:{...d,group_only_greetings:[],creation_date:now,modification_date:now,source:[]}};}
function cardV2(p){const d=cardData(p);if(d.character_book)d.character_book={...d.character_book,entries:d.character_book.entries.map(({use_regex,...e})=>e)};return{spec:'chara_card_v2',spec_version:'2.0',data:d};}
async function portrait(p){
  const W=400,H=600,c=document.createElement('canvas');c.width=W*2;c.height=H*2;
  const g=c.getContext('2d');g.scale(2,2);
  const serif='"Noto Serif SC","Source Han Serif SC","Songti SC","STSong","SimSun",serif';
  const bg=g.createLinearGradient(0,0,0,H);bg.addColorStop(0,'#f7f4ec');bg.addColorStop(1,'#ebe5d6');g.fillStyle=bg;g.fillRect(0,0,W,H);
  const glow=g.createRadialGradient(W*.75,90,10,W*.75,90,260);glow.addColorStop(0,'rgba(163,56,42,.16)');glow.addColorStop(1,'rgba(163,56,42,0)');g.fillStyle=glow;g.fillRect(0,0,W,H);
  g.strokeStyle='#c9c0aa';g.lineWidth=1;g.strokeRect(14.5,14.5,W-29,H-29);
  g.save();g.translate(W/2,205);g.rotate(-3*Math.PI/180);
  g.shadowColor='rgba(163,56,42,.35)';g.shadowBlur=30;g.shadowOffsetY=14;
  g.fillStyle='#a3382a';g.beginPath();g.roundRect(-92,-92,184,184,20);g.fill();g.shadowColor='transparent';
  g.strokeStyle='rgba(255,248,242,.3)';g.lineWidth=2;g.beginPath();g.roundRect(-84,-84,168,168,15);g.stroke();
  g.fillStyle='#fff8f2';g.font='600 108px '+serif;g.textAlign='center';g.textBaseline='middle';g.fillText([...p.name][0],0,6);
  g.restore();
  g.fillStyle='#1c201d';g.textAlign='center';g.textBaseline='alphabetic';
  let size=52;g.font='600 '+size+'px '+serif;while(g.measureText(p.name).width>W-70&&size>26){size-=2;g.font='600 '+size+'px '+serif;}
  if('letterSpacing' in g)g.letterSpacing=Math.round(size*.12)+'px';
  g.fillText(p.name,W/2,390);
  if('letterSpacing' in g)g.letterSpacing='0px';
  const call=unique([...(p.aliases||[]).filter(a=>a!==p.name),...(p.titles||[]).map(t=>t.title)]).slice(0,3).join(' · ');
  if(call){g.fillStyle='#a3382a';g.font='500 17px '+serif;g.fillText(clip(call,22),W/2,428);}
  const brief=p.fields.background?.value||p.fields.personality?.value||'';
  if(brief){g.fillStyle='#474d48';g.font='400 15px '+serif;const chars=[...clip(brief,60)];let line='',y=470;
    for(const ch of chars){if(g.measureText(line+ch).width>W-90){g.fillText(line,W/2,y);y+=25;line='';if(y>520)break;}line+=ch;}if(line&&y<=520)g.fillText(line,W/2,y);}
  g.fillStyle='#7d8179';g.font='400 13px '+serif;g.fillText(clip('《'+state.title+'》',26),W/2,H-34);
  return new Promise(r=>c.toBlob(r,'image/png'));
}
function pngWithCard(png,p){
  const text=(key,val)=>{const kv=new TextEncoder().encode(key+'\0'+val),out=new Uint8Array(12+kv.length),dv=new DataView(out.buffer);
    dv.setUint32(0,kv.length);out.set([116,69,88,116],4);out.set(kv,8);dv.setUint32(8+kv.length,crcDone(crc32(out.subarray(4,8+kv.length))));return out;};
  const chunks=[text('chara',b64utf8(JSON.stringify(cardV2(p)))),text('ccv3',b64utf8(JSON.stringify(cardV3(p))))];
  const iend=png.length-12,size=chunks.reduce((n,c)=>n+c.length,0),out=new Uint8Array(png.length+size);
  out.set(png.subarray(0,iend));let o=iend;for(const c of chunks){out.set(c,o);o+=c.length;}out.set(png.subarray(iend),o);
  return out;
}
async function cardPng(p){return pngWithCard(new Uint8Array(await (await portrait(p)).arrayBuffer()),p);}
function zip(files){
  const enc=new TextEncoder(),parts=[],central=[];let offset=0;
  for(const f of files){
    const name=enc.encode(f.name),crc=crcDone(crc32(f.data)),h=new DataView(new ArrayBuffer(30));
    h.setUint32(0,0x04034b50,true);h.setUint16(4,20,true);h.setUint16(6,0x0800,true);h.setUint32(14,crc,true);h.setUint32(18,f.data.length,true);h.setUint32(22,f.data.length,true);h.setUint16(26,name.length,true);
    parts.push(new Uint8Array(h.buffer),name,f.data);
    const c=new DataView(new ArrayBuffer(46));
    c.setUint32(0,0x02014b50,true);c.setUint16(4,20,true);c.setUint16(6,20,true);c.setUint16(8,0x0800,true);c.setUint32(16,crc,true);c.setUint32(20,f.data.length,true);c.setUint32(24,f.data.length,true);c.setUint16(28,name.length,true);c.setUint32(42,offset,true);
    central.push(new Uint8Array(c.buffer),name);offset+=30+name.length+f.data.length;
  }
  const size=central.reduce((n,x)=>n+x.length,0),e=new DataView(new ArrayBuffer(22));
  e.setUint32(0,0x06054b50,true);e.setUint16(8,files.length,true);e.setUint16(10,files.length,true);e.setUint32(12,size,true);e.setUint32(16,offset,true);
  return new Blob([...parts,...central,new Uint8Array(e.buffer)],{type:'application/zip'});
}
const safeName=s=>s.replace(/[\\/:*?"<>|\s]+/g,'_').slice(0,80);
const MIME={png:'image/png',json:'application/json',zip:'application/zip',md:'text/markdown'};
async function saveFile(filename,data){
  if(window.RenwuzhiAndroid){
    const blob=data instanceof Blob?data:new Blob([data]);
    const b64=await new Promise((res,rej)=>{const r=new FileReader();r.onload=()=>res(String(r.result).split(',')[1]||'');r.onerror=rej;r.readAsDataURL(blob);});
    const ok=await new Promise(res=>{window.__androidSaved=v=>{window.__androidSaved=null;res(!!v);};window.RenwuzhiAndroid.save(filename,MIME[filename.split('.').pop()]||'application/octet-stream',b64);});
    if(ok)setStatus('已保存「'+filename+'」。');
    return ok;
  }
  try{
    const dl=window.claude?.use?await window.claude.use('downloads'):null;
    if(dl){await dl.save({filename,data});return true;}
  }catch(err){if(err?.code==='declined')return false;}
  const blob=data instanceof Blob?data:new Blob([data]);
  const href=URL.createObjectURL(blob),a=document.createElement('a');a.href=href;a.download=filename;document.body.append(a);a.click();a.remove();
  setTimeout(()=>URL.revokeObjectURL(href),4000);return true;
}
async function openExport(){
  const p=state.people.find(x=>x.name===state.selected);
  q('exportName').textContent=p?p.name:'';
  q('exportCurrent').hidden=!p;
  q('exportAllLabel').textContent='全书 '+state.people.length+' 位角色';
  q('exportPreview').removeAttribute('src');
  q('exportDialog').showModal();
  if(p){const blob=await portrait(p);q('exportPreview').src=URL.createObjectURL(blob);}
}
function markdownBlob(){
  let captured;const orig=URL.createObjectURL;
  URL.createObjectURL=b=>{captured=b;return 'blob:x';};
  const click=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){};
  try{exportCards();}finally{URL.createObjectURL=orig;HTMLAnchorElement.prototype.click=click;}
  return captured;
}
document.addEventListener('click',async e=>{
  const b=e.target.closest('[data-export]');if(!b)return;
  const kind=b.dataset.export,p=state.people.find(x=>x.name===state.selected);
  b.disabled=true;
  try{
    if(kind==='png'&&p)await saveFile(safeName(p.name)+'.png',new Blob([await cardPng(p)],{type:'image/png'}));
    if(kind==='json'&&p)await saveFile(safeName(p.name)+'.json',JSON.stringify(cardV3(p),null,2));
    if(kind==='zip'){
      const files=[];for(const [i,x] of state.people.entries()){b.textContent='正在生成 '+(i+1)+' / '+state.people.length;files.push({name:safeName(x.name)+'.png',data:await cardPng(x)});}
      await saveFile(safeName(state.title)+'-酒馆角色卡.zip',zip(files));
    }
    if(kind==='md')await saveFile(safeName(state.title)+'-角色卡.md',markdownBlob());
    q('exportDialog').close();
  }catch(err){setStatus('导出失败：'+(err?.message||err),true);}
  finally{b.disabled=false;if(kind==='zip')b.textContent='打包下载 PNG';}
});
