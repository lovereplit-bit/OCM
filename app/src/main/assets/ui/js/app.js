(function(){
const O=window.OCM,h=O.h,S=O.S,api=O.api,bus=O.bus;
let built=false,tab='chat',sideTab='term',T={},P={},B={},SP={},SB={};
const wide=()=>innerWidth>=820;

O.tr=(en,ru)=>(O.store.get('lang','ru')==='en'?en:ru);

function build(){
  if(built)return;built=true;
  const app=O.$('#app');

  // Minimal Top Header matching Screenshot 1: Hamburger on left, Tokens badge right next to it, Ghost mascot on right
  T.menu=h('button',{class:'top-menu-btn',onclick:()=>O.openSessions()},O.icon('menu'));
  T.tokensVal=h('span',{class:'top-tokens-val'},'0 tok');
  T.tokens=h('button',{class:'top-tokens-btn',style:{display:'none'},title:O.tr('Session Tokens / Context','Токены сессии / Контекст'),onclick:()=>O.openTokensModal()},h('span',{class:'tok-bolt'},'⚡'),T.tokensVal);
  const topLeft=h('div',{class:'top-left-box'},T.menu,T.tokens);
  T.ghost=h('button',{class:'top-ghost-btn',onclick:()=>{if(O.spawnBats)O.spawnBats();O.openGhost()}},O.icon('ghost'));
  const top=h('div',{id:'top'},topLeft,T.ghost);

  T.banner=h('div',{id:'banner',class:'hide'});

  // Panes
  P.files=h('div',{id:'pane-files',class:'pane'});
  P.chat=h('div',{id:'pane-chat',class:'pane'});
  P.side=h('div',{id:'pane-side',class:'pane'});

  SP.term=h('div',{class:'side-pane'});
  SP.changes=h('div',{class:'side-pane'});
  SB.term=h('button',{onclick:()=>O.setTab('term')},O.tr('Terminal','Терминал'));
  SB.changes=h('button',{onclick:()=>O.setTab('changes')},O.tr('Changes','Изменения'));
  P.side.append(h('div',{id:'side-tabs'},SB.term,SB.changes),SP.term,SP.changes);

  const main=h('div',{id:'main'},P.files,P.chat,P.side);

  // Bottom Pill Bar matching Screenshot 1: Centered [Chat] and [Memory on]
  let memOn=O.store.get('memory',true);
  const memLabel=()=>memOn?(O.tr('Memory on','Память вкл')):(O.tr('Memory off','Память выкл'));
  B.chat=h('button',{class:'bottom-pill active',onclick:()=>O.setTab('chat')},O.icon('chat'),h('span',null,O.tr('Chat','Чат')));
  B.memTxt=h('span',null,memLabel());
  B.memory=h('button',{class:'bottom-pill'+(memOn?' active':''),onclick:()=>{
    memOn=!memOn;
    O.store.set('memory',memOn);
    B.memTxt.textContent=memLabel();
    B.memory.classList.toggle('active',memOn);
    O.toast(memOn?(O.tr('Memory active: persistent project rules enabled','Память активна: правила и контекст включены')):(O.tr('Memory disabled','Память отключена')),2000);
  }},O.icon('memory'),B.memTxt);

  const bottomBar=h('div',{id:'bottom-bar'},B.chat,B.memory);

  // Halloween corner cobwebs with animated crawling spiders
  const webTL=h('div',{class:'halloween-web web-tl',html:`<svg viewBox="0 0 70 70" width="70" height="70" fill="none"><path d="M0,0 L70,0 M0,0 L65,25 M0,0 L48,48 M0,0 L25,65 M0,0 L0,70" stroke="rgba(255,255,255,0.22)" stroke-width="1.2"/><path d="M22,0 Q18,10 0,22" stroke="rgba(255,255,255,0.18)" stroke-width="1" fill="none"/><path d="M42,0 Q32,22 0,42" stroke="rgba(255,255,255,0.18)" stroke-width="1" fill="none"/><path d="M64,0 Q50,38 0,64" stroke="rgba(255,255,255,0.18)" stroke-width="1" fill="none"/></svg><div class="spider-hang spider-left"><div class="spider-thread"></div><svg class="spider-svg" viewBox="0 0 20 20" width="18" height="18" fill="none"><path d="M10,10 L3,5 M10,10 L2,10 M10,10 L3,15 M10,10 L5,18 M10,10 L17,5 M10,10 L18,10 M10,10 L17,15 M10,10 L15,18" stroke="#1c1917" stroke-width="1.4" stroke-linecap="round"/><ellipse cx="10" cy="11" rx="4" ry="4.5" fill="#18181b"/><ellipse cx="10" cy="7" rx="3" ry="2.5" fill="#09090b"/><circle cx="8.8" cy="6.5" r="0.8" fill="#ef4444"/><circle cx="11.2" cy="6.5" r="0.8" fill="#ef4444"/></svg></div>`});
  const webTR=h('div',{class:'halloween-web web-tr',html:`<svg viewBox="0 0 70 70" width="70" height="70" fill="none"><path d="M70,0 L0,0 M70,0 L5,25 M70,0 L22,48 M70,0 L45,65 M70,0 L70,70" stroke="rgba(255,255,255,0.22)" stroke-width="1.2"/><path d="M48,0 Q52,10 70,22" stroke="rgba(255,255,255,0.18)" stroke-width="1" fill="none"/><path d="M28,0 Q38,22 70,42" stroke="rgba(255,255,255,0.18)" stroke-width="1" fill="none"/><path d="M6,0 Q20,38 70,64" stroke="rgba(255,255,255,0.18)" stroke-width="1" fill="none"/></svg><div class="spider-hang spider-right"><div class="spider-thread"></div><svg class="spider-svg" viewBox="0 0 20 20" width="18" height="18" fill="none"><path d="M10,10 L3,5 M10,10 L2,10 M10,10 L3,15 M10,10 L5,18 M10,10 L17,5 M10,10 L18,10 M10,10 L17,15 M10,10 L15,18" stroke="#1c1917" stroke-width="1.4" stroke-linecap="round"/><ellipse cx="10" cy="11" rx="4" ry="4.5" fill="#18181b"/><ellipse cx="10" cy="7" rx="3" ry="2.5" fill="#09090b"/><circle cx="8.8" cy="6.5" r="0.8" fill="#ef4444"/><circle cx="11.2" cy="6.5" r="0.8" fill="#ef4444"/></svg></div>`});

  app.append(webTL,webTR,top,T.banner,main,bottomBar);

  O.filesInit(P.files);
  O.chatInit(P.chat);
  O.termInit(SP.term);
  O.changesInit(SP.changes);

  addEventListener('resize',O.debounce(layout,100));
  ['open','session','sessions','msg','vcs','dir','meta','diff'].forEach(e=>bus.on(e,O.debounce(updateTop,120)));
  bus.on('conn',updateBanner);
  bus.on('statuspanel',refreshStatus);
  layout();
  updateTop();
}

function layout(){
  const app=O.$('#app');
  const w=wide();
  app.dataset.mode=w?'wide':'narrow';
  P.files.classList.toggle('show',!w&&tab==='files');
  P.chat.classList.toggle('show',!w&&tab==='chat');
  P.side.classList.toggle('show',!w&&(tab==='term'||tab==='changes'));
  SP.term.classList.toggle('show',sideTab==='term');
  SP.changes.classList.toggle('show',sideTab==='changes');
  SB.term.classList.toggle('on',sideTab==='term');
  SB.changes.classList.toggle('on',sideTab==='changes');
  if(B.chat)B.chat.classList.toggle('active',tab==='chat');
  if(O.termShown)O.termShown();
}

O.setTab=t=>{
  tab=t;
  if(t==='term'||t==='changes')sideTab=t;
  layout();
  if(t==='files')O.refreshFiles(false);
  if(t==='changes')O.refreshChanges();
};

O.calcSessionTokens=sid=>{
  const msgs=O.visibleMsgs(sid||S.sid);
  let input=0,output=0,reasoning=0,total=0;
  for(const m of msgs){
    const info=m.info;
    if(info&&info.tokens){
      input+=(info.tokens.input||0);
      output+=(info.tokens.output||0);
      reasoning+=(info.tokens.reasoning||0);
    }
  }
  total=input+output+reasoning;
  const usage=O.contextUsage(sid||S.sid);
  return{input,output,reasoning,total,pct:usage?usage.pct:null,lim:usage?usage.lim:null};
};

O.openTokensModal=()=>{
  const tok=O.calcSessionTokens(S.sid);
  const card=h('div',{class:'settings-card'},
    h('div',{class:'settings-row'},
      h('div',{class:'settings-row-icon amber'},O.icon('cpu')),
      h('div',{class:'settings-row-text'},
        h('b',null,O.tr('Total Tokens in Session','Всего токенов в сессии')),
        h('span',null,tok.total.toLocaleString()+' ('+O.fmtK(tok.total)+')'))),
    h('div',{class:'settings-row'},
      h('div',{class:'settings-row-icon blue'},O.icon('down')),
      h('div',{class:'settings-row-text'},
        h('b',null,O.tr('Prompt Tokens (Input)','Входящие токены (Промпт)')),
        h('span',null,tok.input.toLocaleString()))),
    h('div',{class:'settings-row'},
      h('div',{class:'settings-row-icon green'},O.icon('up')),
      h('div',{class:'settings-row-text'},
        h('b',null,O.tr('Completion Tokens (Output)','Исходящие токены (Генерация)')),
        h('span',null,tok.output.toLocaleString()))),
    tok.reasoning?h('div',{class:'settings-row'},
      h('div',{class:'settings-row-icon purple'},O.icon('bulb')),
      h('div',{class:'settings-row-text'},
        h('b',null,O.tr('Reasoning Tokens','Токены размышлений')),
        h('span',null,tok.reasoning.toLocaleString()))):null,
    tok.pct!=null?h('div',{class:'settings-row'},
      h('div',{class:'settings-row-icon purple'},O.icon('memory')),
      h('div',{class:'settings-row-text'},
        h('b',null,O.tr('Context Window Utilization','Заполненность контекста')),
        h('span',null,tok.pct+'%'+(tok.lim?' / '+O.fmtK(tok.lim)+' tok':'')))):null
  );
  O.sheet(O.tr('Token Statistics','Статистика токенов'),card);
};

function updateTop(){
  if(!built)return;
  if(T.tokensVal){
    const msgs=S.sid?O.visibleMsgs(S.sid):[];
    const hasStartedChat=!!(S.sid&&msgs.length>0);
    if(T.tokens)T.tokens.style.display=hasStartedChat?'inline-flex':'none';
    const tok=O.calcSessionTokens(S.sid);
    const txt=tok.total>0?(O.fmtK(tok.total)+' tok'):'0 tok';
    const pct=tok.pct!=null?(' · '+tok.pct+'%'):'';
    T.tokensVal.textContent=txt+pct;
  }
  // Ghost icon status dot/indicator if MCP needs attention
  const v=Object.values(S.mcp||{});
  const hasErr=v.some(x=>x.status==='failed'||x.status==='needs_auth');
  if(T.ghost)T.ghost.classList.toggle('active',hasErr);
}

function updateBanner(){
  if(!built)return;
  const t=S.offline?(O.tr('Offline — showing cached chats','Офлайн — показаны кэшированные чаты')):(!S.up?(O.tr('Connecting to local runtime...','Подключение к локальному серверу...')):'');
  T.banner.textContent=t;
  T.banner.classList.toggle('hide',!t);
}

async function refreshStatus(){
  try{
    const m=await api('GET','/mcp');
    S.mcp=m;
    updateTop();
  }catch{}
}

// Ghost mascot sheet with Core & MCP status
O.openGhost=()=>{
  const v=Object.values(S.mcp||{});
  const connected=v.filter(x=>x.status==='connected').length;
  const s=O.sheet(O.tr('OpenCode Assistant','Ассистент OpenCode'),h('div',null,
    h('div',{class:'settings-card'},
      h('div',{class:'settings-row'},
        h('div',{class:'settings-row-icon purple'},O.icon('ghost')),
        h('div',{class:'settings-row-text'},
          h('b',null,'OpenCode Mobile v2.0.1'),
          h('span',null,S.up?(O.tr('Core Active · Linux ARM64 · 120Hz ⚡','Ядро активно · Linux ARM64 · 120Hz ⚡')):(O.tr('Connecting...','Подключение...'))))),
      h('div',{class:'settings-row',onclick:()=>{s.close();O.openWidgetsModal()}},
        h('div',{class:'settings-row-icon amber'},O.icon('sliders')),
        h('div',{class:'settings-row-text'},
          h('b',null,O.tr('HUD Widgets & Soundboard','Интерактивные виджеты и звуки')),
          h('span',null,O.tr('3 detailed widgets + custom actions','3 детализированных виджета + действия'))),
        O.icon('chevR')),
      h('div',{class:'settings-row',onclick:()=>{s.close();O.openPreview()}},
        h('div',{class:'settings-row-icon blue'},O.icon('globe')),
        h('div',{class:'settings-row-text'},
          h('b',null,O.tr('In-App Web Preview','Веб-Предпросмотр сайтов')),
          h('span',null,O.tr('Preview HTML, CSS, JS, images inside app','Просмотр сайтов и проектов в приложении'))),
        O.icon('chevR')),
      h('div',{class:'settings-row',onclick:()=>{s.close();if(O.runTutorial)O.runTutorial(true)}},
        h('div',{class:'settings-row-icon green'},O.icon('bulb')),
        h('div',{class:'settings-row-text'},
          h('b',null,O.tr('Ghost RPG Cutscene Tutorial','Обучение от Призрака')),
          h('span',null,O.tr('Play interactive story cutscene','Пройти обучение с анимациями заново'))),
        O.icon('chevR')),
      h('div',{class:'settings-row',onclick:()=>{s.close();O.openStatus()}},
        h('div',{class:'settings-row-icon green'},O.icon('server')),
        h('div',{class:'settings-row-text'},
          h('b',null,O.tr('MCP & Tools Status','Статус MCP и инструментов')),
          h('span',null,connected+' '+O.tr('servers active','серверов активно'))),
        O.icon('chevR')),
      h('div',{class:'settings-row',onclick:()=>{s.close();O.openSettings()}},
        h('div',{class:'settings-row-icon blue'},O.icon('sliders')),
        h('div',{class:'settings-row-text'},
          h('b',null,O.tr('Settings','Настройки')),
          h('span',null,O.tr('Models, Appearance, Rules & More','Модели, вид, правила и другое'))),
        O.icon('chevR'))
    )
  ));
};

O.enter=async opt=>{
  build();
  O.saveOpts();
  S.offline=!!(opt&&opt.offline);
  S.up=false;
  updateBanner();
  OC.sseStart();
  if(O.checkAccount)O.checkAccount();
  await O.setDir(O.store.get('dir','/root'));
  updateBanner();
  updateTop();
  refreshStatus();
  O.iconsReady.then(()=>{O.refreshFiles(false)});
  setTimeout(()=>{
    if(O.runTutorial)O.runTutorial(false);
  },800);
};

O.reconnect=async()=>{
  if(!built)return;
  S.offline=false;
  S.up=true;
  updateBanner();
  try{OC.sseStart()}catch(e){}
  await O.setDir(S.dir||O.store.get('dir','/root'));
  updateBanner();
  refreshStatus();
};

O.on('server',d=>{
  if(!built)return;
  S.up=!!d.ready;
  updateBanner();
  if(d.ready){
    try{OC.sseStart()}catch(e){}
    refreshStatus();
  }
});

const baseBack=O.back;
O.back=()=>{
  if(baseBack())return true;
  if(built&&!wide()&&tab!=='chat'){
    O.setTab('chat');
    return true;
  }
  return false;
};

O.spawnBats=()=>{
  for(let i=0;i<5;i++){
    const bat=h('div',{class:'flying-bat',style:{
      top:(15+Math.random()*45)+'vh',
      left:(i%2===0?'-60px':'calc(100vw + 30px)'),
      animationDelay:(i*200)+'ms',
      animationDirection:(i%2===0?'normal':'reverse')
    }},h('div',{class:'bat-body',html:`<svg viewBox="0 0 32 18" width="32" height="18" fill="#18181b"><path d="M16 10 C14 5, 8 2, 2 5 C3 10, 8 13, 11 11 C12 14, 15 15, 16 13 C17 15, 20 14, 21 11 C24 13, 29 10, 30 5 C24 2, 18 5, 16 10 Z"/><circle cx="14.5" cy="9" r="0.9" fill="#ef4444"/><circle cx="17.5" cy="9" r="0.9" fill="#ef4444"/></svg>`}));
    document.body.append(bat);
    setTimeout(()=>bat.remove(),3500);
  }
};

O.playChime=kind=>{
  try{if(window.OC&&window.OC.testSound){window.OC.testSound(kind);return}}catch(e){}
  try{
    const ctx=new (window.AudioContext||window.webkitAudioContext)();
    if(!ctx)return;
    const now=ctx.currentTime;
    const notes=kind==='done'?[[659.25,0,0.11],[880,0.11,0.13],[1318.5,0.24,0.36]]
      :(kind==='action'?[[784,0,0.11],[1046.5,0.11,0.28]]
      :[[587.33,0,0.15],[493.88,0.15,0.32]]);
    notes.forEach(([freq,delay,dur])=>{
      const osc=ctx.createOscillator();
      const gain=ctx.createGain();
      osc.type='sine';
      osc.frequency.setValueAtTime(freq,now+delay);
      gain.gain.setValueAtTime(0.0001,now+delay);
      gain.gain.exponentialRampToValueAtTime(0.18,now+delay+0.015);
      gain.gain.exponentialRampToValueAtTime(0.0001,now+delay+dur);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(now+delay);
      osc.stop(now+delay+dur+0.05);
    });
  }catch(e){}
};

addEventListener('unhandledrejection',e=>console.error(e.reason));
document.addEventListener('DOMContentLoaded',()=>{
  O.saveOpts();
  O.bootRun();
});
})();
