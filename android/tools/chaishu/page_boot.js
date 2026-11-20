/* ================= 启动 ================= */
// 引文：模型和示例里的 <q>…</q> 显示成「…」，其余一律转义
function qhtml(s){return esc(s||'').replace(/&lt;q&gt;/g,'<q>').replace(/&lt;\/q&gt;/g,'</q>')}
(()=>{
  const q=new URLSearchParams(location.search);
  document.body.dataset.skin=q.get('skin')==='light'?'light':'dark';
  const px=v=>Math.max(0,Math.min(80,+v||0))+'px';
  document.documentElement.style.setProperty('--top',px(q.get('top')));
  document.documentElement.style.setProperty('--bottom',px(q.get('bottom')));
})();
// App 那边调这几个
window.cs={
  /** 系统返回键：先关弹层、回上一层；到底了返回 false，由 App 关掉整页。 */
  back(){ if(isOpen){ if(stack.length>1)go(null,true); else hideSheet(); return true; } return false; },
  /** 选好的文件已经读成文字存好：{ok,id,name} 或 {ok:false,why}；null 是没选。 */
  async picked(r){
    if(!r)return;
    if(!r.ok)return toast(r.why||'没读出来');
    try{const res=await fetch('/text/'+r.id);fromText(r.name,await res.text(),r.id)}catch(e){toast('没读出来')}
  },
  reply(id,ok,text,fatal){onReply(id,ok,text,fatal)},
};
restore();
