/* ================= 文件读取与切章 ================= */
function decode(buf){try{return new TextDecoder('utf-8',{fatal:true}).decode(buf)}catch(e){return new TextDecoder('gb18030').decode(buf)}}
/**
 * 切章：第X章/回/节/卷、Chapter N、序章/楔子/尾声/番外 各占一行算章节名；
 * 每章记下标题所在行号（line），分析时按行号取正文、找原句所在章。
 * 一个章节名都没有：按 5000 字左右在行尾切段。
 */
function splitChapters(text){
  const lines=text.replace(/\r\n?/g,'\n').split('\n');
  const re=/^[ \t　]*((?:第[0-9０-９零〇一二三四五六七八九十百千两]+[章回节卷集部篇]|chapter\s*\d+|序章|序言|楔子|引子|尾声|后记|番外)[^\n]{0,30})[ \t　]*$/i;
  const heads=[];
  lines.forEach((l,i)=>{const m=l.match(re);if(m&&l.length<=40)heads.push({i,t:m[1].trim()})});
  const out=[];
  if(!heads.length){
    let start=0,acc=0;
    lines.forEach((l,i)=>{acc+=l.replace(/\s/g,'').length;if(acc>=5000||i===lines.length-1){out.push({t:[`第 ${out.length+1} 段`,''],wc:acc,line:start,head:false,cast:[]});start=i+1;acc=0}});
    return {chapters:out.length?out:[{t:['（空文件）',''],wc:0,line:0,head:false,cast:[]}],unit:'段'};
  }
  // 章节名之前还有一大段（序、引子没写标题）：单独算一章
  const pre=lines.slice(0,heads[0].i).join('').replace(/\s/g,'').length;
  if(pre>800)out.push({t:['开篇',''],wc:pre,line:0,head:false,cast:[]});
  heads.forEach((h,k)=>{
    const end=k+1<heads.length?heads[k+1].i:lines.length;
    const wc=lines.slice(h.i+1,end).join('').replace(/\s/g,'').length;
    const sp=h.t.match(/^(第\S+?[章回节卷集部篇]|chapter\s*\d+)\s*(.*)$/i);
    let title=sp?(sp[2]||sp[1]):h.t,sub='';
    // 回目两句（甄士隐梦幻识通灵　贾雨村风尘怀闺秀）分两行
    const two=title.split(/[ \t　]{1,}/).filter(Boolean);
    if(two.length===2&&two[0].length>=4&&two[1].length>=4){title=two[0];sub=two[1]}
    out.push({t:[title,sub],wc,line:h.i,head:true,cast:[]});
  });
  const hui=heads.filter(h=>/第\S+?回/.test(h.t)).length;
  return {chapters:out,unit:hui>heads.length/2?'回':'章'};
}
async function epubText(f){
  const buf=new Uint8Array(await f.arrayBuffer());const dv=new DataView(buf.buffer);
  let eocd=buf.length-22;while(eocd>0&&dv.getUint32(eocd,true)!==0x06054b50)eocd--;
  let p=dv.getUint32(eocd+16,true);const n=dv.getUint16(eocd+10,true);const files=[];
  for(let k=0;k<n;k++){const method=dv.getUint16(p+10,true),csize=dv.getUint32(p+20,true),nl=dv.getUint16(p+28,true),xl=dv.getUint16(p+30,true),cl=dv.getUint16(p+32,true),off=dv.getUint32(p+42,true);
    files.push({name:new TextDecoder().decode(buf.subarray(p+46,p+46+nl)),method,csize,off});p+=46+nl+xl+cl;}
  let text='';
  for(const fi of files.filter(x=>/\.x?html?$/i.test(x.name)).sort((a,b)=>a.name.localeCompare(b.name,undefined,{numeric:true}))){
    const lh=fi.off,ln=dv.getUint16(lh+26,true),lx=dv.getUint16(lh+28,true);const raw=buf.subarray(lh+30+ln+lx,lh+30+ln+lx+fi.csize);
    const data=fi.method===0?raw:new Uint8Array(await new Response(new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).arrayBuffer());
    const doc=new DOMParser().parseFromString(new TextDecoder().decode(data),'text/html');
    doc.querySelectorAll('h1,h2,h3,h4,p').forEach(x=>x.append('\n'));text+='\n'+doc.body.textContent;}
  return text;
}
