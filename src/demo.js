/* 仅原型：模拟模型接口，正式版不包含 */
providers.demo=['演示模型 · 仅原型体验','https://demo.invalid/v1','chat'];
{const real=window.fetch.bind(window);
window.fetch=async(url,opt={})=>{
  if(!String(url).startsWith('https://demo.invalid/'))return real(url,opt);
  const reply=o=>new Response(JSON.stringify(o),{status:200,headers:{'Content-Type':'application/json'}});
  if(String(url).endsWith('/models'))return reply({data:[{id:'demo-writer'}]});
  const prompt=JSON.parse(opt.body).messages[0].content;
  await new Promise((r,j)=>{const t=setTimeout(r,500+Math.random()*900);opt.signal?.addEventListener('abort',()=>{clearTimeout(t);j(new DOMException('aborted','AbortError'));});});
  if(!prompt.includes('林雨今年二十岁')&&Math.random()<.06)return new Response('{}',{status:429});
  const rows=[...prompt.matchAll(/^(\d+) \| (.*)$/gmu)].map(m=>[+m[1],m[2]]);
  const people=[];
  const hit=(re)=>rows.find(([,t])=>re.test(t));
  let r;
  if((r=hit(/林雨今年二十岁/)))people.push({name:'林雨',aliases:[],fields:{age:{value:'二十岁',detail:'',line:r[0],evidence:'林雨今年二十岁',basis:'原文明示',sources:[]}},relations:[]});
  if((r=hit(/沈知微结婚多年/)))people.push({name:'沈知微',aliases:[],fields:{background:{value:'曾在英国留学；父母出事后回到上海，接手家里的公司。',detail:'她在英国留学，父母出事后回到上海，接手家里的公司。',line:r[0],evidence:'她在英国留学，父母出事后回到上海，接手家里的公司。',basis:'原文明示',sources:[]}},relations:[]});
  if((r=hit(/姐，要是你不想说/))){const p=people.find(x=>x.name==='沈知微');const t={title:'姐',by:'林见鹿',line:r[0],evidence:'姐，要是你不想说'};if(p)p.titles=[t];else people.push({name:'沈知微',aliases:[],fields:{},relations:[],titles:[t]});}
  if((r=hit(/林见鹿今年二十三岁/)))people.push({name:'林见鹿',aliases:[],fields:{background:{value:'由外婆带大，毕业后在旧书店工作。',detail:'',line:r[0],evidence:'她从小由外婆带大，毕业后在旧书店工作。',basis:'原文明示',sources:[]}},relations:[]});
  const hints=(prompt.match(/本段本地候选[^：]*：(\[.*?\])\n带行号/su)||[])[1];
  try{for(const h of JSON.parse(hints||'[]'))if(!people.some(p=>p.name===h.name)&&rows.some(([,t])=>t.includes(h.name)))people.push({name:h.name,aliases:[],fields:{},relations:[]});}catch{}
  return reply({choices:[{message:{content:JSON.stringify({people})}}]});
};}
