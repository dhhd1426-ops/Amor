'use strict';
const providers={
  openai:['OpenAI','https://api.openai.com/v1','responses'],
  deepseek:['DeepSeek','https://api.deepseek.com','chat'],
  claude:['Anthropic · Claude','https://api.anthropic.com/v1','claude'],
  gemini:['Google · Gemini','https://generativelanguage.googleapis.com/v1beta','gemini'],
  openrouter:['OpenRouter','https://openrouter.ai/api/v1','chat'],
  qwen:['阿里云百炼 · 通义千问（北京）','https://dashscope.aliyuncs.com/compatible-mode/v1','chat'],
  moonshot:['月之暗面 · Kimi','https://api.moonshot.cn/v1','chat'],
  silicon:['硅基流动','https://api.siliconflow.cn/v1','chat'],
  custom:['自定义供应商','','chat']
};
function apiAddress(value){
  const url=new URL(value.trim());
  if(url.protocol!=='https:'||url.username||url.password||url.search||url.hash)throw new Error('请填写 HTTPS API 基础地址。');
  return url.href.replace(/\/+$/,'').replace(/\/(chat\/completions|responses|messages|models)$/,'');
}
async function apiRequest(c,path,signal,body){
  const headers=c.protocol==='gemini'?{'x-goog-api-key':c.token}:c.protocol==='claude'?{'x-api-key':c.token,'anthropic-version':'2023-06-01','anthropic-dangerous-direct-browser-access':'true'}:{Authorization:'Bearer '+c.token};
  if(body)headers['Content-Type']='application/json';
  const response=await fetch(c.endpoint+path,{method:body?'POST':'GET',headers,body:body?JSON.stringify(body):undefined,signal,redirect:'error',cache:'no-store',credentials:'omit'});
  if(!response.ok){
    const why={400:'请求参数或模型不受此接口支持',401:'密钥无效或已过期',402:'账户余额不足',403:'当前密钥没有权限',404:'接口地址或模型不存在',429:'请求受限或配额不足'};
    throw new Error('HTTP '+response.status+' · '+(why[response.status]||'供应商请求失败'));
  }
  return response.json();
}
async function discoverModels(c,signal){
  const ids=new Set(),visited=new Set();
  let path=c.provider==='silicon'?'/models?type=text&sub_type=chat':'/models';
  while(path){
    if(visited.has(path))throw new Error('供应商返回了重复的模型列表分页。');
    visited.add(path);
    const data=await apiRequest(c,path,signal);
    const models=c.protocol==='gemini'?data.models:data.data;
    if(!Array.isArray(models))throw new Error('接口未返回有效模型列表，请检查地址和协议。');
    for(const m of models){
      if(!m||typeof m!=='object')continue;
      if(c.protocol==='gemini'&&!m.supportedGenerationMethods?.includes('generateContent'))continue;
      const id=c.protocol==='gemini'?m.name?.replace(/^models\//,''):m.id;
      if(typeof id!=='string'||!id)continue;
      if(m.architecture?.output_modalities&&!m.architecture.output_modalities.includes('text'))continue;
      if(/(?:embedding|embed-|rerank|moderation|whisper|transcri(?:be|ption)|dall-e|gpt-image|imagen-|veo-|text-to-speech|(?:^|-)tts(?:-|$)|realtime|native-audio|image-generation)/i.test(id))continue;
      ids.add(id);
    }
    path=c.protocol==='gemini'&&data.nextPageToken?'/models?pageToken='+encodeURIComponent(data.nextPageToken):
      c.protocol==='claude'&&data.has_more&&data.last_id?'/models?after_id='+encodeURIComponent(data.last_id):'';
  }
  if(!ids.size)throw new Error('供应商未返回可选的文本模型。');
  return [...ids].sort();
}
class TruncatedError extends Error{constructor(){super('模型输出达到长度上限');this.name='TruncatedError';}}
const JSON_MODE=new Set(['deepseek','openai','qwen','moonshot']);
function outputLimit(c){
  if(c.max)return c.max;
  if(c.protocol==='claude')return 8192;
  if(c.provider==='deepseek'&&!/reason|r1/i.test(c.model))return 8192;
  return undefined;
}
async function generateText(c,system,prompt,signal){
  try{return await requestText(c,system,prompt,signal,!c.noJson);}
  catch(err){
    if(!c.noJson&&/HTTP 400|HTTP 422/.test(err?.message||'')&&jsonCapable(c)){c.noJson=true;return requestText(c,system,prompt,signal,false);}
    throw err;
  }
}
const jsonCapable=c=>c.protocol==='gemini'||c.protocol==='responses'||(c.protocol==='chat'&&JSON_MODE.has(c.provider));
async function requestText(c,system,prompt,signal,json){
  let data,text,cut=false;
  const params=c.temp===undefined?{}:{temperature:c.temp};
  const max=outputLimit(c);
  json=json&&jsonCapable(c);
  if(c.protocol==='claude'){
    data=await apiRequest(c,'/messages',signal,{model:c.model,...params,max_tokens:max||8192,system,messages:[{role:'user',content:prompt}]});
    text=data.content?.filter(x=>x.type==='text').map(x=>x.text).join('');
    cut=data.stop_reason==='max_tokens';
  }else if(c.protocol==='gemini'){
    data=await apiRequest(c,'/models/'+encodeURIComponent(c.model)+':generateContent',signal,{generationConfig:{...params,...(max?{maxOutputTokens:max}:{}),...(json?{responseMimeType:'application/json'}:{})},contents:[{role:'user',parts:[{text:system+'\n\n'+prompt}]}]});
    text=data.candidates?.[0]?.content?.parts?.filter(x=>!x.thought).map(x=>x.text||'').join('');
    cut=data.candidates?.[0]?.finishReason==='MAX_TOKENS';
  }else if(c.protocol==='responses'){
    data=await apiRequest(c,'/responses',signal,{model:c.model,...params,...(max?{max_output_tokens:max}:{}),...(json?{text:{format:{type:'json_object'}}}:{}),input:system+'\n\n'+prompt,store:false});
    text=data.output?.flatMap(x=>x.content||[]).filter(x=>x.type==='output_text').map(x=>x.text).join('');
    cut=data.status==='incomplete'&&data.incomplete_details?.reason==='max_output_tokens';
  }else{
    data=await apiRequest(c,'/chat/completions',signal,{model:c.model,...params,...(max?{max_tokens:max}:{}),...(json?{response_format:{type:'json_object'}}:{}),messages:[{role:'system',content:system},{role:'user',content:prompt}]});
    const content=data.choices?.[0]?.message?.content;
    text=typeof content==='string'?content:Array.isArray(content)?content.map(x=>x.text||'').join(''):'';
    cut=data.choices?.[0]?.finish_reason==='length';
  }
  if(cut)throw new TruncatedError();
  if(!text?.trim())throw Object.assign(new Error('模型返回了空内容'),{name:'EmptyError'});
  return text.trim();
}
