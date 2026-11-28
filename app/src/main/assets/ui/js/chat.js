(function(){
const O=window.OCM,h=O.h,S=O.S,api=O.api,bus=O.bus;
let msgsEl,dockEl,popEl,ta,sendBtn,agentChip,modelChip,t2iChip,varChip,attsEl,jumpEl,statusEl,todoEl,revEl,fileIn,nokeyEl,comp,projChip,vcsChip,projTxt,vcsTxt,footerPrompt,chatRoot;
const msgEls=new Map(),partEls=new Map(),open=new Set(),timers=new Map();
let atts=[],pathAtts=[],mentions=[],stick=true,revKey='',trail=[],t2iOn=false,lastModelKey='',pendingResumeSid='',lastAutoRotateAt=0,warpWaiter=null;
O.rel=p=>{p=String(p||'');return S.dir&&p.startsWith(S.dir+'/')?p.slice(S.dir.length+1):p};
const cur=()=>S.sessions.find(x=>x.id===S.sid)||O.child(S.sid);
const modelStr=()=>S.model?S.model.providerID+'/'+S.model.modelID:undefined;
const busy=()=>{const s=S.status[S.sid];return !!s&&s.type!=='idle'};

O.chatInit=root=>{
  chatRoot=root;
  msgsEl=h('div',{id:'msgs',class:'sel',onscroll:()=>{stick=nearBottom();jumpEl.classList.toggle('hide',stick)}});
  jumpEl=h('button',{id:'jump',class:'hide',onclick:()=>scrollEnd(true)},O.icon('chevD'));
  revEl=h('div',{class:'hide',style:{padding:'0 10px 6px'}});
  todoEl=h('div',{class:'hide',style:{padding:'0 10px 6px'}});
  dockEl=h('div',{id:'dock'});statusEl=h('div',{class:'status-line hide'});
  popEl=h('div',{id:'pop',class:'hide'});attsEl=h('div',{class:'atts hide'});
  nokeyEl=h('div',{class:'nokey hide'},h('span',{class:'grow'},O.tr('No models connected.','Модели не подключены.')),h('button',{class:'btn sm',onclick:()=>O.openConnect()},O.icon('key'),O.tr('Connect','Подключить')));
  ta=h('textarea',{rows:1,placeholder:O.tr('Chat with OpenCode...','Чат с OpenCode...'),oninput:onInput,onclick:onInput,onkeyup:e=>{if(e.key.length>1)onInput()}});
  fileIn=h('input',{type:'file',multiple:true,class:'hide',accept:'image/*,application/pdf,text/*',onchange:onFiles});
  const plusBtn=h('button',{class:'attach-btn',title:O.tr('Attach a file','Прикрепить файл'),onclick:()=>{
    const items=[];
    if(O.native&&O.native.pickPath)items.push({icon:'folder',label:O.tr('File from phone','Файл с телефона'),sub:O.tr('Adds its path, no upload','Добавляет путь, без загрузки'),fn:()=>O.native.pickPath()});
    items.push({icon:'doc',label:O.tr('Upload to chat','Загрузить в чат'),sub:O.tr('Sends the file contents','Отправляет содержимое файла'),fn:()=>fileIn.click()});
    O.actions(O.tr('Attach','Прикрепить'),items);
  }},O.icon('plus'));
  agentChip=h('button',{class:'hud-pill agent-pill',onclick:()=>O.openAgents()},h('span',null,'Build'),O.icon('chevD'));
  modelChip=h('button',{class:'hud-pill model-pill',onclick:()=>O.openModels()},h('span',null,O.tr('Select model','Выбрать модель')),O.icon('chevD'));
  t2iChip=h('button',{class:'hud-pill t2i-pill hide',title:O.tr('Toggle T2I photo generation mode','Режим генерации фото (T2I)'),onclick:()=>{t2iOn=!t2iOn;t2iChip.classList.toggle('on',t2iOn);updateChips()}},O.icon('image'),h('span',null,'T2I'));
  const micBtn=h('button',{class:'hud-icon-btn mic-btn',onclick:onMic},O.icon('mic'));
  sendBtn=h('button',{id:'send',onclick:onSend},O.icon('send'));
  const cbarLeft=h('div',{class:'cbar-left'},plusBtn,agentChip,modelChip,t2iChip);
  const cbarRight=h('div',{class:'cbar-right'},micBtn,sendBtn);
  const cbarRow=h('div',{class:'cbar-row'},cbarLeft,cbarRight);
  comp=h('div',{id:'composer'},popEl,h('div',{class:'cbox'},nokeyEl,attsEl,ta,cbarRow),fileIn);
  const wrap=h('div',{class:'chat-msgs-wrap',style:{position:'relative',flex:1,minHeight:0,display:'flex',flexDirection:'column'}},msgsEl,jumpEl);
  root.append(wrap,revEl,todoEl,statusEl,dockEl,comp);
  bus.on('open',()=>{renderAll()});
  bus.on('msg',(sid,m)=>{if(sid!==S.sid)return;const was=stick;ensureMsg(m);if(was)scrollEnd()});
  bus.on('part',(sid,m)=>{if(sid!==S.sid)return;const was=stick;ensureMsg(m);if(was)scrollEnd()});
  bus.on('msg-removed',(sid,id)=>{const e=msgEls.get(id);if(e){e.remove();msgEls.delete(id)}});
  bus.on('part-removed',(sid,m,pid)=>{const e=partEls.get(pid);if(e){e.remove();partEls.delete(pid)}});
  bus.on('status',sid=>{if(sid===S.sid){renderStatus();updateSend()}});
  bus.on('perm',renderDock);bus.on('todo',sid=>{if(sid===S.sid)renderTodos()});
  bus.on('session',()=>{const k=revisionKey();if(k!==revKey)renderAll();else renderRevert()});
  bus.on('sessions',()=>{renderRevert()});
  let lastAct=Date.now(),stuckEl=null;
  ['msg','part','status','perm','todo','session','sessions','meta'].forEach(n=>bus.on(n,()=>{lastAct=Date.now();if(stuckEl){stuckEl.remove();stuckEl=null}}));
  setInterval(()=>{
    if(!busy()){lastAct=Date.now();if(stuckEl){stuckEl.remove();stuckEl=null}return}
    if(stuckEl||Date.now()-lastAct<500000)return;
    stuckEl=h('div',{class:'stuck-banner'},
      h('div',{class:'stuck-ic'},O.icon('alert')),
      h('div',{class:'grow'},h('b',null,O.tr('The agent has been silent for 8+ minutes','Агент молчит больше 8 минут')),
        h('span',{class:'mut s'},O.tr('A command may be hanging.','Возможно, команда зависла.'))),
      h('button',{class:'btn sm',onclick:()=>{lastAct=Date.now();if(stuckEl){stuckEl.remove();stuckEl=null}}},O.tr('Wait','Ждать')),
      h('button',{class:'btn sm dng',onclick:async()=>{try{await api('POST','/session/'+S.sid+'/abort',{})}catch(e){O.toast(e.message)}lastAct=Date.now();if(stuckEl){stuckEl.remove();stuckEl=null}}},O.tr('Interrupt','Прервать')));
    comp.insertBefore(stuckEl,comp.firstChild);
  },5000);
  bus.on('meta',updateChips);bus.on('conn',updateChips);
  O.on('picked_path',d=>{if(!d)return;if(!d.ok){O.toast(d.error||O.tr('Could not attach file','Не удалось прикрепить файл'),3500);return}O.attachPath(d.path,d.name);O.toast(O.tr('Attached: ','Прикреплено: ')+(d.name||O.basename(d.path)),1800)});
  O.on('warp_rotated',async d=>{
    if(!d)return;
    if(warpWaiter){const fn=warpWaiter;warpWaiter=null;fn(d);return}
    const loc=[d.city,d.country].filter(Boolean).join(', ');
    if(d.ok){
      O.toast(O.tr('New IP: ','Новый IP: ')+(d.ip||'WARP')+(loc?(' ('+loc+')'):''),3000);
      const targetSid=pendingResumeSid||d.sessionID||'';
      pendingResumeSid='';
      if(targetSid&&targetSid===S.sid&&S.model&&!busy()){
        try{
          await api('POST','/session/'+targetSid+'/prompt_async',{
            messageID:O.mid(),agent:S.agent,model:S.model,variant:S.variant||undefined,
            parts:[{type:'text',synthetic:true,text:'The previous IP/quota limit has cleared after switching exit IP via WARP. Continue cleanly from where you left off without repeating completed work.'}]
          },{timeout:30000});
        }catch(_){}
      }
    }else if(pendingResumeSid||d.sessionID){
      pendingResumeSid='';
      O.toast(d.message||O.tr('WARP IP rotation failed','Не удалось сменить IP через WARP'),3500);
    }
  });
  bus.on('serror',p=>{if(p&&(!p.sessionID||p.sessionID===S.sid)){const e=p.error;const t=e&&(e.data&&e.data.message||e.name);if(t&&e.name!=='MessageAbortedError'){
    if(/quota|usage.?limit|rate.?limit|limit\s+reached|insufficient|exceeded|credit|balance|funds|\b429\b/i.test(String(t))){
      const wst=O.native&&O.native.warpStatus?O.native.warpStatus():null;
      const now=Date.now();
      if(wst&&wst.autoRotate&&(wst.enabled||wst.autoFallback)&&!wst.rotating&&(now-lastAutoRotateAt>5000)){
        lastAutoRotateAt=now;
        pendingResumeSid=p.sessionID||S.sid||'';
        O.notifyLimit(O.tr('Rotating IP via WARP','Смена IP через WARP'),O.tr('Quota reached, switching IP and resuming...','Лимит квоты — меняем IP через WARP и продолжаем...'));
        O.toast(O.tr('Quota limit reached — rotating WARP IP...','Лимит квоты — меняем IP через WARP...'),3200);
        if(O.native&&O.native.warpRotate)O.native.warpRotate(pendingResumeSid);
      }else{
        O.notifyLimit(O.tr('Limit Reached','Лимит исчерпан'),O.tr('Free quota limit reached.','Бесплатные лимиты закончились.'));
        O.showQuotaAccountModal(t);
      }
    }
    else O.toast(t,4000)}}});
  O.on('voice',d=>{if(d&&d.text){ta.value=(ta.value?ta.value+' ':'')+d.text;onInput();ta.focus()}});
  updateChips();renderAll();
};
O.notifyLimit=(title,text)=>{
  try{if(window.OC&&OC.testSound)OC.testSound('quota')}catch(_){}
  try{if(O.native&&O.native.notify)O.native.notify('quota',title||O.tr('Limit Reached','Лимит исчерпан'),text||O.tr('Free quota limit reached.','Бесплатные лимиты закончились.'))}catch(_){}
};
const nearBottom=()=>msgsEl.scrollHeight-msgsEl.scrollTop-msgsEl.clientHeight<140;
const scrollEnd=f=>{requestAnimationFrame(()=>{msgsEl.scrollTop=msgsEl.scrollHeight;if(f){stick=true;jumpEl.classList.add('hide')}})};
const revisionKey=()=>{const s=cur();return s&&s.revert?s.revert.messageID:''};

function renderAll(){
  msgsEl.innerHTML='';msgEls.clear();partEls.clear();revKey=revisionKey();
  const list=S.sid?O.visibleMsgs(S.sid):[];
  if(!list.length)showEmpty();else{if(chatRoot)chatRoot.classList.remove('is-empty');const fr=document.createDocumentFragment();list.forEach(m=>{const e=buildMsg(m);fr.append(e)});msgsEl.append(fr)}
  scrollEnd(true);renderDock();renderStatus();renderRevert();renderTodos();updateSend();updateChips()}
function showEmpty(){
  if(chatRoot)chatRoot.classList.add('is-empty');
  const wm=h('div',{class:'watermark-box'},O.wordmark('100%','auto'));
  msgsEl.append(h('div',{class:'empty'},wm))}
function ensureMsg(m){
  const rv=revisionKey();if(rv&&m.info.id>=rv)return;
  let el=msgEls.get(m.info.id);
  if(!el){const e=msgsEl.querySelector('.empty');if(e)e.remove();if(chatRoot)chatRoot.classList.remove('is-empty');el=buildMsg(m);
    let before=null;for(const c of msgsEl.children){if(c.dataset.id&&c.dataset.id>m.info.id){before=c;break}}
    msgsEl.insertBefore(el,before)}
  else{syncParts(el,m);metaUpdate(el,m)}}
function buildMsg(m){
  const el=h('div',{class:'msg '+m.info.role,'data-id':m.info.id},h('div',{class:'m-body'}),h('div',{class:'m-meta'}));
  msgEls.set(m.info.id,el);syncParts(el,m);metaUpdate(el,m);return el}
function showPart(m,p){
  if(m.info.role==='user')return(p.type==='text'&&!p.synthetic&&!p.ignored)||p.type==='file'||p.type==='agent';
  switch(p.type){case'text':return true;case'reasoning':{if(!S.opts.thinking)return false;const t=p.text||p.reasoning||p.thinking||'';return String(t).trim()!==''||!(p.time&&p.time.end)}case'tool':case'file':case'patch':case'retry':case'compaction':return true}return false}
function syncParts(el,m){
  const body=el.firstChild;const keep=new Set();let prev=null;
  for(const p of m.parts){if(!showPart(m,p))continue;keep.add(p.id);
    let pe=partEls.get(p.id);const fresh=!pe;
    if(fresh){pe=h('div',{class:'part','data-pid':p.id});partEls.set(p.id,pe)}
    const want=prev?prev.nextSibling:body.firstChild;
    if(pe.parentNode!==body||want!==pe)body.insertBefore(pe,want);
    fillPart(pe,p,m,fresh);prev=pe}
  for(const c of [...body.children]){if(!keep.has(c.dataset.pid)){partEls.delete(c.dataset.pid);c.remove()}}}
function fillPart(el,p,m,fresh){
  el._p=p;
  switch(p.type){
  case'text':
    if(m.info.role==='user'){el.className='part p-user';if(el._t!==p.text){el._t=p.text;el.textContent=p.text}}
    else{el.className='part p-text';if(fresh)render(el);else sched(p.id,()=>render(el))}break;
  case'reasoning':{el.className='part think';
    if(!el._d){el._d=h('details',null,h('summary',null,'Размышления'),h('div',{class:'p-text'}));el.append(el._d)}
    const b=el._d.lastChild;if(fresh)renderR(el,b);else sched(p.id,()=>renderR(el,b));break}
  case'tool':{const st=p.state||{};if(st.status==='running'&&p.tool==='bash'&&!el._auto){el._auto=1;open.add(p.id)}else if(st.status!=='running'&&el._auto===1){el._auto=2;open.delete(p.id)}
    if(fresh)fillTool(el,p);else sched(p.id,()=>fillTool(el,el._p));break}
  case'file':{el.className='part fchips';
    const mediaSrc=p.url||p.uri||'';
    const fname=p.filename||p.name||'image.png';
    const isImg=(p.mime||'').startsWith('image/')||/\.(png|jpe?g|webp|gif|bmp|avif)$/i.test(mediaSrc||fname);
    if(isImg&&mediaSrc&&el._src===mediaSrc)break;
    el.textContent='';el._src=mediaSrc;
    if(isImg&&mediaSrc&&O.renderMediaCard){
      el.append(O.renderMediaCard({rawSrc:mediaSrc,caption:p.description||p.caption||'',filename:fname,compact:m.info.role==='user'}));
    }else if(isImg&&mediaSrc){
      el.append(h('img',{class:'thumb',src:mediaSrc}));
    }else{
      el.append(h('span',{class:'chip'},O.fileIcon(fname),h('span',null,fname)));
    }
    break}
  case'agent':el.className='part fchips';el.textContent='';el.append(h('span',{class:'chip'},O.icon('bot'),h('span',null,'@'+p.name)));break;
  case'patch':el.className='part';el.textContent='';el.append(h('span',{class:'chip'},O.icon('edit'),h('span',null,'Изменено файлов: '+((p.files||[]).length))));break;
  case'retry':el.className='part warn s';el.textContent='Повтор: '+((p.error&&p.error.data&&p.error.data.message)||'запрос не удался');break;
  case'compaction':el.className='part sep';el.textContent='Контекст сжат';break}}
function countDiffLines(txt){
  let a=0,d=0;
  String(txt||'').split('\n').forEach(l=>{
    if(l.startsWith('+')&&!l.startsWith('+++'))a++;
    else if(l.startsWith('-')&&!l.startsWith('---'))d++;
  });
  return{a,d};
}
function countOps(ops){
  let a=0,d=0;
  for(const o of ops||[]){
    if(o[0]==='+')a++;
    else if(o[0]==='-')d++;
  }
  return{a,d};
}
function getMsgDiff(m){
  let patchA=0,patchD=0,patchFound=false;
  let toolA=0,toolD=0,toolFound=false;
  let textA=0,textD=0,textFound=false;
  for(const p of m.parts){
    if(p.type==='patch'){
      patchFound=true;
      let fileA=0,fileD=0,hasFiles=false;
      if(Array.isArray(p.files)){
        p.files.forEach(f=>{
          if(typeof f.additions==='number'){fileA+=f.additions;hasFiles=true}
          if(typeof f.deletions==='number'){fileD+=f.deletions;hasFiles=true}
        });
      }
      if(hasFiles){patchA+=fileA;patchD+=fileD}
      else if(p.diff){
        const c=countDiffLines(p.diff);patchA+=c.a;patchD+=c.d;
      }
    }
    if(p.type==='tool'){
      const st=p.state||{},md=st.metadata||p.metadata||{},i=st.input||{};
      let counted=false;
      const diff=md.diff||p.diff;
      if(typeof diff==='string'&&diff){const c=countDiffLines(diff);toolA+=c.a;toolD+=c.d;counted=true}
      else if(p.tool==='edit'&&i.oldString!=null){const c=countOps(O.diffOps(String(i.oldString),String(i.newString||'')));toolA+=c.a;toolD+=c.d;counted=true}
      else if(p.tool==='write'&&typeof i.content==='string'){toolA+=i.content.replace(/\n$/,'').split('\n').length;counted=true}
      else if((p.tool==='patch'||p.tool==='apply_patch')&&typeof i.patch==='string'){const c=countDiffLines(i.patch);toolA+=c.a;toolD+=c.d;counted=true}
      if(counted)toolFound=true;
    }
    if(p.type==='text'&&p.text){
      const mMatch=p.text.match(/(?:^|\n)\s*([+]\d+)\s+([-]\d+)\s*(?:$|\n)/);
      if(mMatch){
        textA=parseInt(mMatch[1].replace('+',''),10)||0;
        textD=parseInt(mMatch[2].replace('-',''),10)||0;
        textFound=true;
      }
    }
  }
  if(patchFound&&(patchA>0||patchD>0||!toolFound))return{added:patchA,deleted:patchD,found:patchFound};
  if(toolFound)return{added:toolA,deleted:toolD,found:true};
  if(textFound)return{added:textA,deleted:textD,found:textFound};
  return{added:0,deleted:0,found:false};
}

O.showMessageDiff=m=>{
  const items=[];
  for(const p of (m&&m.parts)||[]){
    if(p.type==='patch'){
      if(typeof p.diff==='string'&&p.diff.trim())items.push({name:(p.files&&p.files[0]&&(p.files[0].file||p.files[0].filename))||'patch',diff:p.diff});
      else if(Array.isArray(p.files))p.files.forEach(f=>{
        if(typeof f.before==='string'||typeof f.after==='string')items.push({name:f.file||f.filename||'file',ops:O.diffOps(f.before||'',f.after||'')});
      });
    }else if(p.type==='tool'){
      const st=p.state||{},md=st.metadata||p.metadata||{},i=st.input||{};
      const diff=md.diff||p.diff;
      if(typeof diff==='string'&&diff.trim())items.push({name:i.filePath||i.path||i.filename||p.tool||'diff',diff});
      else if(p.tool==='edit'&&i.oldString!=null)items.push({name:i.filePath||i.path||'edit',ops:O.diffOps(String(i.oldString),String(i.newString||''))});
      else if(p.tool==='write'&&typeof i.content==='string')items.push({name:i.filePath||i.path||'write',ops:O.diffOps('',String(i.content))});
      else if((p.tool==='patch'||p.tool==='apply_patch')&&typeof i.patch==='string')items.push({name:i.filePath||'patch',diff:i.patch});
    }
  }
  if(!items.length){O.toast(O.tr('No code changes in this message','В этом сообщении нет изменений кода'),2500);return}
  const body=h('div');
  items.forEach(it=>{
    body.append(h('div',{class:'diff-file-head'},O.fileIcon(O.basename(it.name)),h('b',null,O.basename(it.name)),h('span',{class:'mut s'},O.dirname(it.name)==='.'?'':O.dirname(it.name))));
    body.append(it.diff!=null?O.unifiedEl(it.diff):O.diffEl(it.ops,3));
  });
  O.sheet(O.tr('Changes in this message','Изменения в этом сообщении'),body,{full:true});
};

function render(el){
  const p=el._p;if(el._t===p.text)return;el._t=p.text;
  let cleanText=p.text||'';
  // Strip trailing "+N -M" line from text so it is not duplicated above the badge!
  cleanText=cleanText.replace(/(?:\r?\n)+\s*[+]\d+\s+[-]\d+\s*$/g,'');
  O.mdInto(el,cleanText);
  if(O.initCustomWidgets)O.initCustomWidgets(el);
}
function renderR(el,b){const p=el._p;const t=p.text||p.reasoning||p.thinking||'';if(b._t===t)return;b._t=t;O.mdInto(b,t);
  const run=!(p.time&&p.time.end);el._d.firstChild.textContent=run?'Думает…':'Размышления'}
function sched(id,fn){if(timers.has(id))return;timers.set(id,setTimeout(()=>{timers.delete(id);fn();if(stick)scrollEnd()},100))}

function metaUpdate(el,m){
  const meta=el.lastChild;const i=m.info;meta.textContent='';
  if(i.role==='assistant'){
    const t=[];if(i.modelID)t.push(i.modelID);
    if(i.time&&i.time.completed&&i.time.created)t.push(((i.time.completed-i.time.created)/1000).toFixed(1)+'s');
    if(i.tokens&&(i.tokens.output||i.tokens.input))t.push(O.fmtK((i.tokens.input||0)+(i.tokens.output||0))+' tok');
    if(i.cost)t.push('$'+i.cost.toFixed(4));
    meta.append(h('span',null,t.join('  ')));

    // Render line statistics at the bottom of assistant messages that changed files
    const diffStat=getMsgDiff(m);
    const oldDiffLine=el.firstChild.querySelector('.msg-diff-line')||el.firstChild.querySelector('.msg-diff-badge');
    const hasDiff=diffStat.found||diffStat.added>0||diffStat.deleted>0;
    if(hasDiff){
      const badge=h('div',{
        class:'msg-diff-line',
        title:O.tr('View changes in this message','Посмотреть изменения в этом сообщении'),
        onclick:()=>O.showMessageDiff(m)
      },
      h('span',{class:'diff-label'},O.tr('Written','Написано')),
      h('span',{class:'diff-stat-val add'},'+'+diffStat.added),
      h('span',{class:'diff-sep'}),
      h('span',{class:'diff-label'},O.tr('Deleted','Стёрто')),
      h('span',{class:'diff-stat-val del'},'-'+diffStat.deleted));
      if(oldDiffLine)oldDiffLine.replaceWith(badge);
      else el.firstChild.append(badge);
    }else if(oldDiffLine){
      oldDiffLine.remove();
    }

    if(i.error&&i.error.name!=='MessageAbortedError'){const old=el.firstChild.querySelector('.errbox');if(old)old.remove();
      let em=(i.error.data&&i.error.data.message)||i.error.name||'Ошибка';
      if(/quota|usage.?limit|rate.?limit|limit\s+reached|insufficient|exceeded|credit|\b429\b/i.test(String(em))){
        em+='\n\n'+quotaHint();
        O.notifyLimit(O.tr('Limit Reached','Лимит исчерпан'),O.tr('Free quota limit reached.','Бесплатные лимиты закончились.'));
        O.showQuotaAccountModal(em);
      }
      el.firstChild.append(h('div',{class:'errbox'},em))}
    else if(i.error)meta.append(h('span',{class:'warn'},'прервано'))}
  meta.append(h('span',{class:'grow'}),h('button',{class:'ibtn s',onclick:()=>msgActions(m)},O.icon('dots')))}
function msgText(m){return m.parts.filter(p=>p.type==='text'&&!p.synthetic).map(p=>p.text).join('\n')}
function msgActions(m){O.actions(m.info.role==='user'?'Сообщение':'Ответ',[
  {label:'Копировать текст',icon:'copy',fn:()=>O.native.copy(msgText(m))},
  {label:'Экспорт сессии в Markdown',icon:'doc',fn:()=>exportSessionMd()},
  m.info.role==='user'?{label:'Отменить отсюда',sub:'Откатить это сообщение и всё после него',icon:'undo',fn:()=>revertTo(m)}:null,
  {label:'Форк сессии отсюда',icon:'branch',fn:()=>forkAt(m)}])}
function exportSessionMd(){
  if(!S.sid)return;
  const list=O.visibleMsgs(S.sid);
  if(!list.length)return O.toast('Сессия пуста');
  let out='# OpenCode Mobile — '+(S.sessions.find(x=>x.id===S.sid)?.title||'Сессия')+'\n\n';
  list.forEach(m=>{
    out+=(m.info.role==='user'?'### Пользователь':'### Ассистент')+'\n\n';
    m.parts.forEach(p=>{
      if(p.type==='text'&&p.text)out+=p.text+'\n\n';
      else if(p.type==='reasoning'&&(p.text||p.reasoning))out+='> *Размышления:*\n> '+String(p.text||p.reasoning).split('\n').join('\n> ')+'\n\n';
      else if(p.type==='tool'&&p.state&&p.state.input)out+='```bash\n# Tool: '+(p.tool||'')+'\n'+JSON.stringify(p.state.input,null,2)+'\n```\n\n';
    });
  });
  O.actions('Экспорт сессии в Markdown',[
    {label:'Скопировать Markdown',icon:'copy',fn:()=>O.native.copy(out)},
    {label:'Поделиться файлом/текстом',icon:'share',fn:()=>{if(O.native&&O.native.share)O.native.share(out);else O.native.copy(out)}}]);
}
async function revertTo(m){try{await api('POST','/session/'+S.sid+'/revert',{messageID:m.info.id});if(m.info.role==='user'){ta.value=msgText(m);onInput()}}catch(e){O.toast(e.message)}}
async function forkAt(m){try{const s=await api('POST','/session/'+S.sid+'/fork',{messageID:m.info.id});await O.loadSessions();await O.openSession(s.id)}catch(e){O.toast(e.message)}}

// ---- tools ----
function toolMeta(p){const st=p.state||{},i=st.input||{},r=O.rel;
  switch(p.tool){
  case'bash':return{ic:'terminal',t:i.description||'Команда',s:String(i.command||'').split('\n')[0]};
  case'read':{
    const out=st.output!=null?st.output:(st.metadata&&st.metadata.output);
    const lineCnt=out?String(out).split('\n').length:null;
    return{ic:'file',t:'Чтение',s:r(i.filePath)+(lineCnt?(' ('+lineCnt+' строк)'):'')};
  }
  case'edit':return{ic:'edit',t:'Правка',s:r(i.filePath)};
  case'write':return{ic:'edit',t:'Запись',s:r(i.filePath)};
  case'patch':case'apply_patch':return{ic:'edit',t:'Патч',s:''};
  case'glob':return{ic:'search',t:'Glob',s:i.pattern};
  case'grep':return{ic:'search',t:'Grep',s:i.pattern};
  case'list':return{ic:'folder',t:'Список',s:r(i.path||'.')};
  case'webfetch':return{ic:'globe',t:'Загрузка',s:i.url};
  case'websearch':case'codesearch':return{ic:'globe',t:'Поиск',s:i.query};
  case'task':return{ic:'bot',t:i.description||'Субагент',s:i.subagent_type};
  case'todowrite':case'todoread':return{ic:'list',t:'Задачи',s:(i.todos||[]).length?(i.todos.length+' шт.'):''};
  case'skill':return{ic:'bulb',t:'Навык',s:i.name};
  default:return{ic:'code',t:st.title||p.tool,s:''}}}
function toolBody(p){const st=p.state||{},i=st.input||{},md=st.metadata||{};
  const out=O.stripAnsi(st.output!=null?st.output:(md.output||''));const b=h('div');
  const pre=(t,c)=>h('pre',{class:c||'term-pre'},t);
  switch(p.tool){
  case'bash':b.append(pre('$ '+(i.command||'')+(out?'\n'+out:'')));if(md.exit!=null&&md.exit!==0)b.append(h('div',{class:'mut s'},'код '+md.exit));break;
  case'edit':if(typeof md.diff==='string'&&md.diff)b.append(O.unifiedEl(md.diff));else if(i.oldString!=null)b.append(O.diffEl(O.diffOps(String(i.oldString),String(i.newString||'')),2));break;
  case'write':b.append(pre(String(i.content||'').split('\n').slice(0,80).join('\n'),'code-pre'));break;
  case'read':b.append(pre(out.split('\n').slice(0,60).join('\n'),'code-pre'));break;
  case'todowrite':(i.todos||[]).forEach(t=>b.append(h('div',{class:'todo-i'+(t.status==='completed'?' done':'')},O.icon(t.status==='completed'?'check':'chevR'),t.content)));break;
  case'task':{const sid=md.sessionId||md.sessionID;if(i.prompt)b.append(h('div',{class:'mut s'},String(i.prompt).slice(0,500)));
    if(sid)b.append(h('button',{class:'btn sm',style:{marginTop:'8px'},onclick:()=>openChild(sid)},'Открыть сессию субагента'));if(out)b.append(pre(out.slice(0,3000),'code-pre'));break}
  default:if(Object.keys(i).length)b.append(pre(JSON.stringify(i,null,2).slice(0,3000),'code-pre'));if(out)b.append(pre(out.slice(0,6000),'code-pre'))}
  if(st.status==='error'&&st.error)b.append(h('div',{class:'errbox'},String(st.error)));return b}
function extractToolMedia(p){
  const st=p.state||{},md=st.metadata||{},out=String(st.output||md.output||'');
  const seen=new Set(),res=[];
  const add=(raw,fname,cap)=>{
    const u=String(raw||'').trim();if(!u||seen.has(u))return;
    const isImg=/^data:image\//i.test(u)||/\.(png|jpe?g|webp|gif|bmp|avif)(?:$|[?#])/i.test(u)||/\.(png|jpe?g|webp|gif|bmp|avif)$/i.test(fname||'');
    if(!isImg)return;
    seen.add(u);res.push({rawSrc:u,filename:fname||O.basename(u.split('?')[0])||'image.png',caption:cap||''});
  };
  if(Array.isArray(st.attachments))st.attachments.forEach(a=>{if(a&&(a.url||a.uri||a.path))add(a.url||a.uri||a.path,a.filename||a.name,a.description||a.caption)});
  if(Array.isArray(st.content))st.content.forEach(c=>{if(c&&(c.type==='file'||c.type==='image')&&(c.url||c.uri||c.data)){
    const mime=c.mime||c.mimeType||'image/png';
    const src=c.url||c.uri||(c.data?'data:'+mime+';base64,'+c.data:'');
    if(mime.startsWith('image/')||src.startsWith('data:image/'))add(src,c.filename||c.name);
  }});
  [md.outputPaths,md.files,md.attachments].forEach(arr=>{if(Array.isArray(arr))arr.forEach(x=>add(typeof x==='string'?x:(x&&(x.url||x.path))))});
  let m;const re=/^MEDIA_SAVED:([^\r\n]+)/gm;
  while((m=re.exec(out))!==null)add(m[1].trim());
  return res;
}
function fillTool(el,p){const st=p.state||{},m=toolMeta(p),isOpen=S.opts.details&&open.has(p.id);
  el.className='part tool '+(st.status||'pending')+(isOpen?' open':'');el.textContent='';el._p=p;
  const stat=st.status==='running'||st.status==='pending'?h('span',{class:'spin'}):st.status==='error'?O.icon('alert','err'):O.icon('check','ok');
  el.append(h('button',{class:'tool-h',onclick:()=>{if(!S.opts.details)return;el._auto=2;open.has(p.id)?open.delete(p.id):open.add(p.id);fillTool(el,el._p)}},
    O.icon(m.ic),h('span',{class:'t'},m.t),h('span',{class:'sub grow'},m.s||''),stat,S.opts.details?O.icon('chevR','chev'):null));
  const media=extractToolMedia(p);
  if(media.length&&O.renderMediaCard){
    const box=h('div',{style:{marginTop:'8px',display:'flex',flexDirection:'column',gap:'8px'}});
    media.forEach(it=>box.append(O.renderMediaCard({rawSrc:it.rawSrc,filename:it.filename,caption:it.caption,compact:true})));
    el.append(box);
  }
  if(isOpen)el.append(h('div',{class:'tool-b'},toolBody(p)))}
async function openChild(sid){try{if(!O.child(sid)){const s=await api('GET','/session/'+sid);(S.childSessions=S.childSessions||{})[sid]=s}}catch{}trail.push(S.sid);await O.openSession(sid);bus.emit('session')}

// ---- banners / dock ----
function renderRevert(){const s=cur();revEl.textContent='';
  if(trail.length&&S.sid&&O.child(S.sid)){revEl.classList.remove('hide');revEl.append(h('div',{class:'card revert-banner',style:{display:'flex',alignItems:'center',gap:'10px'}},h('span',{class:'grow'},'Сессия субагента'),h('button',{class:'btn sm',onclick:()=>{const p=trail.pop();O.openSession(p)}},'Назад')));return}
  if(s&&s.revert){revEl.classList.remove('hide');revEl.append(h('div',{class:'card revert-banner',style:{display:'flex',alignItems:'center',gap:'10px'}},h('span',{class:'revert-icon'},O.icon('undo')),h('span',{class:'grow',style:{color:'var(--fg)'}},'Изменения отменены'),h('button',{class:'btn sm',onclick:doRedo},O.icon('redo'),'Вернуть')))}
  else revEl.classList.add('hide')}
function renderTodos(){const t=(S.todos[S.sid]||[]);todoEl.textContent='';
  if(!t.length||t.every(x=>x.status==='completed')){todoEl.classList.add('hide');return}
  todoEl.classList.remove('hide');const done=t.filter(x=>x.status==='completed').length;
  const list=h('div',{class:'hide',style:{marginTop:'6px'}},t.map(x=>h('div',{class:'todo-i'+(x.status==='completed'?' done':'')},O.icon(x.status==='completed'?'check':'chevR'),x.content)));
  todoEl.append(h('div',{class:'card',style:{padding:'8px 12px'}},h('button',{style:{display:'flex',alignItems:'center',gap:'8px',width:'100%'},onclick:()=>list.classList.toggle('hide')},O.icon('list'),h('b',{class:'grow',style:{textAlign:'left'}},'Задачи '+done+'/'+t.length),O.icon('chevD')),list))}
function renderStatus(){const s=S.status[S.sid];statusEl.textContent='';
  if(!s||s.type==='idle'){statusEl.classList.add('hide');return}
  statusEl.classList.remove('hide');const mName=S.model&&S.model.modelID?(' · '+S.model.modelID):'';
  statusEl.append(h('span',{class:'spin'}),h('span',null,s.type==='retry'?('Повтор (попытка '+s.attempt+')'+(s.message?': '+s.message:'')):('Генерация ответа'+mName)))}
function renderDock(){dockEl.textContent='';
  const curPerms=S.perms.filter(p=>p.sessionID===S.sid);
  const curQuestions=S.questions.filter(q=>q.sessionID===S.sid);
  curPerms.forEach(p=>dockEl.append(permCard(p)));
  curQuestions.forEach(q=>dockEl.append(questionCard(q)));
  const other=S.perms.filter(p=>p.sessionID!==S.sid)[0]||S.questions.filter(q=>q.sessionID!==S.sid)[0];
  if(other)dockEl.append(h('button',{class:'hud-dock-card',style:{padding:'12px 16px',display:'flex',alignItems:'center',gap:'10px',textAlign:'left',cursor:'pointer'},onclick:()=>O.openSession(other.sessionID)},O.icon('alert','warn'),h('b',null,'Другая сессия ждёт вас')));
  if(chatRoot){chatRoot.classList.toggle('dock-active',curPerms.length>0||curQuestions.length>0)}
  O.updateBadges&&O.updateBadges()}
function permCard(p){const btns=[];
  const reply=async r=>{btns.forEach(b=>b.disabled=true);try{await O.replyPermission(p,r)}catch(e){btns.forEach(b=>b.disabled=false);O.toast(e.message)}};
  btns.push(h('button',{class:'btn-dock-skip',style:{color:'#f87171'},onclick:()=>reply('reject')},O.tr('Reject','Отклонить')),
            h('button',{class:'btn-dock-skip',onclick:()=>reply('once')},O.tr('Once','Разрешить раз')),
            h('button',{class:'btn-dock-submit',onclick:()=>reply('always')},O.tr('Always','Всегда')));
  const diff=p.metadata&&typeof p.metadata.diff==='string'?p.metadata.diff:'';
  return h('div',{class:'hud-dock-card'},
    h('div',{class:'hud-dock-header'},
      h('div',{class:'hud-dock-header-left'},
        h('div',{class:'badge-icon',style:{background:'var(--acc-soft)',color:'var(--acc)'}},O.icon('shield')),
        h('b',null,O.tr('Permission Request','Запрос разрешения'))),
      h('span',{class:'mut',style:{fontSize:'11.5px',background:'var(--bg3)',padding:'3px 8px',borderRadius:'999px'}},p.title)),
    h('div',{class:'hud-dock-body'},
      h('div',{style:{fontSize:'14px',fontWeight:'600',color:'var(--fg)',marginBottom:'4px'}},p.title),
      p.patterns.length?h('div',{class:'mono s mut',style:{background:'var(--code)',padding:'8px 10px',borderRadius:'10px',wordBreak:'break-all'}},p.patterns.join('\n')):null,
      diff?h('details',{style:{marginTop:'6px'}},h('summary',{class:'s'},'Показать изменения'),O.unifiedEl(diff)):null),
    h('div',{class:'hud-dock-footer'},btns));
}
function questionCard(q){const qs=q.questions||[];const sel=qs.map(()=>new Set());const custom=qs.map(()=>'');
  qs.forEach((x,i)=>{
    if(Array.isArray(x.default))x.default.forEach(d=>sel[i].add(d));
    else if(typeof x.default==='string'&&x.default)sel[i].add(x.default);
  });
  const send=async()=>{
    const answers=qs.map((_,i)=>[...sel[i],...(custom[i]?.trim()?[custom[i].trim()]:[])]);
    const hasAny=answers.some(a=>a.length>0);
    if(!hasAny&&qs.length>0){
      O.toast(O.tr('Please select an option or type an answer, or click Skip','Выберите вариант или введите ответ, либо нажмите «Пропустить»'));
      return;
    }
    try{
      await api('POST','/question/'+q.id+'/reply',{answers});
      S.questions=S.questions.filter(x=>x.id!==q.id);
      renderDock();
    }catch(e){O.toast(e.message)}
  };
  const rej=async()=>{try{await api('POST','/question/'+q.id+'/reject',{});S.questions=S.questions.filter(x=>x.id!==q.id);renderDock()}catch(e){O.toast(e.message)}};
  const body=qs.map((x,i)=>{
    const isMulti=!!x.multiple;
    const qBadge=qs.length>1?h('div',{class:'dock-q-num'},O.tr('Question','Вопрос')+' '+(i+1)+' / '+qs.length):null;
    const opts=h('div',{class:'dock-opts-list'},(x.options||[]).map(o=>{
      const isInit=sel[i].has(o.label);
      const indicator=h('div',{class:'dock-opt-indicator'+(isMulti?' check':'')});
      const b=h('button',{
        class:'dock-opt-row'+(isMulti?' multiple':'')+(isInit?' on':''),
        onclick:()=>{
          if(isMulti){
            sel[i].has(o.label)?sel[i].delete(o.label):sel[i].add(o.label);
          }else{
            sel[i].clear();
            sel[i].add(o.label);
          }
          opts.querySelectorAll('.dock-opt-row').forEach((bb,k)=>bb.classList.toggle('on',sel[i].has(x.options[k].label)));
        }
      },
      indicator,
      h('div',{class:'dock-opt-content'},
        h('span',{class:'dock-opt-label'},o.label),
        o.description?h('span',{class:'dock-opt-desc'},o.description):null));
      return b;
    }));
    const inp=x.custom===false?null:h('input',{
      class:'dock-custom-inp',
      placeholder:O.tr('Type your own answer...','Свой вариант ответа...'),
      oninput:e=>{custom[i]=e.target.value},
      onkeydown:e=>{if(e.key==='Enter'){e.preventDefault();send()}},
      onfocus:e=>{setTimeout(()=>e.target.scrollIntoView({behavior:'smooth',block:'center'}),250)}
    });
    return h('div',{class:'dock-q-section'},
      qBadge,
      x.header?h('div',{class:'dock-q-header'},x.header):null,
      h('div',{class:'dock-q-title'},x.question),
      opts,
      inp);
  });
  return h('div',{class:'hud-dock-card'},
    h('div',{class:'hud-dock-header'},
      h('div',{class:'hud-dock-header-left'},
        h('div',{class:'badge-icon'},O.icon('help')),
        h('b',null,O.tr('OpenCode asks','OpenCode спрашивает'))),
      h('span',{class:'mut',style:{fontSize:'11.5px',background:'var(--bg3)',padding:'3px 8px',borderRadius:'999px'}},
        qs.length>1?(qs.length+' '+O.tr('questions','вопроса')):(O.tr('Action needed','Требуется выбор')))),
    h('div',{class:'hud-dock-body'},body),
    h('div',{class:'hud-dock-footer'},
      h('button',{class:'btn-dock-skip',onclick:rej},O.tr('Skip','Пропустить')),
      h('button',{class:'btn-dock-submit',onclick:send},O.icon('send'),O.tr('Send reply','Отправить ответ'))));
}

// ---- composer ----
let quotaModalOpen=false;
O.showQuotaAccountModal=msg=>{
  if(quotaModalOpen)return;
  if(S.account&&S.account.email){
    O.toast(O.tr('Quota limit reached for this model. Switch to another model or connect your provider key: Settings → Providers.','Квота для этой модели исчерпана. Выберите другую модель или подключите свой ключ: Настройки → Провайдеры.'),6000);
    return;
  }
  quotaModalOpen=true;
  const content=h('div',{style:{textAlign:'center',padding:'12px 8px 8px'}},
    h('div',{class:'quota-hero-icon'},O.icon('zap')),
    h('b',{style:{fontSize:'17px',display:'block',marginBottom:'8px',color:'var(--fg)'}},O.tr('Free Quota Limit Reached','Лимит бесплатной квоты исчерпан')),
    h('p',{class:'mut',style:{fontSize:'13.5px',lineHeight:'1.5',margin:'0 0 18px',textAlign:'center'}},
      O.tr(
        'You are currently using shared guest quota without an account. Sign in with Google or GitHub to receive your personal free OpenCode quota, or add your own provider API key.',
        'Вы используете общую квоту без авторизации, которая временно исчерпана.\n\nВойдите через Google или GitHub, чтобы получить персональную бесплатную квоту OpenCode, либо подключите собственный API ключ.'
      )
    ),
    h('button',{class:'btn pri wide',style:{marginBottom:'10px'},onclick:()=>{
      s.close();
      const wst=O.native&&O.native.warpStatus?O.native.warpStatus():{};
      if((wst.enabled||wst.autoFallback)&&O.warpRotateAndRetry){
        O.warpRotateAndRetry(S.sid);
      }else if(O.openWarpSettings){
        O.openWarpSettings();
      }
    }},O.icon('refresh'),(()=>{
      const wst=O.native&&O.native.warpStatus?O.native.warpStatus():{};
      return (wst.enabled||wst.autoFallback)
        ?O.tr('Rotate IP via WARP & Retry','Сменить IP через WARP и повторить')
        :O.tr('Bypass Limit with WARP / Proxy','Обойти лимит через WARP / Proxy');
    })()),
    h('button',{class:'btn wide',style:{marginBottom:'10px'},onclick:()=>{s.close();O.openAccount()}},O.icon('user'),O.tr('Sign in with Google / GitHub','Войти через Google / GitHub')),
    h('button',{class:'btn wide',style:{marginBottom:'10px'},onclick:()=>{s.close();O.openConnect()}},O.icon('key'),O.tr('Connect own API key','Подключить свой API ключ')),
    h('button',{class:'btn wide',onclick:()=>s.close()},O.tr('Later','Позже'))
  );
  const s=O.sheet(O.tr('OpenCode Quota','Квота OpenCode'),content,{hud:true,onClose:()=>{quotaModalOpen=false}});
};

const quotaHint=()=>S.account&&S.account.email
  ?'Квота этой модели исчерпана. Перезапустите сервер (Настройки → Совместимость), выберите другую модель или подключите свой ключ: Настройки → Модели → Подключить провайдера'
  :'Квота исчерпана. Войдите в аккаунт opencode (Google/GitHub): Настройки → Аккаунт opencode — или подключите свой ключ: Настройки → Модели → Подключить провайдера';
function onMic(){
  const lang=O.store.get('lang','ru')==='en'?'en':'ru';
  if(window.OC&&window.OC.startVoiceInput){
    window.OC.startVoiceInput(lang);
    return;
  }
  const SpeechRec=window.SpeechRecognition||window.webkitSpeechRecognition;
  if(SpeechRec){
    try{
      const rec=new SpeechRec();
      rec.lang=lang==='en'?'en-US':'ru-RU';
      rec.interimResults=false;
      const b=O.$('.mic-btn');if(b)b.style.color='#ef4444';
      O.toast(O.tr('Listening... Speak now','Слушаю... Говорите'),2500);
      rec.onresult=e=>{
        const t=e.results[0][0].transcript;
        ta.value=(ta.value?ta.value+' ':'')+t;
        onInput();ta.focus();
      };
      rec.onerror=e=>{const b=O.$('.mic-btn');if(b)b.style.color='';O.toast(O.tr('Voice input error: ','Ошибка ввода: ')+(e.error||'недоступно'),2000)};
      rec.onend=()=>{const b=O.$('.mic-btn');if(b)b.style.color=''};
      rec.start();
      return;
    }catch(e){}
  }
  O.toast(O.tr('Voice input active. Speak using keyboard mic or type.','Голосовой ввод: используйте микрофон на клавиатуре или введите текст.'),3000);
}
function updateChips(){
  const m=S.model,mi=m&&O.modelInfo(m.providerID,m.modelID);
  const ag=S.agents.find(a=>a.name===S.agent);
  const agName=ag?ag.name:(S.agent||'Build');
  const spanAg=agentChip.querySelector('span');
  if(spanAg)spanAg.textContent=agName.charAt(0).toUpperCase()+agName.slice(1);
  let mName=O.tr('Select model','Выбрать модель');
  if(mi)mName=mi.name||m.modelID;
  else if(m)mName=m.modelID;
  const spanMo=modelChip.querySelector('span');
  if(spanMo)spanMo.textContent=mName;
  const mk=m?(m.providerID+'/'+m.modelID):'';
  const hasT2i=Boolean(mi&&O.isT2IModel&&O.isT2IModel(mi));
  const pureT2i=Boolean(mi&&O.isPureT2IModel&&O.isPureT2IModel(mi));
  if(mk!==lastModelKey){lastModelKey=mk;t2iOn=false}
  if(t2iChip){
    t2iChip.classList.toggle('hide',!hasT2i);
    t2iChip.classList.toggle('on',hasT2i&&t2iOn);
  }
  const nokey=!m&&S.up;
  nokeyEl.classList.toggle('hide',!nokey);
  ta.disabled=!S.up;
  if(ta.disabled){
    ta.placeholder=O.tr('Waiting for the local runtime...','Ожидание локального сервера...');
  }else if(hasT2i&&t2iOn){
    ta.placeholder=O.tr('Describe image to generate...','Опишите изображение для генерации...');
  }else{
    ta.placeholder=O.tr('Chat with OpenCode...','Чат с OpenCode...');
  }
  updateSend();
}
function updateSend(){
  const b=busy(),has=!!ta.value.trim()||!!atts.length||!!pathAtts.length;
  sendBtn.className=b&&!has?'stop':(has?'ready':'');
  sendBtn.textContent='';
  sendBtn.append(O.icon(b&&!has?'stop':'send'));
  sendBtn.disabled=!b&&!has;
}
function autosize(){
  ta.style.height='auto';
  ta.style.height=Math.min(ta.scrollHeight,180)+'px';
}
const BUILTINS=[
 {n:'imagine',get d(){return O.tr('Generate image in chat (T2I)','Сгенерировать фото в чате (T2I)')},i:'image',run:arg=>{if(arg&&arg.trim())O.generateImagePrompt(arg.trim());else{ta.value='/imagine ';onInput();ta.focus()}}},
 {n:'new',d:'Начать новую сессию',i:'plus',run:()=>O.newSession()},
 {n:'sessions',d:'Переключить сессию',i:'list',run:()=>O.openSessions()},
 {n:'models',d:'Выбрать модель',i:'cpu',run:()=>O.openModels()},
 {n:'agents',d:'Выбрать агента',i:'bot',run:()=>O.openAgents()},
 {n:'widgets',d:'Интерактивные виджеты HUD',i:'sliders',run:()=>O.openWidgetsModal()},
 {n:'preview',d:'Веб-предпросмотр проекта',i:'globe',run:arg=>O.openPreview(arg)},
 {n:'open',d:'Открыть приложение Android (например /open telegram)',i:'out',run:arg=>O.dispatchAction('open_app',arg)},
 {n:'connect',d:'Подключить провайдера (OpenCode Zen, OpenAI, ...)',i:'key',run:()=>O.openConnect()},
 {n:'init',d:'Создать или обновить AGENTS.md',i:'doc',run:()=>doInit()},
 {n:'undo',d:'Отменить последнее сообщение и его изменения файлов',i:'undo',run:()=>doUndo()},
 {n:'redo',d:'Вернуть отменённые изменения',i:'redo',run:()=>doRedo()},
 {n:'compact',d:'Сжать сессию, чтобы освободить контекст',i:'refresh',run:()=>doCompact()},
 {n:'share',d:'Поделиться сессией',i:'share',run:()=>doShare()},
 {n:'unshare',d:'Отключить шаринг',i:'share',run:()=>doUnshare()},
 {n:'export',d:'Экспорт диалога в Markdown',i:'down',run:()=>doExport()},
 {n:'themes',d:'Сменить тему',i:'palette',run:()=>O.openThemes()},
 {n:'thinking',d:'Показать или скрыть размышления',i:'bulb',run:()=>{S.opts.thinking=!S.opts.thinking;O.saveOpts();renderAll()}},
 {n:'details',d:'Раскрыть или закрепить детали инструментов',i:'code',run:()=>{S.opts.details=!S.opts.details;O.saveOpts();renderAll()}},
 {n:'status',d:'Серверы MCP, LSP и плагины',i:'server',run:()=>O.openStatus()},
 {n:'files',d:'Файлы проекта',i:'folder',run:()=>O.setTab('files')},
 {n:'terminal',d:'Терминал',i:'terminal',run:()=>O.setTab('term')},
 {n:'settings',d:'Настройки приложения',i:'sliders',run:()=>O.openSettings()},
 {n:'help',d:'Что я умею',i:'info',run:()=>O.openHelp()},
 {n:'exit',d:'Свернуть приложение (сервер продолжает работать)',i:'out',run:()=>OC.minimize()}];
async function sess(){if(S.sid)return S.sid;const s=await O.newSession();return s.id}
async function doInit(){if(!S.model)return O.openConnect();try{const sid=await sess();api('POST','/session/'+sid+'/init',{messageID:O.mid(),providerID:S.model.providerID,modelID:S.model.modelID},{timeout:3600000}).catch(e=>O.toast(e.message))}catch(e){O.toast(e.message)}}
async function doUndo(){const l=O.visibleMsgs(S.sid||'').filter(m=>m.info.role==='user');if(!l.length)return O.toast('Нечего отменять');revertTo(l[l.length-1])}
async function doRedo(){try{await api('POST','/session/'+S.sid+'/unrevert',{})}catch(e){O.toast(e.message)}}
async function doCompact(){if(!S.sid||!S.model)return;try{await api('POST','/session/'+S.sid+'/summarize',{providerID:S.model.providerID,modelID:S.model.modelID},{timeout:600000});O.toast('Сессия сжата')}catch(e){O.toast(e.message)}}
async function doShare(){try{const s=await api('POST','/session/'+S.sid+'/share',{});const u=s.share&&s.share.url;if(u){O.native.copy(u);O.native.share(u)}}catch(e){O.toast(e.message)}}
async function doUnshare(){try{await api('DELETE','/session/'+S.sid+'/share');O.toast('Шаринг остановлен')}catch(e){O.toast(e.message)}}
function doExport(){if(!S.sid)return;const md=O.visibleMsgs(S.sid).map(m=>'## '+(m.info.role==='user'?'Пользователь':'Ассистент')+'\n\n'+m.parts.filter(p=>p.type==='text'&&!p.synthetic).map(p=>p.text).join('\n\n')).join('\n\n');O.native.share(md)}
O.builtins=BUILTINS;
function hidePop(){popEl.classList.add('hide');popEl.textContent=''}
function showPop(items){popEl.textContent='';if(!items.length){hidePop();return}popEl.classList.remove('hide');
  items.slice(0,40).forEach(it=>popEl.append(h('button',{class:'it',style:{width:'100%',textAlign:'left'},onclick:it.pick},it.icon?it.icon:null,h('b',null,it.t),h('span',{class:'d grow'},it.d||''))))}
let atTimer;
function onInput(){autosize();updateSend();const v=ta.value,pos=ta.selectionStart||0,before=v.slice(0,pos);
  const sm=/^\/(\S*)$/.exec(before);
  if(sm){const q=sm[1].toLowerCase();
    const srv=(S.commands||[]).filter(c=>!BUILTINS.some(b=>b.n===c.name)).map(c=>({t:'/'+c.name,d:c.description||'Пользовательская команда',icon:O.icon('slash'),f:c.name,pick:()=>{ta.value='/'+c.name+' ';ta.focus();onInput()}}));
    const bi=BUILTINS.map(b=>({t:'/'+b.n,d:b.d,icon:O.icon(b.i),f:b.n,pick:()=>{ta.value='';autosize();hidePop();updateSend();b.run()}}));
    showPop([...bi,...srv].filter(x=>x.f.startsWith(q)||(q.length>1&&x.f.includes(q))));return}
  const am=/(?:^|\s)@(\S*)$/.exec(before);
  if(am){const q=am[1];clearTimeout(atTimer);atTimer=setTimeout(async()=>{
    const ags=O.subagents().filter(a=>a.name.startsWith(q)).map(a=>({t:'@'+a.name,d:a.description||'Субагент',icon:O.icon('bot'),pick:()=>insertMention('@'+a.name,{type:'agent',name:a.name},pos-q.length-1,pos)}));
    let files=[];try{files=await api('GET','/find/file?query='+encodeURIComponent(q)+'&limit=30')}catch{}
    const fi=(files||[]).map(f=>{const dir=f.endsWith('/');return{t:O.basename(f),d:f,icon:O.fileIcon(O.basename(f),dir),pick:()=>insertMention('@'+f,{type:'file',mime:dir?'application/x-directory':'text/plain',url:'file://'+S.dir+'/'+f.replace(/\/$/,''),filename:O.basename(f),source:{type:'file',path:S.dir+'/'+f.replace(/\/$/,''),text:{value:'@'+f,start:0,end:0}}},pos-q.length-1,pos)}});
    if(ta.value.slice(0,pos)===before)showPop([...ags,...fi])},160);return}
  hidePop()}
O.subagents=()=>(S.agents||[]).filter(a=>a.mode==='subagent'||a.mode==='all');
function insertMention(tok,part,from,to){ta.value=ta.value.slice(0,from)+tok+' '+ta.value.slice(to);const p=from+tok.length+1;ta.setSelectionRange(p,p);mentions.push({tok,part});hidePop();ta.focus();onInput()}
O.addMention=(path,isDir)=>{const rel=O.rel(path);const tok='@'+rel;const abs=path.startsWith('/')?path:S.dir+'/'+path;
  ta.value=(ta.value?ta.value+' ':'')+tok+' ';mentions.push({tok,part:{type:'file',mime:isDir?'application/x-directory':'text/plain',url:'file://'+abs,filename:O.basename(path),source:{type:'file',path:abs,text:{value:tok,start:0,end:0}}}});O.setTab('chat');ta.focus();onInput()};
O.attachPath=(path,name)=>{if(!path)return;const abs=String(path).startsWith('/')?String(path):'/'+path;
  const nm=name||O.basename(abs);const ext=O.ext(nm);const mime=ext==='png'||ext==='jpg'||ext==='jpeg'||ext==='webp'||ext==='gif'?'image/'+(ext==='jpg'?'jpeg':ext):'text/plain';
  const tok='@'+O.rel(abs);
  if(pathAtts.some(a=>a.path===abs))return;
  pathAtts.push({path:abs,name:nm,mime});
  ta.value=(ta.value?ta.value+' ':'')+tok+' ';
  mentions.push({tok,part:{type:'file',mime,url:'file://'+abs,filename:nm,source:{type:'file',path:abs,text:{value:tok,start:0,end:0}}}});
  renderAtts();onInput();ta.focus()};
O.insertText=t=>{ta.value=t;onInput();ta.focus()};
function onFiles(){const files=[...fileIn.files];fileIn.value='';for(const f of files){
  const mi=S.model&&O.modelInfo(S.model.providerID,S.model.modelID);
  if(!(f.type||'').startsWith('text/')&&mi&&mi.attachment===false){O.toast('Эта модель не принимает вложения');continue}
  if(f.size>8e6){O.toast(f.name+' слишком большой (макс. 8 МБ)');continue}
  const r=new FileReader();r.onload=()=>{atts.push({type:'file',mime:f.type||'application/octet-stream',filename:f.name,url:r.result});renderAtts()};r.readAsDataURL(f)}}
function renderAtts(){attsEl.textContent='';attsEl.classList.toggle('hide',!atts.length&&!pathAtts.length);
  atts.forEach((a,i)=>attsEl.append(h('span',{class:'chip'},a.mime.startsWith('image/')?h('img',{src:a.url,style:{height:'22px',borderRadius:'4px'}}):O.fileIcon(a.filename),h('span',null,a.filename),h('button',{onclick:()=>{atts.splice(i,1);renderAtts();updateSend()}},O.icon('x')))));
  pathAtts.forEach((a,i)=>attsEl.append(h('span',{class:'chip path-chip',title:a.path},O.fileIcon(a.name),h('span',null,a.name),h('button',{onclick:()=>{const tok='@'+O.rel(a.path);pathAtts.splice(i,1);mentions=mentions.filter(m=>m.tok!==tok);ta.value=ta.value.split(tok).join('').replace(/[ \t]{2,}/g,' ').trim();onInput();renderAtts()}},O.icon('x')))));
  updateSend()}
function resolveT2iEndpoint(){
  const cfg=O.readCfgObj?O.readCfgObj():{provider:{}};
  let authObj={};
  try{
    const ar=O.native&&O.native.readText?O.native.readText('/root/.local/share/opencode/auth.json'):null;
    if(ar&&ar.ok&&ar.text)authObj=JSON.parse(ar.text||'{}')||{};
  }catch(_){}
  const KNOWN_URLS={
    openai:'https://api.openai.com/v1',
    openrouter:'https://openrouter.ai/api/v1',
    xai:'https://api.x.ai/v1',
    togetherai:'https://api.together.xyz/v1',
    fireworks:'https://api.fireworks.ai/inference/v1'
  };
  const lookup=(pid,mid)=>{
    if(!pid||!mid)return null;
    const cp=(cfg.provider&&cfg.provider[pid])||{};
    const mp=(S.providers||[]).find(p=>p.id===pid)||{};
    const rawUrl=(cp.options&&cp.options.baseURL)||(mp.options&&mp.options.baseURL)||mp.api||KNOWN_URLS[pid]||'';
    const baseURL=String(rawUrl||'').trim().replace(/\/+$/,'');
    if(!baseURL)return null;
    const rawKey=(cp.options&&cp.options.apiKey)||(mp.options&&mp.options.apiKey)||(authObj[pid]&&(authObj[pid].key||authObj[pid].access||authObj[pid].token))||'';
    const headers=Object.assign({},(mp.options&&mp.options.headers)||{},(cp.options&&cp.options.headers)||{});
    return{providerID:pid,modelID:mid,baseURL,apiKey:String(rawKey||''),headers};
  };
  if(S.model){
    const curEp=lookup(S.model.providerID,S.model.modelID);
    if(curEp)return curEp;
  }
  for(const [pid,pdef] of Object.entries(cfg.provider||{})){
    const mods=pdef&&pdef.models?Object.entries(pdef.models):[];
    for(const [mid,mdef] of mods){
      if(O.isT2IModel&&O.isT2IModel(Object.assign({id:mid},mdef||{}))){
        const ep=lookup(pid,mid);if(ep)return ep;
      }
    }
  }
  return null;
}

function parseT2iImages(data){
  const imgs=[];let text='';
  const seen=new Set();
  const add=src=>{
    const s=String(src||'').trim();if(!s||seen.has(s))return;
    seen.add(s);imgs.push(s);
  };
  if(!data||typeof data!=='object')return{imgs,text};
  if(Array.isArray(data.data)){
    data.data.forEach(it=>{
      if(!it)return;
      if(it.b64_json)add('data:image/png;base64,'+it.b64_json);
      else if(it.url)add(it.url);
    });
  }
  if(data.url)add(data.url);
  if(data.image)add(typeof data.image==='string'&&!data.image.startsWith('http')&&!data.image.startsWith('data:')?('data:image/png;base64,'+data.image):data.image);
  if(Array.isArray(data.images)){
    data.images.forEach(it=>{
      if(typeof it==='string')add(it);
      else if(it&&it.b64_json)add('data:image/png;base64,'+it.b64_json);
      else if(it&&((it.image_url&&it.image_url.url)||it.url))add((it.image_url&&it.image_url.url)||it.url);
    });
  }
  const msg=data.choices&&data.choices[0]&&data.choices[0].message;
  if(msg){
    if(Array.isArray(msg.images)){
      msg.images.forEach(it=>{
        if(typeof it==='string')add(it);
        else if(it&&it.b64_json)add('data:image/png;base64,'+it.b64_json);
        else if(it&&((it.image_url&&it.image_url.url)||it.url))add((it.image_url&&it.image_url.url)||it.url);
      });
    }
    if(Array.isArray(msg.content)){
      msg.content.forEach(c=>{
        if(!c)return;
        if(c.type==='image_url'&&c.image_url&&c.image_url.url)add(c.image_url.url);
        else if(c.type==='image'&&(c.url||c.data))add(c.url||('data:'+(c.mime_type||'image/png')+';base64,'+c.data));
        else if(c.type==='text'&&c.text)text+=(text?'\n':'')+c.text;
      });
    }else if(typeof msg.content==='string'){
      let cleaned=msg.content;
      const b64Re=/!\[[^\]]*\]\((data:image\/[^)\s]+)\)|(data:image\/[a-zA-Z0-9+]+;base64,[A-Za-z0-9+/=]+)/g;
      let m;
      while((m=b64Re.exec(msg.content))!==null){add(m[1]||m[2]);cleaned=cleaned.replace(m[0],'')}
      const mdUrlRe=/!\[[^\]]*\]\((https?:\/\/[^)\s]+)\)/g;
      while((m=mdUrlRe.exec(msg.content))!==null){add(m[1]);cleaned=cleaned.replace(m[0],'')}
      const bareUrlRe=/https?:\/\/[^\s"'<>]+\.(?:png|jpe?g|webp|gif|avif)(?:\?[^\s"'<>]*)?/gi;
      while((m=bareUrlRe.exec(cleaned))!==null){add(m[0])}
      text=cleaned.trim();
    }
  }
  return{imgs,text};
}

O.generateImagePrompt=async(promptText)=>{
  const prompt=String(promptText||'').trim();
  if(!prompt)return;
  const ep=resolveT2iEndpoint();
  if(!ep){
    O.toast(O.tr('Connect a Custom API / OpenAI-compatible provider with a T2I model first','Сначала подключите Custom API провайдер с T2I-моделью'),4000);
    if(O.openConnect)O.openConnect();
    return;
  }
  let sid;
  try{sid=await sess()}catch(e){O.toast(e.message||'Session error');return}
  const uid=O.mid(),amid=O.mid();
  const userM=O.upsertMsg({id:uid,sessionID:sid,role:'user',time:{created:Date.now()},agent:S.agent,model:{providerID:ep.providerID,modelID:ep.modelID}});
  userM.local=true;
  userM.parts=[{id:'prt_u_'+uid,sessionID:sid,messageID:uid,type:'text',text:prompt}];
  bus.emit('msg',sid,userM);
  const assistM=O.upsertMsg({id:amid,sessionID:sid,role:'assistant',time:{created:Date.now()},agent:S.agent,providerID:ep.providerID,modelID:ep.modelID});
  assistM.local=true;
  assistM.parts=[{id:'prt_a_wait_'+amid,sessionID:sid,messageID:amid,type:'text',text:O.tr('_Generating image via '+ep.modelID+'..._','_Генерирую фото через '+ep.modelID+'..._')}];
  bus.emit('msg',sid,assistM);scrollEnd(true);

  const base=ep.baseURL;
  const vBase=/\/v\d+(?:alpha|beta)?(?:\/openai)?$/i.test(base)?base:(base+'/v1');
  const imgEp=vBase+'/images/generations';
  const chatEp=vBase+'/chat/completions';
  const hdrs=Object.assign({'Content-Type':'application/json'},ep.headers||{});
  if(ep.apiKey&&ep.apiKey!=='sk-local')hdrs['Authorization']='Bearer '+ep.apiKey;

  const isQuotaErr=(status,msg)=>status===429||/quota|usage.?limit|rate.?limit|limit\s+reached|insufficient|exceeded|credit|balance|funds|\b429\b/i.test(String(msg||''));
  const callApiOnce=async()=>{
    let lastStatus=0,lastMsg='';
    // 1. Standard /images/generations endpoint
    let r=await O.extFetch(imgEp,{method:'POST',headers:hdrs,body:{model:ep.modelID,prompt,n:1,response_format:'b64_json'},timeout:95000});
    if(!r.ok&&r.status===400&&/response_format/i.test(r.text||'')){
      r=await O.extFetch(imgEp,{method:'POST',headers:hdrs,body:{model:ep.modelID,prompt,n:1},timeout:95000});
    }
    if(r.ok){
      const parsed=parseT2iImages(r.data);
      if(parsed.imgs.length)return parsed;
    }else{
      lastStatus=r.status;
      lastMsg=(r.data&&r.data.error&&r.data.error.message)||(r.data&&r.data.message)||r.text||('HTTP '+r.status);
      if(lastStatus===401||lastStatus===403||isQuotaErr(lastStatus,lastMsg))throw Object.assign(new Error(lastMsg),{status:lastStatus});
    }
    // 2. Fallback /chat/completions for multimodal image models
    let rc=await O.extFetch(chatEp,{method:'POST',headers:hdrs,body:{model:ep.modelID,messages:[{role:'user',content:prompt}],modalities:['image','text']},timeout:95000});
    if(!rc.ok&&rc.status===400&&/modalit/i.test(rc.text||'')){
      rc=await O.extFetch(chatEp,{method:'POST',headers:hdrs,body:{model:ep.modelID,messages:[{role:'user',content:prompt}]},timeout:95000});
    }
    if(rc.ok){
      const parsed=parseT2iImages(rc.data);
      if(parsed.imgs.length)return parsed;
      throw new Error(parsed.text||O.tr('Model returned text without an image','Модель вернула ответ без изображения'));
    }
    const cMsg=(rc.data&&rc.data.error&&rc.data.error.message)||(rc.data&&rc.data.message)||rc.text||lastMsg||('HTTP '+(rc.status||lastStatus));
    throw Object.assign(new Error(cMsg),{status:rc.status||lastStatus});
  };

  try{
    let result;
    try{
      result=await callApiOnce();
    }catch(e){
      const wst=O.native&&O.native.warpStatus?O.native.warpStatus():{};
      if(isQuotaErr(e.status,e.message)&&wst.autoRotate&&(wst.enabled||wst.autoFallback)&&O.native&&O.native.warpRotate){
        O.toast(O.tr('Quota limit reached — rotating WARP IP...','Лимит квоты — меняем IP через WARP...'),3200);
        await new Promise(resolve=>{
          const tm=setTimeout(()=>{warpWaiter=null;resolve()},25000);
          warpWaiter=()=>{clearTimeout(tm);resolve()};
          O.native.warpRotate('');
        });
        result=await callApiOnce();
      }else throw e;
    }

    const parts=[];
    if(result.text)parts.push({id:'prt_t_'+amid,sessionID:sid,messageID:amid,type:'text',text:result.text});
    for(let i=0;i<result.imgs.length;i++){
      const rawSrc=result.imgs[i];
      const fname='t2i_'+Date.now()+'_'+(i+1)+'.png';
      let finalUrl=rawSrc,finalName=fname;
      if(O.native&&O.native.cacheMedia){
        const cached=await O.native.cacheMedia(rawSrc,fname);
        if(cached&&cached.ok&&cached.url){finalUrl=cached.url;finalName=cached.name||fname}
      }
      parts.push({id:'prt_img_'+amid+'_'+i,sessionID:sid,messageID:amid,type:'file',mime:'image/png',url:finalUrl,filename:finalName,description:prompt});
    }
    assistM.info.time={created:assistM.info.time.created,completed:Date.now()};
    assistM.parts=parts;
    bus.emit('msg',sid,assistM);scrollEnd(true);
    if(O.saveSessionMsgs)O.saveSessionMsgs(sid);
  }catch(e){
    assistM.info.time={created:assistM.info.time.created,completed:Date.now()};
    assistM.parts=[{id:'prt_err_'+amid,sessionID:sid,messageID:amid,type:'text',text:'**T2I Error:** '+String(e.message||'Request failed')}];
    bus.emit('msg',sid,assistM);
    if(isQuotaErr(e.status,e.message)){
      O.notifyLimit(O.tr('Limit Reached','Лимит исчерпан'),O.tr('Free quota limit reached.','Бесплатные лимиты закончились.'));
      O.showQuotaAccountModal(e.message);
    }else{
      O.toast(e.message||'T2I error',4000);
    }
  }
};

async function onSend(){
  if(busy()&&!ta.value.trim()&&!atts.length&&!pathAtts.length){try{await api('POST','/session/'+S.sid+'/abort',{})}catch(e){O.toast(e.message)}return}
  const text=ta.value.trim();if(!text&&!atts.length&&!pathAtts.length)return;
  const cm=/^\/(\S+)(?:\s+([\s\S]*))?$/.exec(text);
  if(cm){const b=BUILTINS.find(x=>x.n===cm[1]);if(b){clear();b.run(cm[2]);return}
    const sc=(S.commands||[]).find(c=>c.name===cm[1]);
    if(sc){if(!S.model)return O.openConnect();clear();try{const sid=await sess();api('POST','/session/'+sid+'/command',{command:sc.name,arguments:cm[2]||'',agent:S.agent,model:modelStr()},{timeout:3600000}).catch(e=>O.toast(e.message))}catch(e){O.toast(e.message)}return}}

  if(!S.model){O.toast('Подключите провайдера, чтобы выбрать модель');O.openConnect();return}
  const curMi=O.modelInfo(S.model.providerID,S.model.modelID);
  if(text&&!atts.length&&!pathAtts.length&&curMi&&O.isPureT2IModel&&O.isPureT2IModel(curMi)){
    O.toast(O.tr('This is an image model. Use /imagine <prompt>.','Это модель изображений. Используйте /imagine <запрос>.'),4000);return;
  }
  if(text&&!atts.length&&!pathAtts.length&&curMi&&O.isT2IModel&&O.isT2IModel(curMi)&&t2iOn){
    clear();await O.generateImagePrompt(text);return;
  }
  if(text.startsWith('!')){const cmd=text.slice(1).trim();
    if(/^opencode(\s+tui)?\s*$/i.test(cmd)){O.toast('Приложение уже является интерфейсом OpenCode. TUI — во вкладке «Терминал» → Shell, агент — во вкладке «Чат»',6000);clear();return}
    clear();try{const sid=await sess();api('POST','/session/'+sid+'/shell',{agent:S.agent,model:S.model,command:cmd},{timeout:3600000}).catch(e=>O.toast(e.message))}catch(e){O.toast(e.message)}return}
  const parts=[];
  if(O.updateReadmeForModel && S.model && S.model.modelID){
    O.updateReadmeForModel(S.model.modelID);
  }
  if(text)parts.push({type:'text',text});
  mentions.filter(m=>text.includes(m.tok)).forEach(m=>{const p=JSON.parse(JSON.stringify(m.part));if(p.source&&p.source.text){p.source.text.start=text.indexOf(m.tok);p.source.text.end=p.source.text.start+m.tok.length}parts.push(p)});
  atts.forEach(a=>parts.push(a));
  const mid=O.mid();clear();
  try{const sid=await sess();
    const m=O.upsertMsg({id:mid,sessionID:sid,role:'user',time:{created:Date.now()},agent:S.agent,model:S.model});m.local=true;
    m.parts=parts.map((p,i)=>Object.assign({id:'prt_local'+i,sessionID:sid,messageID:mid},p));bus.emit('msg',sid,m);scrollEnd(true);
    await api('POST','/session/'+sid+'/prompt_async',{messageID:mid,agent:S.agent,model:S.model,variant:S.variant||undefined,parts},{timeout:30000});
  }catch(e){
    const s=O.msgs(S.sid);s.list=s.list.filter(x=>x.info.id!==mid);s.idx.delete(mid);const el=msgEls.get(mid);if(el){el.remove();msgEls.delete(mid)}
    if(/quota|usage.?limit|rate.?limit|limit\s+reached|insufficient|exceeded|credit|\b429\b/i.test(String(e.message))){
      O.notifyLimit(O.tr('Limit Reached','Лимит исчерпан'),O.tr('Free quota limit reached.','Бесплатные лимиты закончились.'));
      O.showQuotaAccountModal(e.message);
    }else{
      O.toast(e.message,4000);
    }
    ta.value=text;onInput();
  }}
function clear(){ta.value='';atts=[];pathAtts=[];mentions=[];renderAtts();autosize();hidePop();updateSend()}
O.chat={renderAll,sess,focus:()=>ta&&ta.focus()};
})();
