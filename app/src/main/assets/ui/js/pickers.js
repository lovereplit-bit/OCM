(function(){
const O=window.OCM,h=O.h,S=O.S,api=O.api,bus=O.bus;
const search=(ph,fn)=>h('input',{placeholder:ph,oninput:e=>fn(e.target.value.toLowerCase()),style:{marginBottom:'8px'}});

// ---- sessions drawer ----
O.openSessions=()=>{
  const list=h('div'),q=search('Поиск по сессиям',()=>draw());
  const s=O.sheet('Сессии',h('div',null,h('button',{class:'btn pri wide',onclick:async()=>{s.close();try{await O.newSession()}catch(e){O.toast(e.message)}}},O.icon('plus'),'Новая сессия'),h('div',{style:{height:'10px'}}),q,list),{drawer:true,
    actions:h('button',{class:'ibtn',onclick:()=>{s.close();O.openSettings()}},O.icon('sliders'))});
  function draw(){list.textContent='';const f=q.value.toLowerCase();
    const items=S.sessions.filter(x=>O.sessionTitle(x).toLowerCase().includes(f));
    if(!items.length)list.append(h('div',{class:'mut',style:{padding:'20px',textAlign:'center'}},'Сессий пока нет'));
    items.forEach(x=>{const st=S.status[x.id],b=st&&st.type!=='idle',sm=x.summary;
      list.append(h('div',{class:'li'+(x.id===S.sid?' on':'')},
        h('button',{class:'t',style:{textAlign:'left',flex:1,minWidth:0},onclick:()=>{s.close();O.openSession(x.id)}},
          h('b',{style:{display:'block',overflow:'hidden',textOverflow:'ellipsis',whiteSpace:'nowrap'}},O.sessionTitle(x)),
          h('span',{class:'mut s',style:{overflow:'hidden',textOverflow:'ellipsis',whiteSpace:'nowrap'}},O.ago(x.time.updated)+(sm&&(sm.additions||sm.deletions)?'  +'+sm.additions+' -'+sm.deletions:''))),
        b?h('span',{class:'spin',style:{flex:'none'}}):null,h('button',{class:'ibtn s',style:{flex:'none'},onclick:()=>sessActions(x)},O.icon('dots'))))})}
  draw();bus.on('sessions',()=>{if(s.root.isConnected)draw()})};
function sessActions(x){O.actions(O.sessionTitle(x),[
  {label:'Переименовать',icon:'edit',fn:async()=>{const v=await O.ask('Переименование сессии',null,O.sessionTitle(x));if(v)try{await api('PATCH','/session/'+x.id,{title:v})}catch(e){O.toast(e.message)}}},
  {label:x.share?'Отключить шаринг':'Поделиться',icon:'share',fn:async()=>{try{if(x.share){await api('DELETE','/session/'+x.id+'/share')}else{const r=await api('POST','/session/'+x.id+'/share',{});if(r.share){O.native.copy(r.share.url);O.native.share(r.share.url)}}}catch(e){O.toast(e.message)}}},
  {label:'Форк',icon:'branch',fn:async()=>{try{const r=await api('POST','/session/'+x.id+'/fork',{});await O.loadSessions();O.openSession(r.id)}catch(e){O.toast(e.message)}}},
  {label:'Удалить',icon:'trash',danger:true,fn:async()=>{if(await O.confirm('Удалить сессию?',O.sessionTitle(x),'Удалить',true))try{await api('DELETE','/session/'+x.id);S.sessions=S.sessions.filter(y=>y.id!==x.id);if(S.sid===x.id)O.openSession(S.sessions[0]&&S.sessions[0].id||null);bus.emit('sessions')}catch(e){O.toast(e.message)}}}])}

// ---- projects + folder browser ----
O.openProjects=()=>{
  const recent=O.store.get('recent',[]),seen=new Set(),rows=[];
  const add=(p,sub)=>{if(!p||seen.has(p))return;seen.add(p);rows.push(O.listRow(O.fileIcon(O.basename(p)||'/',true),O.basename(p)||'/',p,p===S.dir?O.icon('check','ok'):null,()=>{s.close();O.setDir(p)}))};
  add(S.dir);recent.forEach(p=>add(p));(S.projects||[]).forEach(x=>add(x.worktree));add('/root');
  const s=O.sheet('Проекты',h('div',null,h('button',{class:'btn pri wide',onclick:()=>{s.close();O.browseDir(S.dir,p=>O.setDir(p))}},O.icon('folder'),'Открыть папку'),h('div',{style:{height:'8px'}}),rows))};
O.browseDir=(start,cb)=>{let path=start||'/root';
  const crumbs=h('div',{class:'mono s mut',style:{wordBreak:'break-all',margin:'6px 2px'}}),list=h('div');
  const short=(l,p)=>h('button',{class:'chip',onclick:()=>{path=p;load()}},O.icon('folder'),h('span',null,l));
  const s=O.sheet('Выбор папки',h('div',null,h('div',{style:{display:'flex',gap:'6px',overflowX:'auto'}},short('Домашняя','/root'),short('Хранилище','/sdcard'),short('Загрузки','/sdcard/Download'),short('Корень','/')),crumbs,list),{full:true,
    actions:h('button',{class:'btn pri sm',onclick:()=>{s.close();cb(path)}},'Выбрать')});
  function load(){const r=O.native.ls(path);crumbs.textContent=path;list.textContent='';
    if(path!=='/')list.append(O.listRow('chevL','..',null,null,()=>{path=O.dirname(path);load()}));
    if(r.err||r.ok===false){list.append(h('div',{class:'err',style:{padding:'12px'}},r.err||'Не удаётся прочитать папку'));return}
    (r.items||[]).filter(x=>x.dir).forEach(x=>list.append(O.listRow(O.fileIcon(x.name,true),x.name,null,null,()=>{path=(path==='/'?'':path)+'/'+x.name;load()})));
    list.append(h('button',{class:'btn wide',style:{marginTop:'10px'},onclick:async()=>{const n=await O.ask('Новая папка','Название');if(!n)return;const rr=O.native.fs('mkdir',(path==='/'?'':path)+'/'+n);rr.ok?load():O.toast(rr.err||'Не удалось')}},O.icon('plus'),'Новая папка'))}
  load()};

// ---- models / agents ----
O.openModels=()=>{
  const list=h('div'),q=search('Поиск моделей',()=>draw());
  const s=O.sheet('Модели',h('div',null,h('button',{class:'btn wide',onclick:()=>{s.close();O.openConnect()}},O.icon('key'),'Подключить провайдера'),h('div',{style:{height:'8px'}}),q,list));
  function draw(){list.textContent='';const f=q.value.toLowerCase();const all=O.allModels();
    if(!all.length)list.append(h('div',{class:'mut',style:{padding:'16px'}},'Моделей пока нет. Подключите провайдера или используйте бесплатные модели OpenCode Zen.'));
    const rec=O.store.get('recentModels',[]).map(k=>all.find(x=>x.pid+'/'+x.mid===k)).filter(Boolean);
    const groups=[];if(rec.length&&!f)groups.push(['Недавние',rec]);
    const by={};all.forEach(x=>{(by[x.pname||x.pid]=by[x.pname||x.pid]||[]).push(x)});Object.keys(by).forEach(k=>groups.push([k,by[k]]));
    groups.forEach(([g,ms])=>{const items=ms.filter(x=>!f||(x.name+' '+x.mid+' '+g).toLowerCase().includes(f));if(!items.length)return;
      list.append(h('div',{class:'grp'},g));
      items.forEach(x=>{const on=S.model&&S.model.providerID===x.pid&&S.model.modelID===x.mid,c=x.m.cost,free=c&&c.input===0&&c.output===0,ctx=x.m.limit&&x.m.limit.context;
        list.append(O.listRow('cpu',x.name,x.mid+(ctx?'  '+O.fmtK(ctx)+' конт.':'')+(free?'  бесплатно':''),on?O.icon('check','ok'):null,()=>{
          const r=O.store.get('recentModels',[]).filter(k=>k!==x.pid+'/'+x.mid);r.unshift(x.pid+'/'+x.mid);O.store.set('recentModels',r.slice(0,6));O.setModel(x.pid,x.mid);s.close()}))})})}
  draw()};
O.openAgents=()=>{const ags=O.primaryAgents();
  const s=O.sheet('Агенты',h('div',null,ags.map(a=>O.listRow('bot',a.name,a.description||'',a.name===S.agent?O.icon('check','ok'):null,()=>{O.setAgent(a.name);s.close()})),
    h('div',{class:'note',style:{marginTop:'12px'}},'Своих агентов добавляйте в Настройки → Агенты и правила.')))};

// ---- connect providers (/connect) ----
const POP=['opencode','anthropic','openai','github-copilot','google','openrouter','xai','deepseek','groq','mistral','together','fireworks','cerebras','nvidia','huggingface'];
O.openConnect=async()=>{
  const body=h('div',null,h('div',{class:'mut'},'Загрузка...'));const s=O.sheet('Подключить провайдера',body,{full:true});
  let all=[],conn=new Set(),auth={};
  try{const r=await api('GET','/provider');all=r.all||[];conn=new Set(r.connected||[]);try{auth=await api('GET','/provider/auth')}catch{}}catch(e){body.textContent=e.message;return}
  const name=p=>p.id==='opencode'?'OpenCode Zen':(p.name||p.id);
  const q=search('Поиск провайдеров',()=>draw()),list=h('div');
  const draw=()=>{list.textContent='';const f=q.value.toLowerCase();
    const sorted=[...all].sort((a,b)=>{const ca=conn.has(a.id)?0:1,cb=conn.has(b.id)?0:1;if(ca!==cb)return ca-cb;const pa=POP.indexOf(a.id),pb=POP.indexOf(b.id);return(pa<0?99:pa)-(pb<0?99:pb)||name(a).localeCompare(name(b))});
    sorted.filter(p=>!f||(name(p)+p.id).toLowerCase().includes(f)).forEach(p=>list.append(O.listRow('cloud',name(p),p.id==='opencode'?'Бесплатные модели без ключа — добавьте ключ Zen для платных':p.id,conn.has(p.id)?O.icon('check','ok'):null,()=>pick(p))))};
  body.textContent='';
  if(S.account&&S.account.email){
    body.append(O.listRow('user',S.account.email,'Аккаунт opencode подключён — квота Zen персональная',O.icon('check','ok'),()=>{s.close();O.openAccount()}));
    body.append(h('div',{class:'note',style:{margin:'10px 0'}},'Вы уже вошли. Модели OpenCode Zen работают под вашей личной квотой. Ниже можно подключить и других провайдеров своим ключом.'));
  }else{
    body.append(h('button',{class:'btn pri wide',onclick:()=>{s.close();O.openAccount()}},O.icon('user'),'Войти через Google / GitHub (аккаунт opencode)'),
      h('div',{class:'note',style:{margin:'10px 0'}},'Вход в аккаунт opencode делает квоту Zen персональной — как в opencode на компьютере. Либо подключите провайдера своим ключом ниже.'));
  const provBase={'openai':'https://api.openai.com/v1','anthropic':'https://api.anthropic.com','openrouter':'https://openrouter.ai/api/v1','deepseek':'https://api.deepseek.com/v1','groq':'https://api.groq.com/openai/v1','together':'https://api.together.xyz/v1','fireworks':'https://api.fireworks.ai/inference/v1','mistral':'https://api.mistral.ai/v1','xai':'https://api.x.ai/v1','cerebras':'https://api.cerebras.ai/v1','nvidia':'https://integrate.api.nvidia.com/v1','google':'https://generativelanguage.googleapis.com/v1beta/openai','ollama':'http://localhost:11434/v1','lmstudio':'http://localhost:1234/v1'};
  const probeModels=async(rawBase,key,pid)=>{
    let base=String(rawBase||'').trim().replace(/\/+$/,'');
    if(!base)base=provBase[pid]||'https://api.openai.com/v1';
    let url=base.endsWith('/models')?base:(base+(base.endsWith('/v1')?'/models':'/v1/models'));
    const headers={};
    if(pid==='anthropic'||base.includes('anthropic.com')){
      url=base.replace(/\/v1.*$/,'')+'/v1/models';
      if(key)headers['x-api-key']=key;headers['anthropic-version']='2023-06-01';
    }else if(key){headers['Authorization']='Bearer '+key}
    const res=await fetch(url,{headers});
    if(!res.ok)throw new Error('HTTP '+res.status+': '+(await res.text()).slice(0,200));
    const data=await res.json();
    const arr=data.data||data.models||(Array.isArray(data)?data:[]);
    const list=arr.map(m=>typeof m==='string'?m:(m.id||m.name||m.model)).filter(Boolean).sort();
    return list;
  };
  const keyForm=p=>{
    const inp=h('input',{type:'password',placeholder:'Вставьте API-ключ',autocapitalize:'off',autocorrect:'off'});
    const urlInp=h('input',{placeholder:provBase[p.id]||'https://api.../v1',value:provBase[p.id]||'',autocapitalize:'off',autocorrect:'off'});
    const showUrl=h('details',{style:{margin:'8px 0',fontSize:'12.5px'}},h('summary',{class:'mut',style:{cursor:'pointer'}},'Кастомный Base URL (прокси)'),h('div',{class:'field',style:{marginTop:'6px'}},urlInp));
    const modelsBox=h('div',{class:'hide',style:{marginTop:'10px',maxHeight:'220px',overflowY:'auto',border:'1px solid var(--line)',borderRadius:'12px',background:'var(--bg)'}});
    const fetchBtn=h('button',{class:'btn wide',style:{marginTop:'8px'},onclick:async()=>{
      const k=inp.value.trim();fetchBtn.disabled=true;fetchBtn.textContent='Опрашиваем API...';
      modelsBox.textContent='';modelsBox.classList.remove('hide');
      try{
        const ms=await probeModels(urlInp.value.trim()||provBase[p.id],k,p.id);
        if(!ms.length){modelsBox.append(h('div',{class:'mut',style:{padding:'12px'}},'Моделей не найдено'));return}
        modelsBox.append(h('div',{class:'grp',style:{margin:'8px 10px 4px'}},'Доступные модели ('+ms.length+') — нажмите для копирования'));
        ms.forEach(m=>modelsBox.append(h('div',{class:'li',style:{padding:'6px 10px',cursor:'pointer'},onclick:()=>{O.native.copy(m);O.toast('Скопировано: '+m)}},
          O.icon('cpu'),h('div',{class:'t'},h('b',{style:{fontSize:'13px',fontWeight:500}},m)),h('button',{class:'ibtn s',onclick:e=>{e.stopPropagation();O.native.copy(m)}},O.icon('copy')))));
      }catch(e){modelsBox.append(h('div',{class:'errbox',style:{margin:'8px'}},'Ошибка опроса: '+e.message))}
      finally{fetchBtn.disabled=false;fetchBtn.textContent='';fetchBtn.append(O.icon('search'),'Получить список моделей')}
    }},O.icon('search'),'Получить список моделей');
    const sh=O.sheet(name(p),h('div',null,h('div',{class:'field'},h('label',null,'API-ключ'),inp),showUrl,fetchBtn,modelsBox,
      p.id==='opencode'?h('div',{class:'note',style:{margin:'8px 0'}},'Получите ключ Zen на opencode.ai/zen. Бесплатные модели работают и без него.'):null,
      h('button',{class:'btn pri wide',style:{marginTop:'12px'},onclick:async()=>{if(!inp.value.trim())return;try{await api('PUT','/auth/'+p.id,{type:'api',key:inp.value.trim()});sh.close();after()}catch(e){O.toast(e.message)}}},'Сохранить')));
  };
  const openCustom=()=>{
    const nid=h('input',{placeholder:'например: my-llm, ollama, proxy',autocapitalize:'off',autocorrect:'off'});
    const nname=h('input',{placeholder:'например: Мой провайдер',autocapitalize:'off',autocorrect:'off'});
    const nurl=h('input',{placeholder:'http://localhost:11434/v1 или https://...',autocapitalize:'off',autocorrect:'off'});
    const nkey=h('input',{type:'password',placeholder:'API-ключ (если нужен)',autocapitalize:'off',autocorrect:'off'});
    const modelsBox=h('div',{class:'hide',style:{marginTop:'10px',maxHeight:'200px',overflowY:'auto',border:'1px solid var(--line)',borderRadius:'12px',background:'var(--bg)'}});
    let foundModels=[];
    const probeBtn=h('button',{class:'btn wide',style:{marginTop:'8px'},onclick:async()=>{
      const u=nurl.value.trim();if(!u){O.toast('Введите Base URL');return}
      probeBtn.disabled=true;probeBtn.textContent='Опрашиваем API...';
      modelsBox.textContent='';modelsBox.classList.remove('hide');
      try{
        foundModels=await probeModels(u,nkey.value.trim(),nid.value.trim());
        if(!foundModels.length){modelsBox.append(h('div',{class:'mut',style:{padding:'12px'}},'Модели не найдены'));return}
        modelsBox.append(h('div',{class:'grp',style:{margin:'8px 10px 4px'}},'Найдено моделей: '+foundModels.length));
        foundModels.forEach(m=>modelsBox.append(h('div',{class:'li',style:{padding:'6px 10px'}},O.icon('cpu'),h('div',{class:'t'},h('b',{style:{fontSize:'13px'}},m)),O.icon('check','ok'))));
      }catch(e){modelsBox.append(h('div',{class:'errbox',style:{margin:'8px'}},'Ошибка опроса: '+e.message))}
      finally{probeBtn.disabled=false;probeBtn.textContent='';probeBtn.append(O.icon('search'),'Опросить API и получить модели')}
    }},O.icon('search'),'Опросить API и получить модели');
    const sh=O.sheet('Кастомный провайдер',h('div',null,
      h('div',{class:'field'},h('label',null,'ID провайдера'),nid),
      h('div',{class:'field',style:{marginTop:'6px'}},h('label',null,'Название'),nname),
      h('div',{class:'field',style:{marginTop:'6px'}},h('label',null,'Base URL'),nurl),
      h('div',{class:'field',style:{marginTop:'6px'}},h('label',null,'API-ключ (опционально)'),nkey),
      probeBtn,modelsBox,
      h('button',{class:'btn pri wide',style:{marginTop:'12px'},onclick:async()=>{
        const id=(nid.value.trim()||'custom').toLowerCase().replace(/[^a-z0-9_-]/g,'');
        const url=nurl.value.trim();if(!url){O.toast('Укажите Base URL');return}
        const mObj={};foundModels.forEach(m=>{mObj[m]={name:m}});
        if(!foundModels.length)mObj['default']={name:'Default Model'};
        const cfgText=O.native.cfgRead()||'{}';let cfgObj={};try{cfgObj=JSON.parse(cfgText)}catch{}
        cfgObj.provider=cfgObj.provider||{};
        cfgObj.provider[id]={npm:'@ai-sdk/openai',name:nname.value.trim()||id,baseURL:url,models:mObj};
        const w=O.native.cfgWrite(JSON.stringify(cfgObj,null,2));
        if(!w.ok){O.toast(w.err||'Ошибка записи opencode.json');return}
        if(nkey.value.trim()){try{await api('PUT','/auth/'+id,{type:'api',key:nkey.value.trim()})}catch{}}
        sh.close();after();O.toast('Кастомный провайдер сохранён!');
      }},'Сохранить провайдер')));
  };
  const addBtn=h('button',{class:'btn wide',style:{marginBottom:'10px'},onclick:openCustom},O.icon('plus'),'Добавить кастомный провайдер (OpenAI/Ollama)');
  body.append(addBtn,q,list);draw();
  const after=async()=>{try{await api('POST','/instance/dispose',null)}catch{}await O.loadMeta();O.toast('Подключено');s.close()};
  const pick=p=>{const ms=(auth[p.id]||[]).map((m,i)=>({m,i}));if(!ms.length)ms.push({m:{type:'api',label:'API-ключ'},i:0});
    const go=x=>x.m.type==='oauth'?oauth(p,x):keyForm(p);
    if(ms.length===1&&!conn.has(p.id))return go(ms[0]);
    O.actions(name(p),[...ms.map(x=>({label:x.m.label||(x.m.type==='oauth'?'Войти':'API-ключ'),icon:x.m.type==='oauth'?'user':'key',fn:()=>go(x)})),
      conn.has(p.id)?{label:'Отключить',icon:'trash',danger:true,fn:()=>disconnect(p)}:null])};
  const oauth=async(p,x)=>{const view=h('div',null,h('div',{class:'mut'},'Подготовка...'));const sh=O.sheet(name(p),view);
    try{const r=await api('POST','/provider/'+p.id+'/oauth/authorize',{method:x.i});view.textContent='';
      view.append(h('div',null,r.instructions||'Откройте ссылку и подтвердите доступ.'),h('div',{style:{display:'flex',gap:'8px',margin:'12px 0'}},
        h('button',{class:'btn pri grow',onclick:()=>O.openUrl(r.url)},O.icon('ext'),'Открыть ссылку'),h('button',{class:'btn',onclick:()=>O.native.copy(r.url)},O.icon('copy'))));
      if(r.method==='code'){const inp=h('input',{placeholder:'Вставьте код'});view.append(inp,h('button',{class:'btn pri wide',style:{marginTop:'8px'},onclick:async()=>{try{await api('POST','/provider/'+p.id+'/oauth/callback',{method:x.i,code:inp.value.trim()},{timeout:120000});sh.close();after()}catch(e){O.toast(e.message)}}},'Отправить'))}
      else{view.append(h('div',{style:{display:'flex',gap:'8px',alignItems:'center'}},h('span',{class:'spin'}),h('span',{class:'mut'},'Ожидание подтверждения...')));
        api('POST','/provider/'+p.id+'/oauth/callback',{method:x.i},{timeout:900000}).then(()=>{if(sh.root.isConnected){sh.close();after()}}).catch(e=>{if(sh.root.isConnected)O.toast(e.message)})}
    }catch(e){view.textContent=e.message}};
  const disconnect=async p=>{const r=O.native.readText('/root/.local/share/opencode/auth.json');
    try{const o=JSON.parse(r.text||'{}');delete o[p.id];const w=O.native.writeText('/root/.local/share/opencode/auth.json',JSON.stringify(o,null,2));if(!w.ok)throw new Error(w.err||'ошибка записи');
      try{await api('POST','/instance/dispose',null)}catch{}await O.loadMeta();conn.delete(p.id);draw();O.toast('Отключено')}catch(e){O.toast(e.message)}}};

// ---- status: MCP / LSP / plugins ----
O.openStatus=async()=>{
  const body=h('div'),tabs=h('div',{class:'seg',style:{marginBottom:'10px'}});const s=O.sheet('Статус',h('div',null,tabs,body));
  let mcp={},lsp=[];try{mcp=await api('GET','/mcp')}catch{}try{lsp=await api('GET','/lsp')}catch{}
  const plugins=[].concat(S.cfg&&S.cfg.plugin||[]).map(x=>Array.isArray(x)?x[0]:x);
  const defs=[['MCP',()=>{body.textContent='';const ks=Object.keys(mcp);if(!ks.length)body.append(h('div',{class:'mut'},'Серверы MCP не настроены.'));
      ks.forEach(k=>{const m=mcp[k],st=m.status;const dot=h('span',{class:'dot '+(st==='connected'?'ok':st==='failed'?'err':st==='disabled'?'':'warn')});
        body.append(O.listRow(O.icon('server'),k,st+(m.error?': '+m.error:''),dot,async()=>{try{if(st==='connected')await api('POST','/mcp/'+encodeURIComponent(k)+'/disconnect',{});else if(st==='needs_auth'){const r=await api('POST','/mcp/'+encodeURIComponent(k)+'/auth',{});if(r.authorizationUrl)O.openUrl(r.authorizationUrl)}else await api('POST','/mcp/'+encodeURIComponent(k)+'/connect',{});mcp=await api('GET','/mcp');defs[0][1]()}catch(e){O.toast(e.message)}}))});
      body.append(h('button',{class:'btn wide',style:{marginTop:'10px'},onclick:()=>{s.close();O.openMcp()}},'Настроить серверы MCP'))}],
    ['LSP',()=>{body.textContent='';if(!lsp.length)body.append(h('div',{class:'mut'},'Языковые серверы не запущены. Они стартуют при открытии файлов.'));
      lsp.forEach(l=>body.append(O.listRow('code',l.name||l.id,l.root||'',h('span',{class:'dot '+(l.status==='connected'?'ok':'err')}))))}],
    ['Плагины ('+plugins.length+')',()=>{body.textContent='';if(!plugins.length)body.append(h('div',{class:'mut'},'Плагины не установлены.'));
      plugins.forEach(p=>body.append(O.listRow('plug',p,'',h('span',{class:'dot ok'}))));
      body.append(h('button',{class:'btn wide',style:{marginTop:'10px'},onclick:()=>{s.close();O.openPlugins()}},'Настроить плагины'))}]];
  defs.forEach((d,i)=>tabs.append(h('button',{class:i===0?'on':'',onclick:e=>{tabs.querySelectorAll('button').forEach(b=>b.classList.toggle('on',b===e.currentTarget));d[1]()}},d[0])));defs[0][1]()};

// ---- themes / help ----
O.openThemes=()=>{const th=[['opencode','OpenCode','#131010','#fab283'],['light','Светлая','#fafaf8','#c2641c'],['tokyonight','Tokyo Night','#1a1b26','#7aa2f7'],['gruvbox','Gruvbox','#282828','#fabd2f']];
  const s=O.sheet('Оформление',h('div',null,th.map(t=>O.listRow(h('span',{style:{width:'22px',height:'22px',borderRadius:'6px',background:t[2],border:'3px solid '+t[3],flex:'none'}}),t[1],'',S.opts.theme===t[0]?O.icon('check','ok'):null,()=>{S.opts.theme=t[0];O.saveOpts();s.close()})),
    h('div',{class:'grp'},'Размер текста'),h('div',{class:'seg'},[13,14,15,16,18].map(n=>h('button',{class:S.opts.fs===n?'on':'',onclick:()=>{S.opts.fs=n;O.saveOpts();s.close()}},n)))))};
O.openHelp=()=>O.sheet('Справка',h('div',null,h('div',{class:'grp'},'Команды'),O.builtins.map(b=>O.listRow(b.i,'/'+b.n,b.d,null,()=>{O.back();b.run()})),
  h('div',{class:'grp'},'Подсказки ввода'),h('div',{class:'note'},'@файл — упомянуть файл или папку.  !команда — выполнить команду оболочки в сессии.  Долгое нажатие на файл в дереве открывает действия.')));
})();
