(function(){
const O=window.OCM,h=O.h,S=O.S,api=O.api,bus=O.bus;
let built=false,tab='chat',sideTab='term',T={},P={},B={},SP={},SB={};
const wide=()=>innerWidth>=820;

O.tr=(en,ru)=>(O.store.get('lang','ru')==='en'?en:ru);

function build(){
  if(built)return;built=true;
  const app=O.$('#app');

  // Minimal Top Header matching Screenshot 1: Hamburger on left, Ghost mascot on right
  T.menu=h('button',{class:'top-menu-btn',onclick:()=>O.openSessions()},O.icon('menu'));
  T.ghost=h('button',{class:'top-ghost-btn',onclick:()=>O.openGhost()},O.icon('ghost'));
  const top=h('div',{id:'top'},T.menu,T.ghost);

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

  app.append(top,T.banner,main,bottomBar);

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

function updateTop(){
  if(!built)return;
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
          h('b',null,'OpenCode Mobile v2.0'),
          h('span',null,S.up?(O.tr('Core Runtime Active · Linux ARM64','Ядро активно · Linux ARM64')):(O.tr('Connecting...','Подключение...'))))),
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

addEventListener('unhandledrejection',e=>console.error(e.reason));
document.addEventListener('DOMContentLoaded',()=>{
  O.saveOpts();
  O.bootRun();
});
})();
