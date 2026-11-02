(function(){
const O=window.OCM,h=O.h,S=O.S,bus=O.bus;

// Universal Action Dispatcher
O.dispatchAction=async function(act,target,data){
  if(!act)return;
  const a=String(act).toLowerCase().trim();
  const t=target||(data&&data.target)||'';
  switch(a){
    case'new_chat':
      try{
        const r=await O.api('POST','/session',{});
        if(O.loadSessions)await O.loadSessions();
        if(O.openSession)O.openSession(r.id);
        O.setTab('chat');
        O.toast(O.tr('New chat created','Создан новый чат'),1500);
      }catch(e){O.toast(e.message)}
      break;
    case'open_app':
      if(t){
        const ok=O.native.openApp(t);
        if(!ok)O.toast(O.tr('Opening '+t+'...','Открытие '+t+'...'),1500);
      }else{
        O.openAppChooser();
      }
      break;
    case'open_files':
    case'files':
      O.setTab('files');
      break;
    case'open_term':
    case'term':
      O.setTab('term');
      break;
    case'preview':
    case'web_preview':
      O.openPreview(t);
      break;
    case'restart_server':
      if(window.OC&&OC.restartServer){
        OC.restartServer();
        O.toast(O.tr('Restarting server...','Перезапуск сервера...'),2000);
      }
      break;
    case'clear_cache':
      try{
        if(O.kv&&O.kv.clear)await O.kv.clear();
        O.toast(O.tr('Cache cleared successfully','Кэш успешно очищен'),1500);
      }catch(e){O.toast(e.message)}
      break;
    case'bats':
      if(O.spawnBats)O.spawnBats();
      O.toast('🦇 🦇 🦇 Бу!',1200);
      break;
    case'sound_done':
      if(window.OC&&OC.testSound)OC.testSound('done');
      break;
    case'sound_action':
      if(window.OC&&OC.testSound)OC.testSound('action');
      break;
    case'sound_quota':
      if(window.OC&&OC.testSound)OC.testSound('quota');
      break;
    case'tutorial':
      if(O.runTutorial)O.runTutorial(true);
      break;
    case'open_settings':
      O.openSettings();
      break;
    case'prompt':
      if(t&&O.insertPrompt)O.insertPrompt(t);
      else if(t){
        const ta=document.querySelector('#composer textarea');
        if(ta){ta.value=t;ta.focus();ta.dispatchEvent(new Event('input'))}
        O.setTab('chat');
      }
      break;
    case'toast':
      O.toast(t||O.tr('Action executed','Действие выполнено'),2000);
      break;
    default:
      console.log('Unknown action:',a,t);
  }
};

// Modal for choosing an app to open
O.openAppChooser=async function(){
  const apps=[
    {name:'Telegram',id:'telegram',icon:'tg'},
    {name:'ВКонтакте',id:'vk',icon:'chat'},
    {name:'YouTube',id:'youtube',icon:'play'},
    {name:'Chrome Browser',id:'chrome',icon:'globe'},
    {name:'GitHub',id:'github',icon:'code'},
    {name:'Настройки',id:'settings',icon:'sliders'},
    {name:'Камера',id:'camera',icon:'image'},
    {name:'Проводник / Файлы',id:'files',icon:'folder'},
    {name:'Калькулятор',id:'calculator',icon:'cpu'}
  ];
  const list=h('div',{class:'app-chooser-list'});
  apps.forEach(a=>{
    list.append(h('button',{
      class:'dock-opt-row',
      style:{width:'100%',margin:'4px 0'},
      onclick:()=>{s.close();O.native.openApp(a.id)}
    },
    h('div',{class:'settings-row-icon blue'},O.icon(a.icon)),
    h('div',{class:'dock-opt-content'},
      h('span',{class:'dock-opt-label'},a.name),
      h('span',{class:'dock-opt-desc'},'openapp '+a.id))));
  });
  const customInp=h('input',{class:'dock-custom-inp',placeholder:O.tr('Or type app name / package...','Или введите название / пакет...'),style:{marginTop:'10px'}});
  const openCustomBtn=h('button',{class:'btn wide pri',style:{marginTop:'8px'},onclick:()=>{
    const val=customInp.value.trim();
    if(val){s.close();O.native.openApp(val)}
  }},O.tr('Open Application','Открыть приложение'));
  const box=h('div',null,list,customInp,openCustomBtn);
  const s=O.sheet(O.tr('Open Android App','Запуск приложения Android'),box);
};

// In-App Web Project Preview (HTML + CSS + JS + Images)
O.openPreview=function(relPath){
  const target=relPath&&relPath!=='.'?(relPath.startsWith('/')?relPath:(S.dir+'/'+relPath)):(S.dir||'/root');
  let previewUrl='https://appassets.androidplatform.net/_preview/index.html';
  try{
    if(O.native&&O.native.preparePreview){
      const res=O.native.preparePreview(target);
      if(res)previewUrl=res;
    }
  }catch(e){console.error('preparePreview error:',e)}

  let isMobile=true;
  // Frame without restrictive sandbox attribute so local assets and scripts execute seamlessly
  const frame=h('iframe',{
    class:'web-preview-frame',
    src:previewUrl
  });

  // Direct srcdoc fallback for local HTML files
  if(target.endsWith('.html')||target.endsWith('.htm')){
    try{
      const r=O.native.readText(target);
      if(r&&r.ok&&r.text){
        // Frame will load via URL or srcdoc
        frame.addEventListener('error',()=>{frame.srcdoc=r.text});
      }
    }catch(_){}
  }

  const titleTxt=h('span',{class:'preview-title-text'},target?O.basename(target):'Web Preview');
  const pathInput=h('input',{
    class:'preview-addr-input',
    value:target||'index.html',
    title:O.tr('Path or URL (Press Enter)','Путь или URL (Нажмите Enter)'),
    onkeydown:e=>{
      if(e.key==='Enter'){
        const v=e.target.value.trim();
        if(v.startsWith('http://')||v.startsWith('https://')){
          frame.src=v;
        }else if(O.native&&O.native.preparePreview){
          const p=O.native.preparePreview(v);
          if(p)frame.src=p;
        }
      }
    }
  });

  const viewToggleBtn=h('button',{class:'ibtn s',title:O.tr('Switch Viewport (Mobile/Desktop)','Переключить масштаб (Мобильный/ПК)'),onclick:()=>{
    isMobile=!isMobile;
    frameWrap.classList.toggle('desktop-mode',!isMobile);
    viewToggleBtn.textContent=isMobile?'📱':'💻';
    O.toast(isMobile?O.tr('Mobile View (390px)','Мобильный вид (390px)'):O.tr('Desktop View (100%)','Широкий вид (100%)'),1000);
  }},'📱');
  const refreshBtn=h('button',{class:'ibtn s',title:O.tr('Reload','Обновить'),onclick:()=>{
    try{
      if(O.native&&O.native.preparePreview)O.native.preparePreview(target);
      frame.src=previewUrl+(previewUrl.includes('?')?'&':'?')+'_t='+Date.now();
      O.toast(O.tr('Reloaded','Обновлено'),800);
    }catch(e){}
  }},O.icon('refresh'));
  const externalBtn=h('button',{class:'ibtn s',title:O.tr('Open in External Browser','Открыть во внешнем браузере'),onclick:()=>{
    O.native.open(previewUrl);
  }},O.icon('ext'));

  const bar=h('div',{class:'web-preview-bar'},
    h('div',{class:'preview-bar-left'},O.icon('globe'),titleTxt,pathInput),
    h('div',{class:'preview-bar-right'},viewToggleBtn,refreshBtn,externalBtn));

  const frameWrap=h('div',{class:'web-preview-frame-wrap mobile-mode'},frame);
  const container=h('div',{class:'web-preview-container'},bar,frameWrap);
  const s=O.sheet(O.tr('Project Web Preview','Предпросмотр проекта'),container,{full:true});
};

// 3 Built-in Detailed Widgets
O.getBuiltinWidgets=function(){
  // Widget 1: System HUD
  const sysBody=h('div',{class:'ocm-widget-body'},
    h('div',{class:'hud-metric-row'},
      h('div',{class:'hud-metric'},h('span',{class:'hud-metric-label'},'Архитектура'),h('b',{class:'hud-metric-val'},'ARM64 Linux')),
      h('div',{class:'hud-metric'},h('span',{class:'hud-metric-label'},'Частота экрана'),h('b',{class:'hud-metric-val',style:{color:'#22c55e'}},'120 Hz ⚡')),
      h('div',{class:'hud-metric'},h('span',{class:'hud-metric-label'},'Статус ядра'),h('b',{class:'hud-metric-val',style:{color:S.up?'#22c55e':'#eab308'}},S.up?'Онлайн':'Запуск'))),
    h('div',{class:'widget-actions-grid'},
      h('button',{class:'btn-widget-action',onclick:()=>O.dispatchAction('restart_server')},O.icon('refresh'),'Перезапуск ядра'),
      h('button',{class:'btn-widget-action',onclick:()=>O.dispatchAction('open_app','telegram')},O.icon('tg'),'Telegram'),
      h('button',{class:'btn-widget-action',onclick:()=>O.dispatchAction('open_settings')},O.icon('sliders'),'Настройки')));

  // Widget 2: Quick Launch & Dev Actions
  const launchBody=h('div',{class:'ocm-widget-body'},
    h('div',{class:'widget-quick-tiles'},
      h('button',{class:'quick-tile',onclick:()=>O.dispatchAction('new_chat')},
        h('div',{class:'tile-icon purple'},O.icon('plus')),h('b',null,'Новый чат'),h('span',null,'Начать чистую сессию')),
      h('button',{class:'quick-tile',onclick:()=>O.dispatchAction('open_term')},
        h('div',{class:'tile-icon green'},O.icon('terminal')),h('b',null,'Терминал'),h('span',null,'Команды bash & pkg')),
      h('button',{class:'quick-tile',onclick:()=>O.dispatchAction('open_files')},
        h('div',{class:'tile-icon blue'},O.icon('folder')),h('b',null,'Файлы'),h('span',null,'Проводник проектов')),
      h('button',{class:'quick-tile',onclick:()=>O.dispatchAction('preview')},
        h('div',{class:'tile-icon amber'},O.icon('globe')),h('b',null,'Веб-Превью'),h('span',null,'Запуск сайта в приложении'))));

  // Widget 3: Halloween Spooky Soundboard & Guide
  const magicBody=h('div',{class:'ocm-widget-body'},
    h('div',{class:'widget-sound-grid'},
      h('button',{class:'btn-sound-card',onclick:()=>O.dispatchAction('bats')},
        h('span',{style:{fontSize:'20px'}},'🦇'),h('b',null,'Стая мышей'),h('span',null,'Хэллоуин-эффект')),
      h('button',{class:'btn-sound-card',onclick:()=>O.dispatchAction('sound_done')},
        h('span',{style:{fontSize:'20px'}},'🔔'),h('b',null,'Чайм финиша'),h('span',null,'Звук E5→A5→E6')),
      h('button',{class:'btn-sound-card',onclick:()=>O.dispatchAction('sound_action')},
        h('span',{style:{fontSize:'20px'}},'⚠️'),h('b',null,'Чайм вопроса'),h('span',null,'Звук G5→C6')),
      h('button',{class:'btn-sound-card',onclick:()=>O.dispatchAction('tutorial')},
        h('span',{style:{fontSize:'20px'}},'👻'),h('b',null,'Гид Призрак'),h('span',null,'Катсцена обучения'))));

  return [
    {id:'sys_hud',title:'Системный HUD',icon:'cpu',body:sysBody},
    {id:'quick_launch',title:'Быстрый запуск разработчика',icon:'sliders',body:launchBody},
    {id:'halloween_magic',title:'Магия Хэллоуина & Звуки',icon:'ghost',body:magicBody}
  ];
};

// Modal exhibiting all widgets
O.openWidgetsModal=function(){
  const wrap=h('div',{class:'widgets-modal-wrap'});
  const widgets=O.getBuiltinWidgets();
  widgets.forEach(w=>{
    const card=h('div',{class:'hud-dock-card widget-card'},
      h('div',{class:'hud-dock-header'},
        h('div',{class:'hud-dock-header-left'},
          h('div',{class:'badge-icon'},O.icon(w.icon)),
          h('b',null,w.title))),
      w.body);
    wrap.append(card);
  });
  const customSection=h('div',{style:{marginTop:'16px',textAlign:'center'}},
    h('button',{class:'btn wide',onclick:()=>{s.close();O.openCustomWidgetCreator()}},O.icon('plus'),O.tr('Create Custom Widget from HTML','Создать свой виджет из HTML')));
  wrap.append(customSection);
  const s=O.sheet(O.tr('HUD Interactive Widgets','Интерактивные виджеты HUD'),wrap,{full:false});
};

// Custom widget creator dialog
O.openCustomWidgetCreator=function(){
  const ta=h('textarea',{class:'editor',style:{height:'160px'},placeholder:'<div class="ocm-widget" data-title="Мой кастомный виджет">\n  <p>Привет из OpenCode!</p>\n  <button data-action="new_chat">Новый чат</button>\n  <button data-action="open_app" data-target="telegram">Открыть Telegram</button>\n</div>'});
  ta.value=`<div class="ocm-widget" data-title="Мой Инструмент">\n  <p style="color:#a1a1aa;margin-bottom:10px;">Быстрые действия на телефоне:</p>\n  <div style="display:flex;gap:8px;flex-wrap:wrap;">\n    <button class="btn sm pri" data-action="new_chat">💬 Новый чат</button>\n    <button class="btn sm" data-action="open_app" data-target="telegram">✈️ Telegram</button>\n    <button class="btn sm" data-action="preview">🌐 Предпросмотр</button>\n  </div>\n</div>`;
  const previewBox=h('div',{class:'custom-widget-preview-area',style:{marginTop:'12px'}});
  const renderPrev=()=>{
    previewBox.innerHTML=ta.value;
    O.initCustomWidgets(previewBox);
  };
  ta.addEventListener('input',renderPrev);
  const saveBtn=h('button',{class:'btn pri wide',style:{marginTop:'12px'},onclick:async()=>{
    const code=ta.value.trim();
    if(!code)return;
    const path='/root/.config/opencode/widgets/widget_'+Date.now()+'.html';
    const r=O.native.writeText(path,code);
    if(r.ok){
      O.toast(O.tr('Widget saved to '+path,'Виджет сохранён в '+path),2000);
      s.close();
    }else{O.toast(r.err||'Ошибка сохранения')}
  }},O.tr('Save Widget','Сохранить виджет'));

  const box=h('div',null,h('p',{class:'mut s',style:{marginBottom:'8px'}},'Вставьте HTML с кнопками `data-action="..."` (например `new_chat`, `open_app`, `preview`, `run_cmd`):'),ta,h('b',{style:{display:'block',marginTop:'10px'}},'Предпросмотр:'),previewBox,saveBtn);
  renderPrev();
  const s=O.sheet(O.tr('Custom Widget Creator','Конструктор виджетов'),box,{full:true});
};

// Scan and bind events in custom widgets (in chat messages or dialogs)
O.initCustomWidgets=function(root){
  if(!root)return;
  const elements=root.querySelectorAll('[data-action]');
  elements.forEach(btn=>{
    if(btn._bound)return;
    btn._bound=true;
    btn.addEventListener('click',e=>{
      e.preventDefault();
      e.stopPropagation();
      const act=btn.getAttribute('data-action');
      const target=btn.getAttribute('data-target')||btn.getAttribute('data-path')||btn.getAttribute('data-cmd')||'';
      O.dispatchAction(act,target,{target});
    });
  });
};

// Listen to native preview events (e.g. from openpreview CLI script or bridge)
O.on('preview', d => {
  if (d && d.path) O.openPreview(d.path);
  else O.openPreview();
});

})();
