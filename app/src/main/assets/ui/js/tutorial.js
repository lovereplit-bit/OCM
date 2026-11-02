(function(){
const O=window.OCM,h=O.h;

const SCENES=[
  {
    title: 'ПРИЗРАК OPENCODE',
    badge: 'LVL 99 • ХРАНИТЕЛЬ',
    mood: 'spooky',
    text: 'Буу! Не бойся, путник! 👻 Я — Призрак OpenCode, твой вечный проводник по тайнам разработки прямо на твоём смартфоне!'
  },
  {
    title: 'НЕЙРОСЕТИ И МОДЕЛИ',
    badge: 'СИЛА ИИ',
    mood: 'magic',
    text: 'В твоих руках мощнейший ИИ-ассистент: Claude, GPT-4o, DeepSeek и Qwen! Бесплатные модели уже готовы к работе в меню внизу экрана.'
  },
  {
    title: 'НАСТОЯЩИЙ LINUX',
    badge: 'ARM64 TERMINAL',
    mood: 'tech',
    text: 'Под капотом бьётся сердце настоящего Linux! Запускай скрипты Python, Node.js, ставь пакеты через `pkg` и даже открывай приложения: напиши «открой телеграм» или выполни `openapp telegram`!'
  },
  {
    title: 'ВЕБ-ПРЕДПРОСМОТР',
    badge: 'HTML + CSS + JS',
    mood: 'code',
    text: 'Создаёшь сайты или веб-приложения? Смотри результат мгновенно прямо внутри OpenCode через встроенный Веб-Предпросмотр в один клик!'
  },
  {
    title: 'ПОСВЯЩЕНИЕ В МАСТЕРА',
    badge: 'ПОГНАЛИ ТВОРИТЬ!',
    mood: 'celebrate',
    text: 'Ты готов стать повелителем мобильного кодинга! 🎃 Нажимай на тыкву для магии и зови меня через иконку призрака вверху. Вперёд к величию!'
  }
];

O.runTutorial=function(force){
  if(!force && O.store.get('tutorial_done', false)) return;

  let currentIdx=0;
  let typeTimer=null;
  let isTyping=false;
  let currentFullText='';

  const overlay=h('div',{class:'tutorial-cutscene-overlay'});
  
  // Ghost Character
  const ghostMascot=h('div',{class:'cutscene-ghost-wrap'},
    h('div',{class:'cutscene-ghost-aura'}),
    h('div',{class:'cutscene-ghost-svg',html:`<svg viewBox="0 0 100 120" width="110" height="130" fill="none">
      <defs>
        <radialGradient id="ghGlow" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stop-color="#c084fc"/>
          <stop offset="60%" stop-color="#9333ea"/>
          <stop offset="100%" stop-color="#581c87"/>
        </radialGradient>
        <filter id="auraGlow" x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur stdDeviation="3" result="blur"/>
          <feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>
        </filter>
      </defs>
      <!-- Ghost body -->
      <path d="M50 10 C25 10 15 35 15 65 C15 95 24 110 32 100 C40 90 44 110 52 100 C60 90 64 110 72 100 C80 90 85 105 85 65 C85 35 75 10 50 10 Z" fill="url(#ghGlow)" filter="url(#auraGlow)"/>
      <!-- Soft highlight -->
      <path d="M50 16 C30 16 22 38 22 62 C22 80 25 90 30 85 C34 80 38 90 46 82 C52 76 60 76 68 82 C74 88 78 80 78 62 C78 38 70 16 50 16 Z" fill="rgba(255,255,255,0.18)"/>
      <!-- Glowing Eyes -->
      <ellipse cx="38" cy="50" rx="6" ry="8" fill="#ffffff" class="ghost-eye-left"/>
      <ellipse cx="62" cy="50" rx="6" ry="8" fill="#ffffff" class="ghost-eye-right"/>
      <ellipse cx="40" cy="50" rx="3" ry="4" fill="#18181b"/>
      <ellipse cx="64" cy="50" rx="3" ry="4" fill="#18181b"/>
      <!-- Cute mouth -->
      <ellipse cx="50" cy="68" rx="6" ry="7" fill="#1e1829" class="ghost-mouth"/>
      <!-- Rosy cheeks -->
      <circle cx="28" cy="62" r="5" fill="rgba(244,114,182,0.4)"/>
      <circle cx="72" cy="62" r="5" fill="rgba(244,114,182,0.4)"/>
    </svg>`})
  );

  // Floating particles
  const particles=h('div',{class:'cutscene-particles'});
  for(let i=0;i<8;i++){
    particles.append(h('div',{class:'magic-sparkle',style:{
      left:(10+Math.random()*80)+'%',
      top:(15+Math.random()*60)+'%',
      animationDelay:(Math.random()*2)+'s'
    }}));
  }

  // RPG Dialog Box
  const nameLabel=h('b',{class:'cutscene-char-name'},SCENES[0].title);
  const badgeLabel=h('span',{class:'cutscene-char-badge'},SCENES[0].badge);
  const textEl=h('div',{class:'cutscene-dialog-text'});
  const indicatorEl=h('div',{class:'cutscene-next-arrow'},'▼');

  const skipBtn=h('button',{class:'cutscene-btn-skip',onclick:finishTutorial},'Пропустить ⏩');
  const nextBtn=h('button',{class:'cutscene-btn-next',onclick:handleNext},'Далее ▶');

  const dialogHeader=h('div',{class:'cutscene-dialog-header'},
    h('div',{style:{display:'flex',alignItems:'center',gap:'8px'}},nameLabel,badgeLabel),
    skipBtn);

  const dialogFooter=h('div',{class:'cutscene-dialog-footer'},
    h('div',{class:'cutscene-step-dots'}),
    nextBtn);

  const dialogBox=h('div',{class:'cutscene-dialog-box'},
    dialogHeader,
    h('div',{class:'cutscene-text-wrap'},textEl,indicatorEl),
    dialogFooter);

  overlay.append(particles,ghostMascot,dialogBox);
  document.body.append(overlay);

  function renderScene(idx){
    const s=SCENES[idx];
    nameLabel.textContent=s.title;
    badgeLabel.textContent=s.badge;
    nextBtn.textContent=idx===SCENES.length-1?'Начать творить! 🚀':'Далее ▶';
    indicatorEl.style.opacity='0';

    // Update dots
    const dotsWrap=dialogFooter.querySelector('.cutscene-step-dots');
    dotsWrap.textContent='';
    SCENES.forEach((_,i)=>{
      dotsWrap.append(h('div',{class:'step-dot'+(i===idx?' active':'')}));
    });

    // Typewriter effect
    clearInterval(typeTimer);
    isTyping=true;
    textEl.textContent='';
    currentFullText=s.text;
    let charIdx=0;
    typeTimer=setInterval(()=>{
      if(charIdx<currentFullText.length){
        textEl.textContent+=currentFullText[charIdx++];
      }else{
        clearInterval(typeTimer);
        isTyping=false;
        indicatorEl.style.opacity='1';
      }
    },22);
  }

  function handleNext(){
    if(isTyping){
      // Skip typewriter, show full text immediately
      clearInterval(typeTimer);
      textEl.textContent=currentFullText;
      isTyping=false;
      indicatorEl.style.opacity='1';
      return;
    }
    if(currentIdx<SCENES.length-1){
      currentIdx++;
      renderScene(currentIdx);
      if(window.OC&&OC.testSound)OC.testSound('action');
    }else{
      finishTutorial();
    }
  }

  function finishTutorial(){
    clearInterval(typeTimer);
    O.store.set('tutorial_done',true);
    if(O.spawnBats)O.spawnBats();
    if(window.OC&&OC.testSound)OC.testSound('done');
    O.toast('🎉 Добро пожаловать в OpenCode Mobile v2.0.1!',3000);
    overlay.classList.add('fade-out');
    setTimeout(()=>overlay.remove(),500);
  }

  renderScene(0);
};

})();
