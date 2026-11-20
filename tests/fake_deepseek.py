import json,re,random,asyncio
random.seed(7)
stats={'calls':0,'429':0,'500':0,'trunc':0,'fence':0,'empty':0,'max_seen':set(),'json_mode':0,'bad':[]}
def tokens(s): return len(s)//1.6
def build(prompt):
    rows=[(int(m.group(1)),m.group(2)) for m in re.finditer(r'^(\d+) \| (.*)$',prompt,re.M)]
    hint=re.search(r'：(\[.*?\])\n带行号',prompt,re.S)
    names=[h['name'] for h in json.loads(hint.group(1))] if hint else []
    if '林雨今年二十岁' in prompt: names=['林雨']
    people=[]
    for n in names:
        hits=[(i,t) for i,t in rows if n in t]
        if not hits: continue
        i,t=hits[0]
        ev=t[t.index(n):t.index(n)+min(40,len(t)-t.index(n))]
        f={'value':'（模型概括）'+t[:30],'detail':t[:200],'line':i,'evidence':ev,'basis':'原文明示','sources':[{'line':j,'quote':tt[:20]} for j,tt in hits[1:4]]}
        fields={'background':f}
        if n=='林雨': fields={'age':{'value':'二十岁','detail':'','line':i,'evidence':'林雨今年二十岁','basis':'原文明示','sources':[]}}
        rels=[]
        for m in names:
            if m==n: continue
            both=[(i2,t2) for i2,t2 in hits if m in t2]
            if both:
                i2,t2=both[0]; q=re.findall(r'“([^”]{1,80})”',t2)
                rels.append({'to':m,'type':'相识','attitudes':[{'value':'（模型）在意对方','detail':t2[:120],'line':i2,'evidence':t2[:15],'basis':'文本推断','sources':[]}],
                             'scenes':[{'quote':q[0],'line':i2,'speaker':n if t2.startswith(n) else '','context':''}] if q else []})
        titles=[]
        for i3,t3 in hits[:3]:
            for tt in re.findall(r'「([^」]{2,6})」',t3)[:1]: titles.append({'title':tt,'by':'叙述','line':i3,'evidence':t3[max(0,t3.index(tt)-3):t3.index(tt)+len(tt)+1]})
        people.append({'name':n,'aliases':[],'fields':fields,'relations':rels,'titles':titles,'pad':'说明'*40*len(hits)})
    return json.dumps({'people':people},ensure_ascii=False)
async def handle(route):
    req=route.request; url=req.url
    if url.endswith('/models'):
        await route.fulfill(status=200,content_type='application/json',body=json.dumps({'data':[{'id':'deepseek-chat'},{'id':'deepseek-reasoner'}]}));return
    body=json.loads(req.post_data); stats['calls']+=1
    hdr=req.headers.get('authorization','')
    if not hdr.startswith('Bearer '): await route.fulfill(status=401,body='{}');return
    mt=body.get('max_tokens'); stats['max_seen'].add(mt)
    if mt and mt>8192: stats['bad'].append('max_tokens>8192'); await route.fulfill(status=400,body='{}');return
    msgs=body['messages']; alltext=''.join(m['content'] for m in msgs)
    if body.get('response_format',{}).get('type')=='json_object':
        stats['json_mode']+=1
        if 'json' not in alltext.lower(): stats['bad'].append('json word'); await route.fulfill(status=400,body='{}');return
    await asyncio.sleep(random.uniform(.2,.9))
    r=random.random()
    if r<.05: stats['429']+=1; await route.fulfill(status=429,body='{}');return
    if r<.08: stats['500']+=1; await route.fulfill(status=503,body='{}');return
    out=build(msgs[-1]['content']); fin='stop'
    limit=mt or 4096
    if tokens(out)>limit: out=out[:int(limit*1.6)]; fin='length'; stats['trunc']+=1
    elif r<.10: out='```json\n'+out+'\n```'; stats['fence']+=1
    elif r<.11: out=''; stats['empty']+=1
    await route.fulfill(status=200,content_type='application/json',body=json.dumps({'choices':[{'message':{'role':'assistant','content':out},'finish_reason':fin}]},ensure_ascii=False))
