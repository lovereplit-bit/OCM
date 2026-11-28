(function(){
const O=window.OCM,h=O.h;let el=null,entered=false,permsRender=null,waitT=null,globeStop=null;
const screen=(...k)=>{if(!el){el=h('div',{id:'boot'});document.body.append(el)}
  if(globeStop){globeStop();globeStop=null}
  el.textContent='';el.append(h('div',{class:'center'},...k.flat()));return el};

function globe(){
  const cv=h('canvas',{class:'globe',width:560,height:560});
  const wrap=h('div',{class:'boot-globe'},cv);
  const ctx=cv.getContext('2d');
  const ellipses=[
    [48,-100,23,34],[15,-90,9,13],[-15,-60,23,14],[72,-42,8,12],
    [50,15,12,19],[6,20,31,22],[28,45,10,12],[50,90,28,54],
    [20,78,10,10],[5,110,12,15],[-25,135,12,18],[-85,0,10,180],
    [38,138,6,6],[54,-3,4,5],[-20,47,7,3],[-42,173,5,4]
  ];
  const isLand=(lat,lon)=>{
    for(const [la,lo,rla,rlo] of ellipses){
      let dl=Math.abs(lon-lo); if(dl>180)dl=360-dl;
      const d=(lat-la)/rla, e=dl/rlo;
      if(d*d+e*e<1)return true;
    }
    return false;
  };
  const pts=[];
  for(let lat=-88;lat<=88;lat+=2.6){
    for(let lon=-180;lon<180;lon+=2.6){
      const land=isLand(lat,lon);
      if(!land&&((Math.round(lat*10)+Math.round(lon*10))&1))continue;
      pts.push({lat:lat*Math.PI/180,lon:lon*Math.PI/180,land});
    }
  }
  const ramp='.,:;-=+*#%@';
  let rgb=[255,255,255];
  try{
    const fg=getComputedStyle(document.documentElement).getPropertyValue('--fg').trim();
    const hm=/^#([0-9a-f]{6})$/i.exec(fg);
    if(hm){const n=parseInt(hm[1],16);rgb=[(n>>16)&255,(n>>8)&255,n&255]}
    else{const rm=fg.match(/(\d+)\D+(\d+)\D+(\d+)/);if(rm)rgb=[+rm[1],+rm[2],+rm[3]]}
  }catch(_){}
  const L=[-0.45,-0.55,0.70];const ll=Math.hypot(...L);
  const cx=280,cy=280,R=232,tilt=-0.32,ct=Math.cos(tilt),st=Math.sin(tilt);
  let raf=0,ang=0,last=0;
  const draw=now=>{
    raf=requestAnimationFrame(draw);
    if(now-last<33)return; last=now;
    ang+=0.010;
    ctx.clearRect(0,0,560,560);
    ctx.font='6px ui-monospace,SFMono-Regular,Menlo,monospace';
    ctx.textAlign='center';ctx.textBaseline='middle';
    for(const p of pts){
      const cl=Math.cos(p.lat),sl=Math.sin(p.lat);
      const lo=p.lon+ang;
      const x=cl*Math.sin(lo),y=sl,z=cl*Math.cos(lo);
      const y2=y*ct-z*st,z2=y*st+z*ct;
      if(z2<=0.02)continue;
      const shade=Math.max(0.05,(x*L[0]+y2*L[1]+z2*L[2])/ll);
      const depth=0.35+0.65*z2;
      let a=(p.land?0.22+0.78*shade:0.07+0.22*shade)*depth;
      if(a<0.04)continue;
      const ch=p.land?ramp[Math.min(ramp.length-1,Math.floor(shade*ramp.length*0.99))]:'.';
      ctx.fillStyle='rgba('+rgb[0]+','+rgb[1]+','+rgb[2]+','+a.toFixed(3)+')';
      ctx.fillText(ch,cx+x*R,cy-y2*R);
    }
  };
  raf=requestAnimationFrame(draw);
  wrap._stop=()=>cancelAnimationFrame(raf);
  return wrap;
}
O.bootHide=()=>{if(globeStop){globeStop();globeStop=null}if(el){el.remove();el=null}};
const logo=()=>[O.logo(44),h('b',{style:{fontSize:'22px'}},'OpenCode')];
function login(msg){
  const st=O.native.state();
  let em=null,gb=null,gbtn=null;
  const goEmail=()=>{const v=em.value.trim();if(!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v)){O.toast('Введите корректный email');return}gb.disabled=true;OC.signInEmail(v)};
  const emailBox=h('div',{style:{width:'100%',display:'flex',gap:'6px'}},
    em=h('input',{type:'email',placeholder:'ваша@почта.ru',autocapitalize:'off',autocorrect:'off',style:{flex:1},onkeydown:e=>{if(e.key==='Enter')goEmail()}}),
    gb=h('button',{class:'btn pri',onclick:goEmail},'Создать'));
  if(st.user){
    // a profile exists on this phone: continue with it, or spin up another guest
    screen(logo(),
      h('b',{style:{fontSize:'20px'}},'Профиль на этом телефоне'),
      h('button',{class:'btn pri wide',onclick:next},'Продолжить как '+(st.name||st.user)),
      h('button',{class:'btn wide',style:{marginTop:'10px'},onclick:()=>OC.signInGuest(true)},O.icon('user'),'Новый профиль гостя'),
      h('div',{class:'note',style:{margin:'10px 0'}},'Новому гостю создастся свой случайный ник, пароль и отдельный профиль.'),
      h('span',{class:'mut s'},'v'+st.version));
    return}
  screen(logo(),
    h('b',{style:{fontSize:'20px'}},'Профиль на этом телефоне'),
    h('p',{class:'mut'},msg||'Чаты, файлы и ключи хранятся только на этом телефоне. Гостю ник и пароль создаются автоматически.'),
    gbtn=h('button',{class:'btn pri wide',onclick:()=>{gbtn.disabled=true;OC.signInGuest(false)}},O.icon('user'),'Продолжить как гость'),
    h('div',{class:'note',style:{margin:'10px 0'}},'У каждого гостя — свой случайный ник и пароль, свой профиль и своя бесплатная квота. Никто не сидит на общем аккаунте.'),
    h('details',{style:{width:'100%'}},
      h('summary',{class:'mut',style:{cursor:'pointer',padding:'6px 0'}},'Войти по почте (не Google)'),
      h('p',{class:'mut s'},'Почта нужна только для имени профиля. Пароль не требуется, данные не покидают телефон.'),
      emailBox),
    st.googleConfigured?h('button',{class:'btn wide',style:{marginTop:'10px'},onclick:()=>OC.signIn()},O.icon('user'),'Войти через Google'):null,
    h('span',{class:'mut s',style:{marginTop:'10px'}},'Бесплатные модели работают сразу. Личную квоту Zen можно подключить позже: Настройки → Аккаунт opencode.'),
    h('span',{class:'mut s'},'v'+st.version))}
function perms(){const render=()=>{const rows=O.permRows();const box=el&&el.querySelector('#pl');if(box){box.textContent='';box.append(...rows)}};permsRender=render;
  screen(h('div',{style:{textAlign:'left',width:'100%'}},h('b',{style:{fontSize:'20px'}},'Разрешения'),h('p',{class:'mut'},'OpenCode нужны эти разрешения, чтобы работать как терминальным агентом на компьютере.'),h('div',{id:'pl'},...O.permRows()),
    h('button',{class:'btn pri wide',style:{marginTop:'14px'},onclick:()=>{O.store.set('permsSeen',true);permsRender=null;next()}},'Продолжить')))}
let inst=null;
function install(){const bar=h('i'),msg=h('p',{class:'mut'},'Распаковываем Linux-окружение. Это происходит один раз.'),err=h('p',{class:'err'});
  const g=globe();
  inst={bar,msg,err};screen(logo(),g,msg,h('div',{class:'bar',style:{width:'100%'}},bar),err);globeStop=g._stop;OC.install()}
O.on('install',d=>{if(!inst||!el)return;
  if(d.error){inst.msg.textContent='Установка не удалась';inst.err.textContent=d.error;
    if(!el.querySelector('.retry'))el.firstChild.append(h('button',{class:'btn retry',onclick:install},O.icon('refresh'),'Повторить'));
    if(!el.querySelector('.reset'))el.firstChild.append(h('button',{class:'btn reset',style:{marginTop:'8px'},onclick:()=>OC.resetInstall()},O.icon('trash'),'Сбросить и установить заново'))}
  else if(d.done){inst=null;start()}else inst.bar.style.width=d.pct+'%'});
function start(){
  const msg=h('p',{class:'mut'},'Запуск OpenCode'),err=h('p',{class:'err'}),extra=h('div');
  const spin=h('span',{class:'spin'});
  const g=globe();
  screen(logo(),g,h('div',{style:{display:'flex',gap:'8px',alignItems:'center'}},spin,msg),err,extra);
  globeStop=g._stop;
  OC.start();clearTimeout(waitT);
  waitT=setTimeout(()=>{
    if(el&&!entered){
      extra.append(
        h('button',{class:'btn wide',onclick:()=>{O.bootHide();entered=true;O.enter()}},O.icon('doc'),'Читать кэшированные чаты, пока идёт запуск'),
        h('button',{class:'btn wide',style:{marginTop:'8px'},onclick:()=>{
          if(spin)spin.remove();
          msg.textContent='Сброс конфигурации...';
          if(window.OC&&OC.resetConfig)OC.resetConfig();else if(window.OC&&OC.restartServer)OC.restartServer();
        }},O.icon('refresh'),'Сбросить конфигурацию и перезапустить')
      );
    }
  },4000);
}
function next(){const st=O.native.state(),p=st.perms||{};
  if(!O.store.get('permsSeen')&&!(p.notifications&&p.files&&p.battery&&p.install))return perms();
  if(!st.installed)return install();start()}
O.on('signin',d=>{if(!el)return;d.ok?next():login(d.error||'Не удалось войти')});
O.on('perms',()=>{if(permsRender)permsRender()});
O.on('boot',d=>{
  if(!el)return;
  const e=el.querySelector('.err');
  if(e)e.textContent=d.error;
  const sp=el.querySelector('.spin');
  if(sp)sp.remove();
  const m=el.querySelector('.mut');
  if(m)m.textContent='Ошибка запуска сервера';
  if(!el.querySelector('.retry')){
    el.firstChild.append(
      h('button',{class:'btn retry pri wide',style:{marginTop:'10px'},onclick:start},O.icon('refresh'),'Повторить запуск'),
      h('button',{class:'btn wide',style:{marginTop:'8px'},onclick:()=>{if(window.OC&&OC.resetConfig)OC.resetConfig();start()}},O.icon('trash'),'Сбросить конфигурацию и запустить')
    );
  }
});
O.on('ready',()=>{clearTimeout(waitT);O.bootHide();if(!entered){entered=true;O.enter()}else O.reconnect()});
O.bootRun=()=>{const st=O.native.state();
  if(st.ready&&st.user&&st.installed){entered=true;O.enter();return}
  if(st.user)return next();
  login()};
})();
