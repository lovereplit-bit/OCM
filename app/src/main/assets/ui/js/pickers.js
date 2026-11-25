(function(){
const O=window.OCM,h=O.h,S=O.S,api=O.api,bus=O.bus;
const search=(ph,fn)=>h('input',{placeholder:ph,oninput:e=>fn(e.target.value.toLowerCase()),style:{marginBottom:'8px'}});

// ---- sessions drawer ----
O.openSessions=()=>{
  const list=h('div'),summary=h('div',{class:'sess-summary-bar'}),q=search(O.tr('Search sessions...','Поиск по сессиям...'),()=>draw());
  const navItem=(ic,label,fn)=>h('button',{class:'drawer-nav-item',onclick:()=>{s.close();fn();}},O.icon(ic),h('span',null,label));
  const nav=h('div',{style:{marginBottom:'12px',borderBottom:'1px solid var(--line)',paddingBottom:'8px'}},
    navItem('chat',O.tr('Chat','Чат'),()=>O.setTab('chat')),
    navItem('folder',O.tr('Files','Файлы'),()=>O.setTab('files')),
    navItem('terminal',O.tr('Terminal','Терминал'),()=>O.setTab('term')),
    navItem('branch',O.tr('Changes','Изменения'),()=>O.setTab('changes')),
    navItem('sliders',O.tr('Settings','Настройки'),()=>O.openSettings()));
  const newBtn=h('button',{class:'btn pri wide',onclick:async()=>{s.close();if(O.setTab)O.setTab('chat');try{await O.newSession()}catch(e){O.toast(e.message)}}},O.icon('plus'),O.tr('New session','Новая сессия'));
  const s=O.sheet(O.tr('Navigation & Sessions','Навигация и сессии'),h('div',null,nav,newBtn,h('div',{style:{height:'12px'}}),summary,q,list),{drawer:true});
  let filterMode='all';
  const busyCheck=id=>O.isSessionBusy?O.isSessionBusy(id):!!(S.status[id]&&S.status[id].type!=='idle');
  const unreadCheck=id=>O.hasUnreadMessages?O.hasUnreadMessages(id):Number((S.unread&&S.unread[id])||0);
  function draw(){
    list.textContent='';summary.textContent='';
    const all=S.sessions||[];
    const busyCount=all.filter(x=>busyCheck(x.id)).length;
    const unreadCount=all.filter(x=>unreadCheck(x.id)>0).length;
    if(busyCount||unreadCount){
      const chip=(id,lbl,cnt)=>h('button',{class:'sess-filter-chip'+(filterMode===id?' on':''),onclick:()=>{filterMode=filterMode===id?'all':id;draw()}},lbl+(cnt!=null?' · '+cnt:''));
      summary.style.display='flex';
      if(busyCount)summary.append(chip('busy',O.tr('Running','В работе'),busyCount));
      if(unreadCount)summary.append(chip('unread',O.tr('Unread','Новые ответы'),unreadCount));
    }else{
      filterMode='all';
      summary.style.display='none';
    }
    const f=q.value.toLowerCase();
    const items=all.filter(x=>{
      if(!O.sessionTitle(x).toLowerCase().includes(f))return false;
      if(filterMode==='busy')return busyCheck(x.id);
      if(filterMode==='unread')return unreadCheck(x.id)>0;
      return true;
    });
    if(!items.length)list.append(h('div',{class:'mut',style:{padding:'20px',textAlign:'center'}},O.tr('No matching sessions','Подходящих сессий нет')));
    items.forEach(x=>{
      const isCur=x.id===S.sid,isBusy=busyCheck(x.id),unread=unreadCheck(x.id),sm=x.summary;
      const cls='sess-row'+(isCur?' on':'')+(isBusy?' busy':'')+(unread>0?' unread':'');
      const lead=isBusy
        ?h('span',{class:'spin sess-spin',title:O.tr('Running','В работе')})
        :(unread>0
          ?h('span',{class:'sess-dot unread',title:O.tr('Unseen agent response','Есть непросмотренный ответ агента')})
          :(isCur
            ?h('span',{class:'sess-dot current',title:O.tr('Current session','Текущий чат')})
            :h('span',{class:'sess-ic'},O.icon('chat'))));
      const badges=[];
      if(isCur)badges.push(h('span',{class:'sess-pill cur'},O.tr('Active','Текущий')));
      if(isBusy)badges.push(h('span',{class:'sess-pill busy'},O.tr('Working','Работает')));
      if(unread>0)badges.push(h('span',{class:'sess-pill unread'},unread>1?O.tr('New · '+unread,'Новое · '+unread):O.tr('New reply','Новый ответ')));
      const diffTxt=sm&&(sm.additions||sm.deletions)?('  +'+sm.additions+' -'+sm.deletions):'';
      const subTxt=isBusy
        ?O.tr('Agent is working...','Агент выполняет задачу...')
        :(unread>0
          ?O.tr('Unseen agent response · ','Непросмотренный ответ · ')+O.ago(x.time.updated)
          :(O.ago(x.time.updated)+diffTxt));
      list.append(h('div',{class:cls},
        h('button',{class:'sess-main',onclick:()=>{s.close();if(O.setTab)O.setTab('chat');O.openSession(x.id)}},
          lead,
          h('div',{class:'sess-info'},
            h('div',{class:'sess-title-row'},h('b',null,O.sessionTitle(x))),
            badges.length?h('div',{class:'sess-badges'},badges):null,
            h('span',{class:'sess-sub'},subTxt))),
        h('button',{class:'ibtn s',style:{flex:'none'},onclick:e=>{e.stopPropagation();sessActions(x)}},O.icon('dots'))));
    });
  }
  draw();
  if(!S.offline&&O.api){
    O.api('GET','/session/status').then(st=>{if(st&&typeof st==='object'){S.status={};for(const k in st)S.status[k]=st[k];if(s.root.isConnected)draw()}}).catch(()=>{});
  }
  const redraw=()=>{if(s.root.isConnected)draw()};
  bus.on('sessions',redraw);bus.on('status',redraw);bus.on('open',redraw);
};
function sessActions(x){O.actions(O.sessionTitle(x),[
  {label:O.tr('Rename','Переименовать'),icon:'edit',fn:async()=>{const v=await O.ask(O.tr('Rename session','Переименование сессии'),null,O.sessionTitle(x));if(v)try{await api('PATCH','/session/'+x.id,{title:v})}catch(e){O.toast(e.message)}}},
  {label:x.share?O.tr('Stop sharing','Отключить шаринг'):O.tr('Share','Поделиться'),icon:'share',fn:async()=>{try{if(x.share){await api('DELETE','/session/'+x.id+'/share')}else{const r=await api('POST','/session/'+x.id+'/share',{});if(r.share){O.native.copy(r.share.url);O.native.share(r.share.url)}}}catch(e){O.toast(e.message)}}},
  {label:O.tr('Fork','Форк'),icon:'branch',fn:async()=>{try{const r=await api('POST','/session/'+x.id+'/fork',{});await O.loadSessions();O.openSession(r.id)}catch(e){O.toast(e.message)}}},
  {label:O.tr('Delete','Удалить'),icon:'trash',danger:true,fn:async()=>{if(await O.confirm(O.tr('Delete session?','Удалить сессию?'),O.sessionTitle(x),O.tr('Delete','Удалить'),true))try{await api('DELETE','/session/'+x.id);S.sessions=S.sessions.filter(y=>y.id!==x.id);if(S.sid===x.id)O.openSession(S.sessions[0]&&S.sessions[0].id||null);bus.emit('sessions')}catch(e){O.toast(e.message)}}}])}

// ---- projects + folder browser ----
O.openProjects=()=>{
  const recent=O.store.get('recent',[]),seen=new Set(),rows=[];
  const add=(p,sub)=>{if(!p||seen.has(p))return;seen.add(p);rows.push(O.listRow(O.fileIcon(O.basename(p)||'/',true),O.basename(p)||'/',p,p===S.dir?O.icon('check','ok'):null,()=>{s.close();O.setDir(p)}))};
  add(S.dir);recent.forEach(p=>add(p));(S.projects||[]).forEach(x=>add(x.worktree));add('/root');
  const s=O.sheet(O.tr('Projects','Проекты'),h('div',null,h('button',{class:'btn pri wide',onclick:()=>{s.close();O.browseDir(S.dir,p=>O.setDir(p))}},O.icon('folder'),O.tr('Open folder','Открыть папку')),h('div',{style:{height:'10px'}}),h('div',{class:'group-card'},rows)),{hud:true})};
O.browseDir=(start,cb)=>{let path=start||'/root';
  const crumbs=h('div',{class:'mono s mut',style:{wordBreak:'break-all',margin:'6px 2px'}}),list=h('div');
  const short=(l,p)=>h('button',{class:'chip',onclick:()=>{path=p;load()}},O.icon('folder'),h('span',null,l));
  const s=O.sheet(O.tr('Select folder','Выбор папки'),h('div',null,h('div',{style:{display:'flex',gap:'6px',overflowX:'auto'}},short('Home','/root'),short('SDCard','/sdcard'),short('Downloads','/sdcard/Download'),short('/','/')),crumbs,list),{full:true,
    actions:h('button',{class:'btn pri sm',onclick:()=>{s.close();cb(path)}},O.tr('Select','Выбрать'))});
  function load(){const r=O.native.ls(path);crumbs.textContent=path;list.textContent='';
    if(path!=='/')list.append(O.listRow('chevL','..',null,null,()=>{path=O.dirname(path);load()}));
    if(r.err||r.ok===false){list.append(h('div',{class:'err',style:{padding:'12px'}},r.err||'Не удаётся прочитать папку'));return}
    (r.items||[]).filter(x=>x.dir).forEach(x=>list.append(O.listRow(O.fileIcon(x.name,true),x.name,null,null,()=>{path=(path==='/'?'':path)+'/'+x.name;load()})));
    list.append(h('button',{class:'btn wide',style:{marginTop:'10px'},onclick:async()=>{const n=await O.ask(O.tr('New folder','Новая папка'),O.tr('Name','Название'));if(!n)return;const rr=O.native.fs('mkdir',(path==='/'?'':path)+'/'+n);rr.ok?load():O.toast(rr.err||'Не удалось')}},O.icon('plus'),O.tr('New folder','Новая папка')))}
  load()};

// ---- models / agents (Screenshot 2 exact match) ----
O.openModels=()=>{
  let onlyFree=O.store.get('onlyFreeModels',true);
  let filterText='';
  const listEl=h('div');
  const q=search(O.tr('Search models...','Поиск моделей...'),v=>{filterText=v;draw()});

  const swRow=O.toggleRow('shield',
    O.tr('Only free models','Только бесплатные модели'),
    O.tr('Hide paid models requiring API balance or keys','Показывать только модели с бесплатным доступом'),
    onlyFree,
    v=>{onlyFree=v;O.store.set('onlyFreeModels',v);draw()}
  );

  let curEffort=O.store.get('effort','Default');
  const effortTxt=h('span',null,curEffort+' ›');
  const effortCard=h('button',{class:'detached-card',onclick:()=>{
    const levels=['Default','Low','Medium','High'];
    const idx=(levels.indexOf(curEffort)+1)%levels.length;
    curEffort=levels[idx];
    O.store.set('effort',curEffort);
    effortTxt.textContent=curEffort+' ›';
    O.toast(O.tr('Reasoning effort: ','Усилие рассуждений: ')+curEffort,1500);
  }},
    h('div',{class:'detached-card-left'},
      h('span',null,O.tr('Effort','Усилие')),
      h('span',{class:'recommended-badge'},O.tr('Recommended','Рекомендуется'))),
    h('div',{class:'detached-card-right'},effortTxt));

  const localModelsCard=h('button',{class:'detached-card',onclick:()=>{s.close();O.openLocalModels()}},
    h('div',{class:'detached-card-left'},
      O.icon('download'),
      h('span',{style:{marginLeft:'6px'}},O.tr('Download Local Models (Hugging Face)','Локальные ИИ модели (Hugging Face)'))),
    h('div',{class:'detached-card-right'},'›'));

  const connectCard=h('button',{class:'detached-card',onclick:()=>{s.close();O.openConnect()}},
    h('div',{class:'detached-card-left'},
      O.icon('key'),
      h('span',{style:{marginLeft:'6px'}},O.tr('Connect more providers','Подключить провайдеров (API ключи)'))),
    h('div',{class:'detached-card-right'},'›'));

  const container=h('div',null,q,listEl,h('div',{style:{height:'10px'}}),swRow,effortCard,localModelsCard,connectCard);
  const s=O.sheet(O.tr('Select model','Выбор модели'),container,{hud:true,full:true});

  function draw(){
    listEl.textContent='';
    const all=O.allModels();
    if(!all.length){
      listEl.append(h('div',{class:'mut',style:{padding:'24px 16px',textAlign:'center'}},
        h('p',null,O.tr('No models available. Check runtime or connect a provider.','Моделей пока нет. Дождитесь запуска сервера или подключите провайдера.')),
        h('button',{class:'btn pri sm',style:{marginTop:'8px'},onclick:()=>{s.close();O.openConnect()}},O.icon('key'),O.tr('Connect provider','Подключить'))
      ));
      return;
    }

    let items=all;
    if(onlyFree){
      items=items.filter(x=>O.isModelFree(x)||x.isLocal||x.isCustom);
    }
    if(filterText){
      const f=filterText.toLowerCase();
      items=items.filter(x=>(x.name+' '+x.mid+' '+(x.pname||x.pid)).toLowerCase().includes(f));
    }

    if(!items.length){
      listEl.append(h('div',{class:'mut',style:{padding:'20px 16px',textAlign:'center'}},
        onlyFree
          ?O.tr('No free models found matching filter. Try disabling "Only free models" below.','Бесплатных моделей по фильтру не найдено. Попробуйте отключить пункт «Только бесплатные модели» ниже.')
          :O.tr('No models match filter.','Модели не найдены.')));
      return;
    }

    const activeKey=S.model?(S.model.providerID+'/'+S.model.modelID):'';
    items.sort((a,b)=>{
      const aOn=(a.pid+'/'+a.mid)===activeKey;
      const bOn=(b.pid+'/'+b.mid)===activeKey;
      if(aOn!==bOn)return aOn?-1:1;
      const aLoc=a.isLocal||a.pid==='local';
      const bLoc=b.isLocal||b.pid==='local';
      if(aLoc!==bLoc)return aLoc?-1:1;
      if(Boolean(a.isCustom)!==Boolean(b.isCustom))return a.isCustom?-1:1;
      return (a.name||a.mid).localeCompare(b.name||b.mid);
    });

    const groupCard=h('div',{class:'group-card'});
    items.forEach(x=>{
      const isSel=activeKey===(x.pid+'/'+x.mid);
      const isFree=O.isModelFree(x);
      const isLoc=Boolean(x.isLocal || x.pid==='local' || (x.name&&x.name.includes('(Локально)')));
      const isCust=Boolean(x.isCustom && !isLoc);
      const badgeCls=isLoc?'badge-local':(isCust?'badge-custom':(isFree?'badge-free':'badge-paid'));
      const badgeTxt=isLoc?O.tr('Local','Локально'):(isCust?'API':(isFree?O.tr('Free','Бесплатно'):O.tr('Paid','Платная')));
      const badge=h('span',{class:'badge-model '+badgeCls},badgeTxt);

      const ctx=x.m&&x.m.limit&&x.m.limit.context,parts=[];
      parts.push(isLoc?'Локальный мост (GGUF)':(x.pname||x.pid));
      if(ctx)parts.push(O.fmtK(ctx)+' context');
      if(x.m&&(x.m.modalities&&x.m.modalities.includes('image')||x.m.attachment!==false))parts.push('images / t2i');
      if(x.m&&(x.m.reasoning||x.m.variants))parts.push('reasoning');
      if(isLoc)parts.push('офлайн');
      else if(!isFree&&!isCust&&x.m&&x.m.cost&&typeof x.m.cost.input==='number'&&x.m.cost.input>0){
        parts.push('$'+x.m.cost.input+'/1M');
      }
      const sub=parts.join(' · ');

      const row=h('button',{class:'model-row'+(isSel?' on':'')+(isLoc?' model-row-local':'')+(isCust?' model-row-custom':''),onclick:()=>{
        O.setModel(x.pid,x.mid);
        if(isLoc&&O.native&&O.native.preloadLocalModel){
          try{O.native.preloadLocalModel(x.mid)}catch(_){}
        }
        s.close();
        O.toast(O.tr('Selected: ','Выбрана: ')+(x.name||x.mid),1500);
      }},
        h('div',{class:'model-row-left'},
          h('div',{style:{display:'flex',alignItems:'center',gap:'6px',flexWrap:'wrap'}},
            h('span',{class:'model-row-title'},x.name||x.mid),
            badge
          ),
          h('span',{class:'model-row-sub'},sub)
        ),
        isSel?O.icon('check','model-check'):null
      );
      groupCard.append(row);
    });
    listEl.append(groupCard);
  }
  draw();
};

O.openAllModelsCatalog=()=>O.openModels();

O.openAgents=()=>{const ags=O.primaryAgents();
  const card=h('div',{class:'group-card'},ags.map(a=>h('button',{class:'model-row',onclick:()=>{O.setAgent(a.name);s.close()}},
    h('div',{class:'model-row-left'},
      h('span',{class:'model-row-title'},a.name.charAt(0).toUpperCase()+a.name.slice(1)),
      h('span',{class:'model-row-sub'},a.description||'')),
    a.name===S.agent?O.icon('check','model-check'):null)));
  const s=O.sheet(O.tr('Agents & Modes','Режимы и агенты'),h('div',null,card,
    h('div',{class:'model-disclaimer'},O.tr('Add custom agents and rules in Settings → Agents & Rules.','Своих агентов и правила можно настроить в Настройки → Агенты и правила.'))),{hud:true})};

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
  }
  body.append(h('button',{class:'btn wide',style:{margin:'8px 0'},onclick:()=>O.openCustomProvider(s)},O.icon('cpu'),'Свой провайдер / Прозвон моделей'));
  body.append(q,list);draw();
  const after=async()=>{try{await api('POST','/instance/dispose',null)}catch{}await O.loadMeta();O.toast('Подключено');s.close()};
  const pick=p=>{const ms=(auth[p.id]||[]).map((m,i)=>({m,i}));if(!ms.length)ms.push({m:{type:'api',label:'API-ключ'},i:0});
    const go=x=>x.m.type==='oauth'?oauth(p,x):keyForm(p);
    if(ms.length===1&&!conn.has(p.id))return go(ms[0]);
    O.actions(name(p),[...ms.map(x=>({label:x.m.label||(x.m.type==='oauth'?'Войти':'API-ключ'),icon:x.m.type==='oauth'?'user':'key',fn:()=>go(x)})),
      conn.has(p.id)?{label:'Отключить',icon:'trash',danger:true,fn:()=>disconnect(p)}:null])};
  const keyForm=p=>{const inp=h('input',{type:'password',placeholder:'Вставьте API-ключ',autocapitalize:'off',autocorrect:'off'});
    const sh=O.sheet(name(p),h('div',null,h('div',{class:'field'},h('label',null,'API-ключ'),inp),
      p.id==='opencode'?h('div',{class:'note',style:{margin:'8px 0'}},'Получите ключ Zen на opencode.ai/zen. Бесплатные модели работают и без него.'):null,
      h('button',{class:'btn pri wide',onclick:async()=>{
        const key=inp.value.trim();if(!key)return;
        try{
          await api('PUT','/auth/'+p.id,{type:'api',key});
          // Clean up any historical broken custom override for built-in providers
          try{
            const t=O.native.cfgRead();let obj={};try{if(t.trim())obj=O.parseJsonc(t)}catch{}
            if(obj.provider&&obj.provider[p.id]){
              const cur=obj.provider[p.id];
              if(cur.npm==='@ai-sdk/openai'&&(!cur.models||!Object.keys(cur.models).length)){
                delete obj.provider[p.id];
                O.native.cfgWrite(JSON.stringify(obj,null,2));
              }
            }
          }catch(_){}
          sh.close();after();
        }catch(e){O.toast(e.message)}
      }},'Сохранить')))};
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

// ---- custom provider & model probing ----
const normalizeBaseUrl=(raw,proto)=>{
  let u=String(raw||'').trim();
  if(!u)return '';
  if(!/^https?:\/\//i.test(u)){
    const isLocalHost=/^(127\.0\.0\.1|localhost|0\.0\.0\.0|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/i.test(u);
    u=(isLocalHost?'http://':'https://')+u;
  }
  u=u.replace(/\/+$/,'');
  u=u.replace(/\/(chat\/completions|completions|responses|messages|models)$/i,'');
  if(proto==='@ai-sdk/anthropic'&&u.endsWith('/v1')===false&&/anthropic\.com$/i.test(u))u+='/v1';
  return u.replace(/\/+$/,'');
};

O.openCustomProvider=(parentSheet,editPid)=>{
  const readCfg=()=>{const t=O.native.cfgRead();let obj={};try{if(t&&t.trim())obj=O.parseJsonc(t)}catch{}obj.provider=obj.provider||{};return obj};
  const cfgObj=readCfg();
  const existingDef=editPid&&cfgObj.provider[editPid]?cfgObj.provider[editPid]:null;

  const existingWrap=h('div');
  const idInp=h('input',{placeholder:'id (латиница), напр. deepseek, ollama, myproxy',autocapitalize:'off',autocorrect:'off'});
  const nameInp=h('input',{placeholder:'Отображаемое имя, напр. My Custom API'});
  const urlInp=h('input',{class:'mono',placeholder:'https://api.example.com/v1 или http://127.0.0.1:11434/v1',autocapitalize:'off',autocorrect:'off'});
  const keyInp=h('input',{type:'password',class:'mono',placeholder:'API-ключ (можно оставить пустым для Ollama/LM Studio)',autocapitalize:'off',autocorrect:'off'});
  const manualInp=h('textarea',{class:'mono',rows:2,placeholder:'ID моделей через запятую или с новой строки (напр. gpt-4o, deepseek-chat)'});
  const statusBox=h('div',{class:'mut s',style:{margin:'8px 0',lineHeight:'1.4'}});
  const modelList=h('div',{class:'hide',style:{maxHeight:'220px',overflowY:'auto',margin:'8px 0',border:'1px solid var(--line)',borderRadius:'12px',padding:'6px',background:'var(--card)'}});
  let foundModels=[];const sel=new Set();
  let curProto=(existingDef&&existingDef.npm)||'@ai-sdk/openai-compatible';
  let markFree=true;

  if(existingDef&&editPid){
    idInp.value=editPid;
    nameInp.value=existingDef.name||editPid;
    urlInp.value=(existingDef.options&&existingDef.options.baseURL)||'';
    keyInp.value=(existingDef.options&&existingDef.options.apiKey&&existingDef.options.apiKey!=='sk-local')?existingDef.options.apiKey:'';
    const exModels=Object.keys(existingDef.models||{});
    manualInp.value=exModels.join(', ');
    exModels.forEach(m=>sel.add(m));
  }

  const drawExisting=()=>{
    existingWrap.textContent='';
    const cur=readCfg();
    const entries=Object.entries(cur.provider||{}).filter(([id])=>id!=='local'&&id!=='opencode');
    if(!entries.length)return;
    existingWrap.append(h('div',{class:'settings-section-title',style:{marginTop:'4px'}},O.tr('Configured Custom Providers','Подключённые кастомные провайдеры')));
    const card=h('div',{class:'group-card'});
    entries.forEach(([pid,def])=>{
      const mCount=Object.keys(def.models||{}).length;
      const base=(def.options&&def.options.baseURL)||'built-in';
      const npmLabel=def.npm||'@ai-sdk/openai-compatible';
      card.append(h('div',{class:'settings-row'},
        h('div',{class:'settings-row-icon'},O.icon('server')),
        h('div',{class:'settings-row-text'},
          h('b',null,(def.name||pid)+' ('+pid+')'),
          h('span',{class:'mono'},base+' · '+mCount+' mod · '+npmLabel.replace('@ai-sdk/',''))),
        h('button',{class:'ibtn s',title:O.tr('Edit','Изменить'),onclick:()=>{
          idInp.value=pid;nameInp.value=def.name||pid;
          urlInp.value=(def.options&&def.options.baseURL)||'';
          keyInp.value=(def.options&&def.options.apiKey&&def.options.apiKey!=='sk-local'&&def.options.apiKey!=='ollama')?def.options.apiKey:'';
          curProto=def.npm||'@ai-sdk/openai-compatible';
          updateProtoSeg();
          const ms=Object.keys(def.models||{});
          manualInp.value=ms.join(', ');
          sel.clear();ms.forEach(m=>sel.add(m));
          O.toast('Загружено в форму: '+pid,1200);
        }},O.icon('edit')),
        h('button',{class:'ibtn s',title:O.tr('Delete','Удалить'),onclick:async()=>{
          if(!await O.confirm(O.tr('Remove provider?','Удалить провайдера?'),pid,'Удалить',true))return;
          const cc=readCfg();delete cc.provider[pid];
          O.native.cfgWrite(JSON.stringify(cc,null,2));
          try{await api('POST','/instance/dispose',null)}catch(_){}
          await O.loadMeta();drawExisting();
          O.toast('Удалено: '+pid,1500);
        }},O.icon('trash'))
      ));
    });
    existingWrap.append(card);
  };
  drawExisting();

  const PRESETS=[
    {id:'openrouter',name:'OpenRouter',url:'https://openrouter.ai/api/v1',proto:'@ai-sdk/openai-compatible',icon:'globe',models:''},
    {id:'groq',name:'Groq',url:'https://api.groq.com/openai/v1',proto:'@ai-sdk/openai-compatible',icon:'zap',models:''},
    {id:'deepseek',name:'DeepSeek',url:'https://api.deepseek.com/v1',proto:'@ai-sdk/openai-compatible',icon:'cpu',models:'deepseek-chat, deepseek-reasoner'},
    {id:'ollama',name:'Ollama (Local)',url:'http://127.0.0.1:11434/v1',proto:'@ai-sdk/openai-compatible',icon:'server',models:''},
    {id:'lmstudio',name:'LM Studio',url:'http://127.0.0.1:1234/v1',proto:'@ai-sdk/openai-compatible',icon:'monitor',models:''},
    {id:'google-openai',name:'Gemini API',url:'https://generativelanguage.googleapis.com/v1beta/openai',proto:'@ai-sdk/openai-compatible',icon:'cube',models:'gemini-2.5-flash, gemini-2.5-pro'},
    {id:'anthropic-custom',name:'Anthropic Relay',url:'https://api.anthropic.com/v1',proto:'@ai-sdk/anthropic',icon:'shield',models:'claude-sonnet-4-20250514'},
    {id:'together',name:'Together AI',url:'https://api.together.xyz/v1',proto:'@ai-sdk/openai-compatible',icon:'code',models:''},
    {id:'nvidia',name:'NVIDIA NIM',url:'https://integrate.api.nvidia.com/v1',proto:'@ai-sdk/openai-compatible',icon:'cpu',models:''}
  ];
  const presets=h('div',{style:{display:'flex',gap:'6px',flexWrap:'wrap',marginBottom:'12px'}},
    PRESETS.map(pr=>h('button',{class:'btn sm',type:'button',onclick:()=>{
      idInp.value=pr.id;nameInp.value=pr.name;urlInp.value=pr.url;curProto=pr.proto;
      if(pr.models&&!manualInp.value.trim())manualInp.value=pr.models;
      updateProtoSeg();
      statusBox.textContent='Пресет выбран: '+pr.name+'. Вставьте ключ и нажмите «Прозвонить API» или «Сохранить».';
    }},O.icon(pr.icon),pr.name)));

  const PROTOS=[
    ['@ai-sdk/openai-compatible','OpenAI-совм.'],
    ['@ai-sdk/anthropic','Anthropic'],
    ['@ai-sdk/google','Gemini'],
    ['@ai-sdk/openai','OpenAI Resp.']
  ];
  const protoSeg=h('div',{class:'seg'});
  const updateProtoSeg=()=>{protoSeg.querySelectorAll('button').forEach((b,i)=>b.classList.toggle('on',PROTOS[i][0]===curProto))};
  PROTOS.forEach(([val,lbl])=>{
    protoSeg.append(h('button',{type:'button',class:val===curProto?'on':'',onclick:()=>{curProto=val;updateProtoSeg()}},lbl));
  });

  const buildHeaders=(url,key)=>{
    const hd={'Accept':'application/json','Content-Type':'application/json'};
    if(key){
      if(curProto==='@ai-sdk/anthropic'){
        hd['x-api-key']=key;
        hd['anthropic-version']='2023-06-01';
        hd['Authorization']='Bearer '+key;
      }else{
        hd['Authorization']='Bearer '+key;
      }
    }
    if(url.includes('openrouter.ai')||idInp.value.trim()==='openrouter'){
      hd['HTTP-Referer']='https://opencode.mobile';
      hd['X-Title']='OpenCode Mobile';
    }
    return hd;
  };

  const renderCheckboxes=()=>{
    modelList.textContent='';
    if(!foundModels.length){modelList.classList.add('hide');return}
    modelList.classList.remove('hide');
    foundModels.forEach(mid=>{
      const chk=h('input',{type:'checkbox',checked:sel.has(mid),style:{width:'auto',margin:'0 8px 0 0'}});
      chk.onchange=()=>{
        if(chk.checked)sel.add(mid);else sel.delete(mid);
        manualInp.value=[...sel].join(', ');
      };
      modelList.append(h('label',{style:{display:'flex',alignItems:'center',padding:'6px 4px',fontSize:'13px',cursor:'pointer'}},chk,h('span',{class:'mono grow'},mid)));
    });
  };

  const probeBtn=h('button',{class:'btn grow',type:'button',onclick:async()=>{
    let url=normalizeBaseUrl(urlInp.value,curProto);
    if(!url)return O.toast('Введите Base URL');
    urlInp.value=url;
    const key=keyInp.value.trim();
    const hd=buildHeaders(url,key);
    probeBtn.disabled=true;statusBox.textContent='Прозваниваю API (CORS-bypass через OkHttp)...';
    try{
      const candidates=[];
      if(url.endsWith('/models'))candidates.push({target:url,base:url.replace(/\/models$/,'')});
      else{
        candidates.push({target:url+'/models',base:url});
        if(!/\/v\d|\/openai|\/api\/paas/i.test(url)){
          candidates.push({target:url+'/v1/models',base:url+'/v1'});
        }
      }
      if(idInp.value.includes('ollama')||url.includes(':11434')){
        const origin=url.replace(/\/v1.*$/,'');
        candidates.push({target:origin+'/api/tags',base:origin+'/v1',ollama:true});
      }

      let gotList=[],usedBase=url,lastErr='';
      for(const c of candidates){
        try{
          const r=await O.extFetch(c.target,{method:'GET',headers:hd,timeout:12000});
          if(!r.ok){lastErr='HTTP '+r.status+(r.data&&r.data.error&&r.data.error.message?(' — '+r.data.error.message):'');continue}
          const json=r.data;
          const raw=Array.isArray(json)?json:((json&&json.data)||(json&&json.models)||[]);
          const parsed=raw.map(m=>typeof m==='string'?m:(m.id||m.model||m.name)).filter(Boolean);
          if(parsed.length){
            gotList=parsed;
            usedBase=c.base;
            break;
          }else{
            lastErr='Сервер ответил 200 OK, но список моделей пуст';
            usedBase=c.base;
          }
        }catch(ex){lastErr=ex.message}
      }
      if(usedBase!==url)urlInp.value=usedBase;
      foundModels=[...new Set(gotList)];
      if(!foundModels.length){
        statusBox.textContent=(lastErr?lastErr+'. ':'')+'Не удалось получить список автоматически — просто впишите ID модели вручную ниже (например gpt-4o или deepseek-chat).';
        modelList.classList.add('hide');
        return;
      }
      sel.clear();
      foundModels.slice(0,80).forEach(mid=>sel.add(mid));
      manualInp.value=[...sel].join(', ');
      renderCheckboxes();
      statusBox.textContent='Найдено '+foundModels.length+' моделей (выбрано '+sel.size+'). Endpoint: '+usedBase;
      O.toast('Найдено моделей: '+foundModels.length,2000);
    }catch(e){
      statusBox.textContent='Ошибка: '+e.message;
      O.toast('Ошибка прозвона: '+e.message,4000);
    }finally{probeBtn.disabled=false}
  }},O.icon('search'),O.tr('Probe Models','Прозвонить /models'));

  const testBtn=h('button',{class:'btn grow',type:'button',onclick:async()=>{
    let url=normalizeBaseUrl(urlInp.value,curProto);
    if(!url)return O.toast('Введите Base URL');
    urlInp.value=url;
    const key=keyInp.value.trim();
    const typed=manualInp.value.split(/[\n,]+/).map(s=>s.trim()).filter(Boolean);
    const modelId=(typed[0]||[...sel][0]||'').trim();
    if(!modelId)return O.toast('Укажите хотя бы один ID модели или нажмите Прозвонить');
    testBtn.disabled=true;statusBox.textContent='Тестируем ответ модели '+modelId+'...';
    try{
      const hd=buildHeaders(url,key);
      let ep=url+'/chat/completions',payload={model:modelId,messages:[{role:'user',content:'Reply OK'}],max_tokens:8,stream:false};
      if(curProto==='@ai-sdk/anthropic'){
        ep=url.endsWith('/v1')?(url+'/messages'):(url+'/v1/messages');
        payload={model:modelId,messages:[{role:'user',content:'Reply OK'}],max_tokens:16,stream:false};
      }else if(curProto==='@ai-sdk/openai'){
        ep=url+'/responses';
        payload={model:modelId,input:'Reply OK',max_output_tokens:16};
      }
      const r=await O.extFetch(ep,{method:'POST',headers:hd,body:payload,timeout:18000});
      if(r.ok){
        statusBox.textContent='Тест пройден (HTTP '+r.status+'). API и ключ работают исправно с моделью '+modelId+'.';
        O.toast('API отвечает корректно!',2000);
      }else{
        const errMsg=(r.data&&r.data.error&&r.data.error.message)||(typeof r.text==='string'?r.text.slice(0,180):'');
        statusBox.textContent='Ошибка HTTP '+r.status+': '+errMsg;
        O.toast('API вернул HTTP '+r.status,3500);
      }
    }catch(e){
      statusBox.textContent='Ошибка теста: '+e.message;
      O.toast('Ошибка соединения: '+e.message,3500);
    }finally{testBtn.disabled=false}
  }},O.icon('zap'),O.tr('Test Chat','Тест запроса'));

  const freeToggle=O.toggleRow('shield',
    O.tr('Show in "Free Models" view','Показывать с фильтром бесплатных'),
    O.tr('Prevent custom API models from being hidden by the free filter','Чтобы модели вашего API не скрывались фильтром в чате'),
    markFree,
    v=>{markFree=v}
  );

  const saveBtn=h('button',{class:'btn pri wide',style:{marginTop:'12px'},onclick:async()=>{
    const pid=idInp.value.trim().toLowerCase().replace(/[^a-z0-9_-]/g,'')||('custom-'+Date.now().toString(36));
    const pname=nameInp.value.trim()||pid;
    let url=normalizeBaseUrl(urlInp.value,curProto);
    if(!url)return O.toast('Введите Base URL');
    urlInp.value=url;
    const key=keyInp.value.trim();

    const typedList=manualInp.value.split(/[\n,]+/).map(s=>s.trim()).filter(Boolean);
    const finalMids=typedList.length?typedList:[...sel];
    if(!finalMids.length){
      return O.toast('Укажите хотя бы одну модель (прозвоните API или впишите ID модели вручную)');
    }

    const mo={};
    [...new Set(finalMids)].forEach(mid=>{
      const entry={
        name:mid,
        attachment:true,
        tool_call:true,
        temperature:true,
        limit:{context:128000,output:8192}
      };
      if(markFree){
        entry.free=true;
        entry.cost={input:0,output:0};
      }
      mo[mid]=entry;
    });

    const obj=readCfg();
    const provOpts={baseURL:url,apiKey:key||(pid.includes('ollama')?'ollama':'sk-local')};
    if(pid==='openrouter'||url.includes('openrouter.ai')){
      provOpts.headers={'HTTP-Referer':'https://opencode.mobile','X-Title':'OpenCode Mobile'};
    }
    obj.provider[pid]={name:pname,npm:curProto,options:provOpts,models:mo};

    const w=O.native.cfgWrite(JSON.stringify(obj,null,2));
    if(!w.ok)return O.toast(w.err||'Ошибка записи opencode.json');

    saveBtn.disabled=true;
    try{
      if(key)try{await api('PUT','/auth/'+pid,{type:'api',key})}catch(_){}
      try{await api('POST','/instance/dispose',null)}catch(_){}
      await new Promise(r=>setTimeout(r,260));
      await O.loadMeta();

      const firstMid=Object.keys(mo)[0];
      const loadedProv=S.providers.find(p=>p.id===pid&&p.models&&Object.keys(p.models).length>0);
      if(loadedProv&&firstMid){
        O.setModel(pid,firstMid);
        O.toast('Провайдер '+pname+' подключён ('+Object.keys(mo).length+' моделей)!',3000);
        sh.close();if(parentSheet&&parentSheet.close)parentSheet.close();
      }else{
        O.store.set('model.'+S.agent,{providerID:pid,modelID:firstMid});
        if(S.sid)O.store.set('session.model.'+S.sid,{providerID:pid,modelID:firstMid});
        if(window.OC&&OC.restartServer){
          OC.restartServer();
          O.toast('Сохранено! Перезапуск ядра для активации '+pname+'...',3500);
        }else{
          O.toast('Сохранено в opencode.json ('+pname+')',2500);
        }
        sh.close();if(parentSheet&&parentSheet.close)parentSheet.close();
      }
    }finally{saveBtn.disabled=false}
  }},O.icon('check'),O.tr('Save & Connect Provider','Сохранить и подключить'));

  const sh=O.sheet(O.tr('Custom API Provider','Свой API провайдер'),h('div',null,
    existingWrap,
    h('div',{class:'settings-section-title',style:{marginTop:'8px'}},O.tr('Quick Presets','Быстрые пресеты')),
    presets,
    h('div',{class:'field'},h('label',null,O.tr('API Protocol / SDK','Формат API / Протокол')),protoSeg),
    h('div',{class:'field'},h('label',null,O.tr('Provider ID','ID провайдера (латиница)')),idInp),
    h('div',{class:'field'},h('label',null,O.tr('Display Name','Отображаемое имя')),nameInp),
    h('div',{class:'field'},h('label',null,O.tr('Base URL','Base URL (без /chat/completions)')),urlInp),
    h('div',{class:'field'},h('label',null,O.tr('API Key','API-ключ (опционально для локальных)')),keyInp),
    h('div',{style:{display:'flex',gap:'8px',margin:'10px 0'}},probeBtn,testBtn),
    statusBox,
    modelList,
    h('div',{class:'field'},h('label',null,O.tr('Models (auto or comma-separated IDs)','Модели (через запятую, если /models недоступен)')),manualInp),
    h('div',{class:'group-card'},freeToggle),
    saveBtn),{hud:true,full:true});
};

// ---- themes / seasonal HUD / help ----
O.openThemes=()=>{
  const th=[
    ['opencode','OpenCode Dark','#0d0d0f','#38bdf8'],
    ['light','Светлая (Light)','#f8f8fa','#0284c7'],
    ['tokyonight','Tokyo Night','#1a1b26','#7aa2f7'],
    ['gruvbox','Gruvbox','#282828','#8ec07c']
  ];
  const accents=[
    ['auto','Авто','#38bdf8'],
    ['#38bdf8','Небесный','#38bdf8'],
    ['#22d3ee','Ледяной','#22d3ee'],
    ['#4ade80','Изумруд','#4ade80'],
    ['#60a5fa','Кобальт','#60a5fa'],
    ['#e11d48','Кармин','#e11d48'],
    ['#e4e4e7','Платина','#e4e4e7']
  ];

  const box=h('div');
  const draw=()=>{
    box.textContent='';
    const activeSeason=O.activeSeasonKey?O.activeSeasonKey(S.opts):'none';

    box.append(h('div',{class:'grp',style:{marginTop:'4px'}},O.tr('Base Theme','Тема интерфейса')));
    const themeCard=h('div',{class:'group-card'});
    th.forEach(t=>themeCard.append(O.listRow(
      h('span',{style:{width:'22px',height:'22px',borderRadius:'7px',background:t[2],border:'2.5px solid '+t[3],flex:'none'}}),
      t[1],
      t[0]==='opencode'?O.tr('Signature obsidian hub style','Фирменный стиль главного хаба'):'',
      S.opts.theme===t[0]?O.icon('check','ok'):null,
      ()=>{S.opts.theme=t[0];O.saveOpts();draw()}
    )));
    box.append(themeCard);

    box.append(h('div',{class:'grp'},O.tr('Accent Color','Акцентный цвет')));
    const accRow=h('div',{class:'accent-swatches'});
    accents.forEach(([val,lbl,hex])=>{
      const on=(S.opts.accent||'auto')===val;
      accRow.append(h('button',{
        type:'button',
        class:'accent-swatch'+(on?' on':''),
        title:lbl,
        onclick:()=>{S.opts.accent=val;O.saveOpts();draw()}
      },h('span',{class:'accent-dot',style:{background:hex}}),h('span',{class:'accent-lbl'},lbl)));
    });
    const customPicker=h('input',{
      type:'color',
      value:O.resolveAccentHex?O.resolveAccentHex():'#38bdf8',
      style:{width:'40px',height:'32px',padding:'2px',borderRadius:'8px',cursor:'pointer',flex:'none'},
      onchange:e=>{S.opts.accent=e.target.value;O.saveOpts();draw()}
    });
    const hexInp=h('input',{
      class:'mono',
      value:(S.opts.accent&&S.opts.accent!=='auto')?S.opts.accent:'',
      placeholder:'#38bdf8',
      style:{flex:1,minWidth:0,padding:'6px 10px',fontSize:'13px'},
      onchange:e=>{
        const v=e.target.value.trim();
        if(/^#[0-9a-f]{6}$/i.test(v)){S.opts.accent=v.toLowerCase();O.saveOpts();draw()}
        else if(!v){S.opts.accent='auto';O.saveOpts();draw()}
      }
    });
    box.append(accRow,h('div',{class:'card',style:{display:'flex',alignItems:'center',gap:'8px',marginTop:'8px',padding:'10px 12px'}},
      h('span',{class:'mut s'},O.tr('Custom Hex:','Свой цвет:')),customPicker,hexInp));

    box.append(h('div',{class:'grp'},O.tr('Seasonal HUD (Calendar & Custom)','Сезонный HUD и праздники')));
    const seasonCard=h('div',{class:'group-card'});
    const sList=O.SEASONAL_THEMES||[];
    sList.forEach(st=>{
      const isCur=(S.opts.seasonalMode||'auto')===st.id;
      const activeBadge=(st.id===activeSeason&&st.id!=='none'&&isCur)?' · '+O.tr('Active now','Активно сейчас'):'';
      seasonCard.append(O.listRow(
        st.icon||'calendar',
        st.name,
        st.desc+activeBadge,
        isCur?O.icon('check','ok'):null,
        ()=>{S.opts.seasonalMode=st.id;O.saveOpts();draw()}
      ));
    });
    seasonCard.append(O.toggleRow(
      'zap',
      O.tr('Seasonal Animations','Плавные анимации HUD'),
      O.tr('Floating snowflakes, petals, particles inside frame','Лёгкие фоновые частицы без нагрузки на интерфейс'),
      S.opts.seasonalAnim!==false,
      v=>{S.opts.seasonalAnim=v;O.saveOpts();draw()}
    ));
    box.append(seasonCard);

    box.append(h('div',{class:'grp'},O.tr('Text Size','Размер текста')));
    box.append(h('div',{class:'seg'},[13,14,15,16,18].map(n=>h('button',{
      class:S.opts.fs===n?'on':'',
      onclick:()=>{S.opts.fs=n;O.saveOpts();draw()}
    },n+'px'))));
  };
  draw();
  O.sheet(O.tr('Appearance & Seasonal HUD','Оформление и темы'),box,{hud:true,full:true});
};

O.openHelp=()=>O.sheet('Справка',h('div',null,h('div',{class:'grp'},'Команды'),O.builtins.map(b=>O.listRow(b.i,'/'+b.n,b.d,null,()=>{O.back();b.run()})),
  h('div',{class:'grp'},'Подсказки ввода'),h('div',{class:'note'},'@файл — упомянуть файл или папку.  !команда — выполнить команду оболочки в сессии.  Долгое нажатие на файл в дереве открывает действия.')));
})();
