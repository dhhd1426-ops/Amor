
'use strict';
const sampleText = [
'第一章　雨夜来信',
'沈知微结婚多年，已经生了两个孩子。她比我高半个头，身形修长，窄肩撑起浅灰大衣，收紧的腰带束住衣身。她在英国留学，父母出事后回到上海，接手家里的公司。',
'林见鹿今年二十三岁，身高一米六五，体态轻盈，性格活泼。她从小由外婆带大，毕业后在旧书店工作。',
'周砚今年三十一岁，身高一米八六，身材高挑，性格沉稳。他曾在父亲的公司负责审计，如今独自开了一间事务所。',
'沈知微是林见鹿的姐姐。沈知微替林见鹿擦去袖口的雨水，只叮嘱她把外套扣好，没提自己在门外等了多久。',
'沈知微低头看向林见鹿：“你可以不帮我，但别替我认输。”',
'林见鹿拉住沈知微：“姐，要是你不想说，我就陪你等雨停。”',
'第二章　合伙人',
'周砚是沈知微的旧同事。沈知微对周砚的态度从警惕变为有限信任。',
'沈知微对周砚说：“账本可以给你，但公司的底牌还不行。”',
'周砚看着沈知微说：“等你查完这一页，再决定信不信我。”'
].join('\n\n');
const labels = {name:'姓名',gender:'性别',age:'年龄',height:'身高',build:'身材',personality:'性格',background:'背景'};
const unknown = '原文未明确';
const q = id => document.getElementById(id);
const matchText = (s, re) => s.match(re)?.[1]?.trim() || '';
const string = val => typeof val === 'string' ? val.trim() : '';
const field = (value, line, basis='原文明示', detail='', sources=[]) => ({value,line,basis,detail,sources:unique([line,...sources])});
const clean = value => string(value).replace(/[。；;，,]+$/u,'');
const unique = a => [...new Set(a)];
const keys = Object.keys(labels).filter(k => k !== 'name');
const surname = '殷胡刘高龙游岳冉燕邱樊墨南欧左尉长邝邬湛皇甫苗孟邵赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜谢邹喻柏窦章苏潘葛范彭韦马苗方俞任袁柳汤罗薛雷贺倪安常傅齐康伍余顾孟黄萧尹姚邵汪祁毛禹狄米贝明臧戴宋舒庞熊纪屈项祝董梁杜阮蓝季路江童温陆白石徐裴程邢叶黎夏许林唐凌云陆许简许宁段闻许许池谢许乔盛时许洛晏封薄容霍穆慕莫霍许许';
const personStart = '(?:欧阳|司马|上官|诸葛|慕容|东方|南宫|司徒|闻人|[' + surname + '])';
const follows = '说|问|答|道|笑|喊|叫|回道|低头|抬头|抬眼|看向|看着|拉住|走进|走来|站在|坐在|推开|望着|今年|现年|年方|年仅|年纪|的年龄|，|、|对|是|跟|和|与|轻声|闻言|摇头|点头|皱眉|叹了|转身|低声|并未|推门|进门|回到|穿着|披着|替|把|将|递给|接过|停下|靠在|走到|听见|沉默|结婚|已有|已经|比我|长得|一向|平时|从小|原本|身形|身材|身高|性格|出生|毕业|不爱|迟迟|伸手';
const namePattern = new RegExp('(?:^|[，。！？：；、\\s”」])(?:这时|随后|只见|忽然|此时|然后|眼看)?(' + personStart + '[\\p{Script=Han}]{1,2}?)(?=' + follows + ')','gu');
const secondary = new RegExp('(?:对|向|看向|看着|盯着|望着|拉住|叫住|陪着|跟着|跟|和|与)(' + personStart + '[\\p{Script=Han}]{1,2}?)(?=说|问|笑|的|[，。！？：；、\\s”」]|$)','gu');
const speech = /[“「『"]([^”」』"\n]{1,1500})[”」』"]/gu;
const titlePattern = new RegExp('(?:^|[，。！？：；、\\s])(' + personStart + '(?:太太|夫人|小姐|先生|医生|老师|老板|队长))(?=' + follows + ')','gu');
const noNames = /^(?:白天|白色|白纸|白云|白发|石板|石头|林中|林间|方才|方向|方圆|余光|马车|马路|马上|马匹|路上|路人|路边|常常|江边|江水|江南|江北|温度|沈默|午后|明天|明年|何必|何况|夏天|夏夜|小时|时刻|简直|段落|叶子|花瓣|许久|许多|许是|陈旧|周围|周末|周年|宋代|顾客|顾虑|谢幕|谢意|高处|秦朝|东方|苏醒|陆续|高兴|年龄|青衫|雷声|黄昏|蓝天|江面|母亲|姐姐|妹妹)$/u;
function candidates(lines, forced) {
  const score = new Map();
  function add(name, points) {
    if (!name || /^(?:高跟|左右|左侧|左腿|时而|时不时|余韵|池水|长大|南境|任何|温泉|高贵|高度|高人|高声|明明|金紫色|华贵|明媚|舒适|任由|郑重|何方|长期|胡乱|胡说|路过|齐声|方才|简直|简略|温热|温声|周到|金色|林木|温情|张口|白天|方的|骤然)/u.test(name) || noNames.test(name)||/^(?:温热|温暖|温柔|温润|温泉|薄纱|时间|任何|尤其|方才|朱红色|白浆|白腻|白日里|莫非|严肃|方口中|方台阶|屈辱|余威|齐齐|安宁|随即|高兴|高耸|高大|高峰|高声|高挑|高处)(?:的|中|池|那)?$/u.test(name)||/[家的中着上里]$/u.test(name)) return;
    score.set(name,(score.get(name)||0)+points);
  }
  for (const line of lines) {
    const prose=line.replace(speech,'');
    for(const m of line.matchAll(/(?:教主|宫主|岛主|掌门|人称|号称)[「“『]([^」”』]{2,8})[」”』]([\p{Script=Han}]{2,3}?)(?=对|是|向|曾|在|，|。)/gu))add(m[2],6);
    for (const m of prose.trim().matchAll(namePattern)) {
      const tail = prose.trim().slice(m.index + m[0].length);
      add(m[1], /^(?:[，、]|是)/u.test(tail) ? 1 : 3);
    }
    for (const m of line.matchAll(secondary)) add(m[1],2);
    for (const m of prose.matchAll(titlePattern)) add(m[1],3);
    add(matchText(line,/(?:名叫|名为|姓名[：:]|自称)\s*([\p{Script=Han}]{2,4})(?=[，。！？、；：\s]|$)/u),4);
  }
  const extensions=new Map();
  for(const [name,points] of score)for(let i=2;i<name.length;i++){
    const prefix=name.slice(0,i);
    extensions.set(prefix,Math.max(extensions.get(prefix)||0,points));
  }
  const found=[...score].filter(([name,points])=>points>=2&&(extensions.get(name)||0)<=points+2&&!(name.length===3&&(score.get(name.slice(0,2))||0)>points*3&&/[心一大面忽惨手脚口眼听说把将见正才刚]/u.test(name[2]))).map(([name])=>name);
  if(!found.length&&!forced.length&&lines.some(line=>/^(?:我(?=走|看|听|站|说|问|回|坐|想|记得|伸手|低头|是|今年))/u.test(line.trim())))found.push('我');
  return unique([...forced,...found]).sort((a,b)=>b.length-a.length);
}
const relationsRx = '(亲姐姐|亲妹妹|姐姐|妹妹|哥哥|弟弟|母亲|父亲|父女|母女|妻子|丈夫|女儿|儿子|伴侣|恋人|朋友|同学|室友|搭档|旧同事|同事|合伙人|上司|下属|老师|学生|对手|仇人|邻居|恩人)';
const patterns = {
  age: /(?:年龄\s*[：:为是]?\s*|今年|现年|年方|年仅|年约|才)?([零〇一二三四五六七八九十百两\d]{1,5}\s*(?:周岁|虚岁|岁)(?:半)?)/u,
  height: /(?:身高|个头|身量)\s*[：:约为是有]?\s*((?:1[.．]\d{1,2}\s*(?:米|m)?|\d{2,3}\s*(?:厘米|公分|cm)|[一二两1-2]米[零〇一二三四五六七八九十\d]{0,3}))(?!\d)/iu,
  build: /(?:身材|身形|体态|体型|体格)\s*[：:是为]?\s*([^，。！？；、：\n]{1,25})/u,
  personality: /(?:性格|个性|脾气)\s*[：:是为]?\s*([^，。！？；：\n]{1,28})/u,
  background: /((?:出生于|出身于?|长大于|毕业于|毕业后|曾任|曾在|从小|来自|在[^，。；]{1,15}(?:留学|工作)|接手|继承)[^。！？；]{0,90})/u
};
const ageClue = /((?:刚(?:满|过)?[二三四五六七八九十]十(?:出头)?|[二三四五六七八九]十(?:来|多)岁|[二三四五六七八九]十(?:出头|上下|左右)|年近[二三四五六七八九]?十|尚未成年|年迈|正值壮年|已是中年|刚成年|刚上(?:小学|初中|高中|大学)|刚从[^\s，。；]{0,12}毕业|还是个孩子|生了[一二三四五六七八九两\d]+个孩子|(?:结婚|成婚)(?:多年|[一二三四五六七八九十两\d]+年)|(?:已是|身为|早已是)(?:人妻|人母|人父)))/u;
const relativeHeight = /((?:比(?:我|她|他|旁人)(?:还|要|略|稍)?(?:高|矮)(?:出|了)?(?:[一半两]个头|一截|一点|许多|不少|些|半头)?|(?:只|恰好|刚好|才)?到(?:我|她|他)的?(?:肩膀|肩头|肩口|肩|下巴|胸口|胸前|眉眼|鼻尖)|(?:和|与)(?:我|她|他)(?:差不多|一样)高|个子(?:很|偏|挺)?(?:高|矮)))/u;
const comparedHeight = /^(?:，?\s*)比\s*$/u;
const comparedEnd = /^([还要略稍]?(?:高|矮)(?:了|出)?(?:[一半两]个头|一截|一点|许多|不少|些|半头)?)(?=$|[，。；！？、])/u;
const buildClue = /((?:身量|骨架|双肩|肩膀|宽肩|窄肩|肩背|肩头|肩线|腰身|腰肢|细腰|腰背|腰带|双腿|胳膊|手臂|筋骨|体态)[^，。；！？、]{1,32}|(?:身姿|身段)(?:挺拔|修长|轻盈|纤细|挺直|丰腴)|(?:高挑|修长|瘦削|魁梧|纤细|匀称|结实|丰腴|清瘦|挺拔)(?=的?(?:身形|身影|身材|身子|体格)))/gu;
const behaviorClues = [
  [/一向少言|不爱说话|少开口|寡言|沉默了许久|半晌才开口|只说了.{0,6}(?:句|字)/u,'言语节制'],
  [/仔细|反复核对|逐页|逐项|看完.{0,10}才|想了.{0,6}才|先.{1,16}再|等你查完.{0,12}再|把话问清|只给.{1,12}留|不肯轻易/u,'做事有步骤'],
  [/直接|直截了当|没有绕弯|当即拒绝|不许|别替我|决定了|断然/u,'表达直接'],
  [/逗笑|打趣|开玩笑|笑着问|笑嘻嘻|忍不住笑/u,'会用玩笑活跃交谈'],
  [/替.{1,14}(?:擦去|擦干|披上|盖上|挡雨|留灯)|叮嘱|陪你等|反复确认.{0,10}(?:安全|平安)|守了.{0,8}(?:一夜|整夜)/u,'把关照落实在行动里'],
  [/只.{0,12}(?:没提|不提)|没有.{0,8}提起|欲言又止|把话咽回|不动声色/u,'有些话留在心里'],
  [/摔门|拍桌|打断.{0,12}的话|厉声|猛地站起/u,'当时的反应强烈']
];
const attitudeClues=[
  [/替[^。！？]{1,30}(?:擦去|擦干|披上|盖上|挡雨|留灯)|叮嘱|陪你|陪着你|等你回来|担心你|别怕|你没事/u,'这段互动里，言行体现出关照'],
  [/先[^。！？]{0,20}再[^。！？]{0,20}(?:信|决定)|底牌还不行|提醒.{0,10}提防|我还不能信|不能全信|只肯.{0,10}交出/u,'这次交谈中仍留有戒心'],
  [/请恕|恕我|请原谅|失礼了|失敬|多有得罪/u,'说话时主动致歉或请求谅解'],
  [/多谢|感激不尽|有劳.{0,5}了|劳烦.{0,5}了/u,'向对方表达谢意或礼貌相托'],
  [/还请|烦请|恳请|求求你|求你|请您/u,'以请求的口吻与对方交谈'],
  [/不得无礼|休得|不许你|不准你|给我住口|给我闭嘴|住手/u,'在这次交谈中出言制止'],
  [/你放心|莫怕|莫要担心|不用怕|不必担心|且安心/u,'出言安抚对方'],
  [/我信你|我相信你|相信你的话|把[^。！？]{0,20}托付给/u,'此时愿意信任或托付'],
  [/拒绝.{0,15}请求|别替我|不要替我|不必替我|我自己来|别管我的事/u,'这次互动中明确划出了界限'],
  [/对不起|向.{1,18}道歉|原谅我/u,'在这段互动里主动缓和关系'],
  [/谢谢你|多亏你|向.{1,18}道谢/u,'当面表达了感谢']
];
function inferredAttitude(edge,excerpt,line){
  const cue=attitudeClues.find(([pattern])=>pattern.test(excerpt));
  if(!cue||edge.attitudes.some(a=>a.line===line&&(a.basis==='原文明示'||a.value===cue[1])))return;
  edge.attitudes.push(field(cue[1],line,'文本推断',excerpt));
}
function sourceSentence(excerpt,clue){
  return excerpt.split(/(?<=[。！？；])/u).find(part=>part.includes(clue))?.trim()||excerpt;
}
function collectProfile(card,description,excerpt,line){
  if(!card.material)card.material={build:[],behavior:[],background:[]};
  const f=card.fields, m=card.material;
  const age=matchText(description,/^(?:[，、\s]*(?:今年|现年|如今|年方|年仅|年纪约莫|年龄[：:为]?)\s*|[，、\s]*)([零〇一二三四五六七八九十百两\d]{1,5}岁(?:半)?)(?=[，。；、！\s]|$)/u);
  const ageContext=matchText(description,/(生了[一二三四五六七八九两\d]+个孩子)/u)||matchText(description,ageClue);
  if(age&&(!f.age||f.age.basis!=='原文明示'))f.age=field(age,line);
  if(ageContext&&ageContext!==f.age?.value){
    const stage=/生了/u.test(ageContext)?ageContext+'，具体岁数未写明':/结婚|成婚|人妻|人母|人父/u.test(ageContext)?'原文交代了婚育阶段，具体岁数未写明':ageContext;
    const detail=/结婚|成婚|人妻|人母|人父|生了/u.test(ageContext)?sourceSentence(excerpt,ageContext):'';
    if(!f.age)f.age=field(stage,line,detail?'年龄线索':'原文年龄描述',detail);
    else if(f.age.basis!=='原文明示'&&detail&&!f.age.detail.includes(detail)){
      f.age.detail=[f.age.detail,detail].filter(Boolean).join('\n');
      f.age.sources=unique([...f.age.sources,line]);
      if(/生了/u.test(ageContext))f.age.value=stage;
    }
  }
  const height=clean(matchText(description,patterns.height));
  const relative=matchText(description,relativeHeight);
  const heightSubject=relative&&(/^(?:比|个子|和|与)/u.test(relative)||/(?:个头|身高|身量|个子|站直了)[^，。；]{0,6}$/u.test(description.slice(0,description.indexOf(relative))));
  if(height&&(!f.height||f.height.basis!=='原文明示'))f.height=field(height,line);
  else if(heightSubject&&!f.height)f.height=field(relative,line,'相对描述',sourceSentence(excerpt,relative));
  const explicitBuild=clean(matchText(description,patterns.build));
  const descriptions=unique([...description.matchAll(buildClue)].map(match=>match[1]));
  if(explicitBuild||descriptions.length){
    if(!f.build)f.build=field(explicitBuild||descriptions[0],line,explicitBuild?'原文明示':'原文描写');
    if(explicitBuild&&f.build.basis!=='原文明示')f.build=field(explicitBuild,line);
    const bodySentence=sourceSentence(excerpt,explicitBuild||descriptions[0]);
    if(m.build.length<5&&!m.build.some(x=>x.text===bodySentence))m.build.push({text:bodySentence.slice(0,340),line});
  }
  const personality=clean(matchText(description,patterns.personality));
  if(personality&&(!f.personality||f.personality.basis!=='原文明示'))f.personality=field(personality,line);
  collectBehavior(card,description,excerpt,line);
  const background=clean(matchText(description.split(/[。；！？]/u)[0],patterns.background).split(/[，、]/u)[0]);
  if(background&&!/^来自(?:性|仙宫宫主的怒火)|(?:气息|怒火|触感|摩擦)/u.test(background)&&m.background.length<6&&!m.background.some(x=>x.text===background))m.background.push({text:background,line});
  const gender=matchText(description,/(?:性别\s*[：:为是]?\s*|^是(?:一名|一个|一位)?)(男|女)(?=[性生孩人，。；、\s]|$)/u);
  const pronoun=matchText(description,/(?:^|[。！？；]\s*)(她|他)(?!们)/u);
  if(gender)f.gender=field(gender,line);
  else if(!f.gender&&pronoun)f.gender=field(pronoun==='她'?'女':'男',line,'代词线索');
}
function collectBehavior(card,description,excerpt,line){
  const m=card.material;
  for(const [pattern,trait] of behaviorClues){
    if(m.behavior.length>=10)break;
    if(pattern.test(description)&&!m.behavior.some(b=>b.trait===trait&&b.line===line))m.behavior.push({trait,text:excerpt,line});
  }
}
function finishProfile(card){
  const f=card.fields, m=card.material;
  if(!m)return;
  if(f.build&&m.build.length){
    f.build.detail=m.build.map(e=>e.text).join('\n');
    f.build.sources=unique([f.build.line,...m.build.map(e=>e.line)]);
  }
  if(m.behavior.length){
    const selected=m.behavior.filter((item,i,list)=>list.findIndex(b=>b.trait===item.trait)===i).slice(0,4);
    const traits=selected.map(item=>item.trait).join('，');
    const base=f.personality||field('从相关场景看，'+traits+'。',selected[0].line,'行为线索');
    if(base.basis==='原文明示'&&base.value.length<16)base.value='原文写'+card.name+'性格'+base.value+'。在已提取的场景中，'+traits+'。';
    base.detail=unique(selected.map(item=>item.text)).join('\n');
    base.sources=unique([base.line,...selected.map(item=>item.line)]);
    f.personality=base;
  }else if(f.personality){
    f.personality.value='原文用「'+f.personality.value+'」描述其性格。';
    f.personality.detail=card.profileQuotes?.get(f.personality.line)||'';
  }
  if(m.background.length){
    f.background=field(m.background.map(e=>e.text).join('。')+'。',m.background[0].line,'原文经历','',m.background.map(e=>e.line));
  }
  delete card.material;
  delete card.profileQuotes;
}
function chapterMap(lines) {
  let chapter = '';
  return lines.map(line => {
    if (/^(?:#{1,6}\s*)?(?:第[零〇一二三四五六七八九十百千万两\d]{1,9}[章节卷回部篇]|序章|楔子|尾声|番外|chapter\s+\d+)/iu.test(line.trim()) && line.length<85) chapter=line.trim().replace(/^#+\s*/,'');
    return chapter;
  });
}
function identityEvidence(lines,known){
  const H='[\\p{Script=Han}]', name=personStart+H+'{1,2}?';
  const title='(?:老师|小姐|先生|夫人|太太|医生|教授|主任|队长|老板)';
  const isTitle=new RegExp('^'+personStart+title+'$','u');
  const fullName=new RegExp('^'+name+'$','u');
  const alias='('+H+'{1,8}?)';
  const end='(?=[”」』"、，。！？；：\\s]|$)';
  const mark='[“「『"]?';
  const intro='(?:(?:有(?:一)?个|的)?外号(?:叫|是)?|绰号(?:叫|是)?|昵称(?:叫|是)?|小名(?:叫|是)?|别名(?:叫|是)?|又名|人称|号称|被(?:大家|人|学生们)?(?:叫作|叫做|称为|誉为)|(?:大家|别人|家里人|家人|同事们?|学生们?|同学们?|朋友们?|教众)(?:都|也|私下里?|背地里|在背后|平时|习惯|常常?)*(?:叫|喊|称)(?:她|他)(?:为|作|做)?)';
  const boundary='(?:^|[，。；！？：、\\s「“]|她叫|他叫|名叫|名为)';
  const forward=new RegExp(boundary+'('+name+')[，,（(：:\\s]*'+intro+'[：:\\s]*'+mark+alias+end,'gu');
  const reverse=new RegExp('(?:^|[，。！？；：“「『\\s])'+mark+alias+'[”」』"]?[，,的\\s]*(?:本名|真名|原名)(?:叫|是|为)?[：:\\s]*('+name+')'+end,'gu');
  const ancient=new RegExp('(?:教主|宫主|岛主|掌门|人称|号称)[「“『]('+H+'{2,8})[」”』]('+name+')(?=对|是|向|曾|在|，|。)','gu');
  const namedAddress=new RegExp(boundary+'('+name+')(?:[，,]?(?:被)?(?:大家|同学们?|学生们?|同事们?)?(?:都|也|总|私下里?|平常|平时)*(?:称呼|喊|叫|称)(?:她|他)(?:为|作|做)?)[：:\\s]*'+mark+alias+end,'gu');
  const evidence=[],seen=new Set(),proper=new Set(known.filter(n=>fullName.test(n)&&!isTitle.test(n)));
  function add(real,other,line){
    if(!fullName.test(real)||isTitle.test(real)||noNames.test(real)||!other||other===real||/^(?:自己|大家|他|她|我|你|老师|小姐|先生|姐姐|妹妹|妈妈|爸爸)$/u.test(other))return;
    const key=real+'|'+other+'|'+line;
    if(!seen.has(key)){seen.add(key);proper.add(real);evidence.push({name:real,alias:other,line});}
  }
  for(const [i,line] of lines.entries()){
    for(const rx of [forward,namedAddress])for(const m of line.matchAll(rx))add(m[1],m[2],i+1);
    for(const rx of [reverse,ancient])for(const m of line.matchAll(rx))add(m[2],m[1],i+1);
    // An explicit introduction also handles standalone name / alias lists.
    const list=line.trim().match(/^([^\s/／]{2,4})\s*[/／]\s*(.+)$/u);
    if(list && fullName.test(list[1]) && !isTitle.test(list[1])){
      const items=list[2].replace(/[。.]+$/u,'').split(/[、/／，,]/u).map(x=>x.trim());
      if(items.every(x=>/^\p{Script=Han}{1,8}$/u.test(x)))for(const x of items)add(list[1],x,i+1);
    }
  }
  const honorific=new RegExp('有[「“『]('+H+'{2,12})[」”』]之称的('+name+')'+end,'gu');
  for(const [i,line] of lines.entries())for(const m of line.matchAll(honorific))add(m[2],m[1],i+1);
  for(const [i,line] of lines.entries())for(const m of line.matchAll(/被誉为[「“『]([\p{Script=Han}]{2,12})[」”』]/gu)){
    const sentence=line.slice(0,m.index).split(/[。！？；]/u).at(-1).trim();
    const subject=[...proper].find(n=>sentence.startsWith(n));
    if(subject&&(evidence.some(e=>e.name===subject&&e.alias===m[1])||!/[，,]/u.test(sentence)))add(subject,m[1],i+1);
  }
  const aliasCue=new RegExp('(?:^|[，。；！？，\\s])'+intro+'[：:\\s]*'+mark+alias+end,'gu');
  const quoteList=/^[”」』"]?(?:、[“「『"]?([\p{Script=Han}]{1,8})[”」』"]?)+/u;
  const properList=[...proper].sort((a,b)=>b.length-a.length);
  const knownRx=properList.length?new RegExp(properList.join('|'),'gu'):null;
  for(const [i,line] of lines.entries()){
    for(const m of line.matchAll(aliasCue)){
      const prior=line.slice(0,m.index);
      if(!knownRx||/[“「『"][^”」』"]*$/u.test(prior))continue;
      const context=prior.trim()?prior.split(/[。！？；]/u).filter(Boolean).at(-1):lines[i-1];
      if(!context||context.length>120)continue;
      const subjects=unique([...context.matchAll(knownRx)].map(hit=>hit[0]));
      if(subjects.length!==1||!context.trim().startsWith(subjects[0]))continue;
      add(subjects[0],m[1],i+1);
      const rest=line.slice(m.index+m[0].length).match(quoteList)?.[0]||'';
      for(const item of rest.matchAll(/、[“「『"]?([\p{Script=Han}]{1,8})/gu))add(subjects[0],item[1],i+1);
    }
  }
  const jobs=new Map(),titleUses=new Map();
  const teaching=new RegExp(boundary+'('+name+')(?:[，,]?\\s*(?:是|担任|出任|当上了|成了))(?:我(?:们)?的?|她的|他的|一名|一位|这所学校的|新来的|班里的|班上的|学校的|语文|数学|英语|物理|历史|教书的){0,4}(老师|教师|班主任|教授|医生|主任)(?=[，。！？；、\\s]|$)','gu');
  for(const [i,line] of lines.entries())for(const m of line.matchAll(teaching)){
    const real=m[1],family=real.match(new RegExp('^'+personStart,'u'))[0];
    const other=family+(/老师|教师|班主任/u.test(m[2])?'老师':m[2]);
    proper.add(real);
    if(!jobs.has(other))jobs.set(other,[]);
    jobs.get(other).push({real,line:i+1});
  }
  const titleRx=new RegExp(personStart+title,'gu');
  for(const [i,line] of lines.entries()){
    for(const m of line.matchAll(titleRx)){
      if(!titleUses.has(m[0]))titleUses.set(m[0],i+1);
      if(!/[“「『"]|称呼|叫她|喊她|称她|称他|叫他|喊他/u.test(line))continue;
      const family=m[0].match(new RegExp('^'+personStart,'u'))[0];
      const possibles=[...proper].filter(n=>n.startsWith(family));
      if(possibles.length!==1)continue;
      const real=possibles[0], before=line.slice(0,m.index);
      // Require a direct addressee cue or a reply explicitly attributed to the full name.
      if(before.endsWith('向'+real+'喊：“')||before.endsWith('对'+real+'说：“')||
         (line.slice(m.index).match(/^[^。！？]{1,12}[！？，。]?[”」』"]\s*[，,]?/u)&&
          new RegExp('[”」』"]\\s*[，,]?'+real+'(?:应声|答道|回答|应道|回过头|回头|点头)').test(line)))add(real,m[0],i+1);
    }
  }
  for(const [alias,claims] of jobs){
    const family=alias.match(new RegExp('^'+personStart,'u'))[0];
    const names=[...proper].filter(n=>n.startsWith(family));
    if(names.length===1&&titleUses.has(alias)){
      add(names[0],alias,claims[0].line);
      add(names[0],alias,titleUses.get(alias));
    }
  }
  return evidence;
}
function unifyIdentities(cards,links,lines){
  if(!links.length)return cards;
  const owners=new Map();
  for(const x of links){if(!owners.has(x.alias))owners.set(x.alias,new Set());owners.get(x.alias).add(x.name);}
  const realNames=new Set(links.map(x=>x.name));
  const owner=n=>owners.get(n)?.size===1&&!realNames.has(n)?[...owners.get(n)][0]:n;
  const groups=new Map();
  for(const c of cards){const key=owner(c.name);if(!groups.has(key))groups.set(key,[]);groups.get(key).push(c);}
  const out=[];
  for(const [name,group] of groups){
    const base=group.find(c=>c.name===name)||group[0];
    const evidence=links.filter(x=>x.name===name);
    const aliases=unique(evidence.map(x=>x.alias));
    const card={...base,name,aliases,aliasEvidence:evidence,fields:{...base.fields},relations:[],soloScenes:[]};
    const edges=new Map();
    function append(target,items,isScene){
      const sig=x=>JSON.stringify([x.line,isScene?x.quote:x.value,isScene?owner(x.speaker):x.basis]);
      const found=new Set(target.map(sig));
      for(const x of items||[]){const k=sig(x);if(!found.has(k)){found.add(k);target.push(isScene?{...x,speaker:owner(x.speaker)}:x);}}
    }
    for(const p of group){
      for(const [k,f] of Object.entries(p.fields))if(!card.fields[k]||card.fields[k].basis!=='原文明示'&&f.basis==='原文明示')card.fields[k]=f;
      append(card.soloScenes,p.soloScenes,true);
      for(const r of p.relations){
        const to=owner(r.to);
        if(to===name){append(card.soloScenes,r.scenes,true);continue;}
        if(!edges.has(to))edges.set(to,{to,type:r.type,scenes:[],attitudes:[]});
        const e=edges.get(to);
        if(e.type==='同场互动')e.type=r.type;
        append(e.scenes,r.scenes,true);append(e.attitudes,r.attitudes,false);
      }
    }
    card.relations=[...edges.values()];
    for(const e of card.relations){e.scenes.sort((a,b)=>a.line-b.line);e.attitudes.sort((a,b)=>a.line-b.line);}
    card.soloScenes.sort((a,b)=>a.line-b.line);
    if(aliases.length){
      const forms=[name,...aliases.filter(a=>owner(a)===name)];
      card.mentions=0;card.mentionsAt=[];
      for(const [i,l] of lines.entries())if(forms.some(n=>l.includes(n))){card.mentions++;if(card.mentionsAt.length<8)card.mentionsAt.push(i+1);}
    }
    out.push(card);
  }
  return out.sort((a,b)=>b.mentions-a.mentions||a.name.localeCompare(b.name,'zh'));
}

function analyzeLocal(lines, forced=[], progress=()=>{}) {
  const detected=candidates(lines,forced);
  const links=identityEvidence(lines,detected);
  const names=unique([...detected,...links.flatMap(x=>[x.name,x.alias])]).sort((a,b)=>b.length-a.length);
  const people = new Map(names.map(name=>[name,{name,fields:{},relations:[],mentions:0,mentionsAt:[],profileQuotes:new Map(),soloScenes:[]}]));
  const lastSeen = new Map();
  let recent = [];
  const nameRx = names.length ? new RegExp(names.map(n=>n.replace(/[.*+?^\${}()|[\]\\]/g,'\\$&')).join('|'),'gu') : /(?!) /gu;
  const startsWithName=new RegExp('^(?:'+nameRx.source+')','u');
  function relation(from,to) {
    const p=people.get(from);
    let edge=p.relations.find(r=>r.to===to);
    if(!edge){edge={to,type:'同场互动',attitudes:[],scenes:[]};p.relations.push(edge);}
    return edge;
  }
  function splitLine(line) {
    const parts=[];
    let start=0;
    for(const token of line.matchAll(/[“「『"][^”」』"]*[”」』"]|[。！？；](?=.{1,8})/gu)){
      const next=token.index+token[0].length;
      if(startsWithName.test(line.slice(next,next+14).trimStart())){
        parts.push(line.slice(start,next));
        start=next;
      }
    }
    parts.push(line.slice(start));
    return parts;
  }
  for (const [i,original] of lines.entries()) {
    if(i%1500===0&&lines.length>1500)progress('正在整理原文 · '+Math.floor(i/lines.length*100)+'%');
    if (!original.trim()) continue;
    const seen=new Set();
    for(const line of splitLine(original)){
      const named = unique([...line.matchAll(nameRx)].map(m=>m[0]));
      named.forEach(n=>{
        const entry=people.get(n);
        if(!seen.has(n)){entry.mentions++;if(entry.mentionsAt.length<8)entry.mentionsAt.push(i+1);}
        seen.add(n);
      });
      const narrative=line.replace(speech,'');
      const visible=unique([...narrative.matchAll(nameRx)].map(m=>m[0]));
      const principal=visible[0];
      if (principal) {
        const card=people.get(principal);
        const start=narrative.indexOf(principal)+principal.length;
        const namedNext=visible.length>1?narrative.indexOf(visible[1],start):-1;
        const description=narrative.slice(start,namedNext<0?undefined:namedNext);
        if(!card.fields.personality&&patterns.personality.test(description))card.profileQuotes.set(i+1,line.trim().slice(0,400));
        collectProfile(card,description,line.trim().slice(0,1200),i+1);
        if(visible.length>=2){
          const [from,to]=visible;
          const between=narrative.slice(start,namedNext);
          const after=narrative.slice(namedNext+to.length);
          const compared=comparedHeight.test(between)&&matchText(after,comparedEnd);
          if(compared&&!card.fields.height)card.fields.height=field('比'+to+compared,i+1,'相对描述',line.trim());
          const relationAction=/(?:对|问|望向|看向|看着|盯着|拉住|替|追上|拦住|叫住|走向|转向|跟着|冲着|护着|安慰|道谢|向|等着|叮嘱|给|是|和|跟|与)$/u.test(between.trim());
          if(!relationAction&&!compared){recent=visible;visible.forEach(n=>lastSeen.set(n,i));continue;}
          const edge=relation(from,to);
          const kind=matchText(after,new RegExp('^的'+relationsRx,'u'));
          if(kind && /^(?:正|便|就|乃)?是\s*$/u.test(between)) edge.type=kind;
          const attitude=clean(matchText(after,/^的态度\s*(?:是|为|从)?\s*([^。！？；\n]{1,85})/u));
          if(attitude && /对\s*$/u.test(between)) {
            const full=after.startsWith('的态度从')?'从'+attitude:attitude;
            if(!edge.attitudes.some(a=>a.value===full))edge.attitudes.push(field(full,i+1));
          }
          if(!attitude&&relationAction&&!/^(?:是|和|跟|与)$/u.test(between.trim())){
            inferredAttitude(edge,from+between+to+after,i+1);
            collectBehavior(card,between+to+after,line.trim().slice(0,400),i+1);
          }
          const other=relation(to,from);
          for(const quote of line.matchAll(speech)){
            const prior=line.slice(0,quote.index);
            const later=line.slice(quote.index+quote[0].length);
            const hasAction=/说|问|道|答|喊|笑|看向|看着|拉住|望着|转向/u.test(prior)||new RegExp('^[，,。\\s]*'+from+'(?:说|问|道|答|喊|笑)','u').test(later);
            if(!hasAction)continue;
            const display=prior.includes(from)?from:later.includes(from)?from:'';
            if(!display) continue;
            const context=prior.trim().slice(-240)||(later.trim().slice(0,180));
            const scene={quote:quote[1],line:i+1,speaker:display,context};
            edge.scenes.push(scene);
            other.scenes.push(scene);
            inferredAttitude(edge,quote[1],i+1);
            collectBehavior(card,quote[1],context+'“'+quote[1]+'”',i+1);
          }
        }
      }
      if(visible.length){
        recent=visible;
        visible.forEach(n=>lastSeen.set(n,i));
        if(visible.length===1&&/说|问|答|道|喊|开口|低声/u.test(narrative)){
          const c=people.get(principal);
          for(const quote of line.matchAll(speech)){
            if(c.soloScenes.length>=12)break;
            const context=line.slice(0,quote.index).trim()||line.slice(quote.index+quote[0].length).trim();
            c.soloScenes.push({quote:quote[1],speaker:principal,context,line:i+1});
            collectBehavior(c,quote[1],context+'“'+quote[1]+'”',i+1);
          }
        }
      }else if(recent.length===1&&i-lastSeen.get(recent[0])<=2){
        const card=people.get(recent[0]);
        const pronoun=matchText(narrative,/^\s*(她|他)(?!们)/u);
        if(pronoun){
          if(!card.fields.gender)card.fields.gender=field(pronoun==='她'?'女':'男',i+1,'代词线索');
          collectProfile(card,narrative.trim().slice(1),line.trim(),i+1);
        }
      }
    }
  }
  recoverInteractions(lines,people,nameRx,relation);
  const reverse={亲姐姐:'手足',亲妹妹:'手足',姐姐:'手足',妹妹:'手足',哥哥:'手足',弟弟:'手足',母亲:'子女',父亲:'子女',女儿:'家人',儿子:'家人',上司:'下属',下属:'上司',老师:'学生',学生:'老师'};
  for(const p of people.values()){
    finishProfile(p);
    for(const r of p.relations){
      r.attitudes.sort((a,b)=>a.line-b.line);
      const back=people.get(r.to).relations.find(e=>e.to===p.name);
      if(back && back.type==='同场互动' && r.type!=='同场互动')back.type=reverse[r.type]||r.type;
    }
  }
  return unifyIdentities([...people.values()],links,lines).sort((a,b)=>b.mentions-a.mentions || a.name.localeCompare(b.name,'zh'));
}
function recoverInteractions(lines,people,nameRx,relation){
  const kin={母亲:'子女',父亲:'子女',儿子:'家长',女儿:'家长',丈夫:'妻子',妻子:'丈夫'};
  let pair=[],lastPair=-10,pending=null;
  function saveScene(from,to,quote,context,line){
    const scene={speaker:from,quote,context,line};
    const lists=to?[relation(from,to).scenes,relation(to,from).scenes]:[people.get(from).soloScenes];
    for(const list of lists)if(!list.some(s=>s.line===line&&s.quote===quote))list.push(scene);
    if(to)inferredAttitude(relation(from,to),quote,line);
  }
  for(const [i,raw] of lines.entries()){
    const text=raw.trim();
    if(!text)continue;
    if(/^(?:第.{1,8}[章节卷]|番外|序章|尾声)/u.test(text)){pair=[];pending=null;continue;}
    if(pending){
      if(i-pending.i<=3&&text.length<240&&!/[“「]/u.test(text)&&/[？！!?…]|^(?:你|我|请|别)/u.test(text))
        saveScene(pending.from,pending.to,text,pending.context,i+1);
      pending=null;
    }
    for(const m of text.matchAll(/(?:教主|宫主|岛主|掌门|人称|号称)[「“『]([^」”』]{2,8})[」”』]([\p{Script=Han}]{2,3}?)(?=对|是|向|曾|在|，|。)/gu)){
      if(!people.has(m[2]))continue;
      const card=people.get(m[2]);
      card.aliases=unique([...(card.aliases||[]),m[1]]);
    }
    // Use the subject and addressee in a speech cue, excluding names inside the utterance.
    const inline=text.match(/^([^。！？：:]{1,180}(?:道|说|问|答|开口|喊|笑了起来))[：:]\s*([^「“『"\s].*)$/u);
    if(inline){
      const who=unique([...inline[1].matchAll(nameRx)].map(m=>m[0]));
      const from=who[0];
      if(from&&who.length<=2){
        const explicit=who.length===2&&/(?:对|向|看着|看向|望着|冲着)/u.test(inline[1]);
        const to=explicit?who[1]:who.length===1&&pair.includes(from)&&i-lastPair<=3?pair.find(n=>n!==from):null;
        const quote=inline[2].split(/\s+(?=[他她其这那])/u)[0].trim();
        if(quote)saveScene(from,to,quote,inline[1],i+1);
      }
    }
    const hits=[...text.matchAll(nameRx)];
    const lead=hits[0]?.[0];
    if(!lead)continue;
    for(const h of hits.slice(1)){
      const target=h[0];
      if(target===lead)continue;
      const prefix=text.slice(hits[0].index+lead.length,h.index);
      const family=prefix.match(/(?:自己|其)(?:的)?(母亲|父亲|丈夫|妻子|儿子|女儿)$/u);
      if(family&&hits[0].index<4&&!/[。！？”]/u.test(prefix)){
        relation(target,lead).type=family[1];relation(lead,target).type=kin[family[1]];
      }
      const towards=prefix.match(/(?:一直|十分|始终|很是|不禁|颇为|格外)?(敬佩|敬畏|欣赏|担心|疼爱|信任|提防|忌惮|厌恶|鄙夷|轻视|怜惜|爱慕|感激|警惕|心疼)(?:着|起)?$/u);
      const interaction=prefix.match(/(安慰|保护|搀扶|训斥|质问|斥责|嘲讽|冷落|袒护|宽慰|劝慰|怒视|怒斥|称赞|夸赞|打断|拦住)(?:着|了)?$/u);
      if((towards||interaction)&&prefix.length<28&&!/[。！？；，、]|(?:并非|并不|没有|不再|不会|不敢)/u.test(prefix)&&!hits.slice(1).some(other=>other.index<h.index)){
        const value=towards?'原文写到其对'+target+'的'+towards[1]:'这次面对'+target+'时，采取了'+interaction[1]+'的举动。';
        const edge=relation(lead,target);
        if(!edge.attitudes.some(a=>a.line===i+1))edge.attitudes.push(field(value,i+1,towards?'原文明示':'行为线索',sourceSentence(text,prefix+target)));
      }
      const a=text.slice(h.index+target.length).match(/^(?:格外|尤为|十分|颇为)?(宠溺有加|言听计从|心存戒备|心怀感激|恭敬有加|爱护有加|十分信任|颇为忌惮|满怀敌意)/u);
      if(a&&/对$/u.test(prefix)&&!/[。！？]/u.test(prefix)){
        const edge=relation(lead,target);
        if(!edge.attitudes.some(x=>x.line===i+1))edge.attitudes.push(field(a[1],i+1,'原文明示',sourceSentence(text,a[1])));
      }
    }
    const narrative=text.replace(speech,'');
    const names=unique([...narrative.matchAll(nameRx)].map(m=>m[0]));
    if(names.length>2){pair=[];continue;}
    if(names.length===2){pair=names;lastPair=i;}
    if(!names.length)continue;
    const speaker=names[0];
    const to=pair.includes(speaker)&&i-lastPair<=3?pair.find(n=>n!==speaker):null;
    for(const m of text.matchAll(speech)){
      const prefix=text.slice(0,m.index);
      const phrase=prefix.split(/[。！？」”]/u).at(-1);
      if(!phrase.includes(speaker)||!/(?:说|问|答|道|喊|开口|回应|低语|笑)[：:，,]?\s*$/u.test(phrase))continue;
      if(names.length===2&&!/(?:对|向|看着|望向|看向|冲着|叫住|拉住)/u.test(phrase))continue;
      saveScene(speaker,to,m[1],phrase.slice(-170),i+1);
    }
    if(/(?:说道|问道|低声道|喊道|开口道|回答|轻声说|说|问)[：:]$/u.test(text))
      pending={from:speaker,to,context:text.slice(-170),i};
  }
  for(const card of people.values())for(const r of card.relations)r.scenes.sort((a,b)=>a.line-b.line);
}
