(function(){
const O=window.OCM;

O.SEASONAL_THEMES=[
  {id:'auto',name:'По календарю (Авто)',desc:'Праздничный HUD включается автоматически по датам',icon:'calendar'},
  {id:'none',name:'Обычный (Без праздников)',desc:'Чистый классический интерфейс OpenCode круглый год',icon:'moon'},
  {id:'newyear',name:'Новый год и зима',desc:'Авто: с 1 декабря по последний день февраля',icon:'snowflake'},
  {id:'halloween',name:'Хэллоуин HUD',desc:'Авто: только в неделю Хэллоуина (25 окт – 1 ноя)',icon:'halloween'},
  {id:'autumn',name:'Осенняя классика',desc:'Тёплый контур листвы и спокойный атмосферный HUD',icon:'leaf'},
  {id:'spring',name:'Весеннее обновление',desc:'Авто: 1–8 марта и 1–9 мая',icon:'flower'},
  {id:'programmer',name:'День программиста',desc:'Авто: 256-й день года (11–15 сентября)',icon:'code'}
];

O.resolveHoliday=(d)=>{
  const dt=d instanceof Date?d:new Date();
  const m=dt.getMonth()+1;
  const day=dt.getDate();
  // Winter / New Year: Dec 1 through last day of February
  if(m===12||m===1||m===2)return 'newyear';
  // Halloween week only: Oct 25 .. Nov 1
  if((m===10&&day>=25)||(m===11&&day===1))return 'halloween';
  // Spring holidays: Mar 1..8 and May 1..9
  if((m===3&&day<=8)||(m===5&&day<=9))return 'spring';
  // Programmer's Day: 256th day of year (+/- 2 days window)
  const start=new Date(dt.getFullYear(),0,1);
  const doy=Math.floor((dt-start)/86400000)+1;
  if(doy>=254&&doy<=258)return 'programmer';
  return 'none';
};

O.activeSeasonKey=(opts)=>{
  const cfg=opts||(O.S&&O.S.opts)||{};
  const mode=cfg.seasonalMode||'auto';
  if(mode==='none')return 'none';
  if(mode==='auto')return O.resolveHoliday();
  return O.SEASONAL_THEMES.some(t=>t.id===mode)?mode:'none';
};

const NS='http://www.w3.org/2000/svg';
function svgEl(vb,cls,inner){
  const s=document.createElementNS(NS,'svg');
  s.setAttribute('viewBox',vb);
  if(cls)s.setAttribute('class',cls);
  s.setAttribute('aria-hidden','true');
  s.innerHTML=inner;
  return s;
}

function buildHalloweenHud(anim){
  const frag=document.createDocumentFragment();
  // Frame hairline with gothic corner runes & spiderwebs
  const cornerWeb='<path d="M0 0h150M0 0v150M0 0L108 108M0 0L138 56M0 0L56 138" stroke="currentColor" stroke-width="0.9" fill="none" opacity="0.55"/><path d="M32 0Q24 24 0 32M66 0Q46 46 0 66M102 0Q70 70 0 102M138 0Q94 94 0 138" stroke="currentColor" stroke-width="0.75" fill="none" opacity="0.38"/><path d="M124 8v18" stroke="currentColor" stroke-width="0.7" opacity="0.5"/><circle cx="124" cy="29" r="3" fill="currentColor" opacity="0.7"/><path d="M121 28l-4-2M127 28l4-2M120 31l-4 1M128 31l4 1" stroke="currentColor" stroke-width="0.8" opacity="0.65"/>';
  frag.append(
    svgEl('0 0 160 160','hud-corner hud-tl hud-hw-web',cornerWeb),
    svgEl('0 0 160 160','hud-corner hud-tr hud-hw-web',cornerWeb),
    svgEl('0 0 160 160','hud-corner hud-bl hud-hw-web',cornerWeb),
    svgEl('0 0 160 160','hud-corner hud-br hud-hw-web',cornerWeb)
  );
  // Top gothic iron arch with etched crescent moon, bats & carved lantern crest
  const topCrest=document.createElement('div');
  topCrest.className='hud-top-garland hud-hw-crest';
  topCrest.append(svgEl('0 0 800 58','hud-crest-svg',
    '<path d="M0 10Q180 42 400 18Q620 42 800 10" stroke="currentColor" stroke-width="1" fill="none" opacity="0.34"/>'
    +'<path d="M240 16l8 10 8-10M544 16l8 10 8-10" stroke="currentColor" stroke-width="0.9" fill="none" opacity="0.45"/>'
    +'<g transform="translate(384,6)">'
    +'<path d="M16 4c-8.5 0-14 5-14 12.5S7.5 29 16 29s14-5 14-12.5S24.5 4 16 4z" fill="none" stroke="currentColor" stroke-width="1.4" opacity="0.75"/>'
    +'<path d="M16 4V1.2c0-.9 1.3-1.4 2.8-1M11 4.8C9 9.5 9 23.5 11 28.2M21 4.8C23 9.5 23 23.5 21 28.2M16 4.2v24.5" stroke="currentColor" stroke-width="0.85" fill="none" opacity="0.45"/>'
    +'<path d="M9.5 13l3.2 2.4-3.8.5zM22.5 13l-3.2 2.4 3.8.5zM10.5 21.5c1.8 2 3.6 2 5.5 1 1.9 1 3.7 1 5.5-1l-1.5 2.4-2-.8-2 1.2-2-1.2-2 .8z" fill="currentColor" opacity="0.85"/>'
    +'</g>'
    +'<path d="M166 24c4-5 11-5 15 0 3-2 7 0 9 3-4 0-6 2-9 2-3 3-8 3-11 0-3 0-6-2-9-2 2-3 5-5 5-3z" fill="currentColor" opacity="0.35"/>'
    +'<path d="M612 20c4-4 9-4 13 0 3-1.5 6 0 8 2.5-3.5 0-5.5 1.8-8 1.8-2.5 2.5-6.5 2.5-9 0-2.5 0-5-1.8-7.5-1.8 1.5-2.5 3.5-4 3.5-2.5z" fill="currentColor" opacity="0.35"/>'
  ));
  frag.append(topCrest);

  // Subtle carved lantern watermark on bottom-right edge (well away from text/composer)
  const lantern=document.createElement('div');
  lantern.className='hud-bottom-crest hud-hw-lantern';
  lantern.append(svgEl('0 0 120 120','hud-lantern-svg',
    '<path d="M60 12v14M44 26h32M40 34h40l-4 46H44z" fill="none" stroke="currentColor" stroke-width="1.2" opacity="0.45"/>'
    +'<circle cx="60" cy="57" r="9" fill="none" stroke="currentColor" stroke-width="1" opacity="0.4"/>'
    +'<path d="M60 44c3.5 4.5 5 8 5 11a5 5 0 01-10 0c0-3 1.5-6.5 5-11z" fill="currentColor" opacity="0.55"/>'
    +'<path d="M40 80h40M36 86h48" stroke="currentColor" stroke-width="1.3" opacity="0.45"/>'
  ));
  frag.append(lantern);

  if(anim){
    const specs=[
      {t:'bat',x:12,d:13,s:1.0,del:-2},
      {t:'wisp',x:28,d:16,s:0.85,del:-7},
      {t:'bat',x:74,d:15,s:0.9,del:-5},
      {t:'wisp',x:88,d:18,s:1.1,del:-11},
      {t:'leaf',x:48,d:14,s:0.9,del:-4}
    ];
    for(const p of specs){
      const el=document.createElement('div');
      el.className='hud-particle hud-hw-particle';
      el.style.left=p.x+'%';
      el.style.animationDuration=p.d+'s';
      el.style.animationDelay=p.del+'s';
      el.style.transform=`scale(${p.s})`;
      if(p.t==='bat'){
        el.append(svgEl('0 0 36 18','hud-p-svg','<path d="M18 11c-4-7-11-7-17-3 4 1 6 3 7 6 3-1 6-1 10-3zm0 0c4-7 11-7 17-3-4 1-6 3-7 6-3-1-6-1-10-3z" fill="currentColor" opacity="0.42"/>'));
      }else if(p.t==='wisp'){
        el.append(svgEl('0 0 24 24','hud-p-svg','<circle cx="12" cy="12" r="2.2" fill="currentColor" opacity="0.5"/><circle cx="12" cy="12" r="6" fill="none" stroke="currentColor" stroke-width="0.6" opacity="0.25"/>'));
      }else{
        el.append(svgEl('0 0 24 24','hud-p-svg','<path d="M12 2l2.5 5 5.5-1-3 5 4 3-5 1 1 5-5-3-5 3 1-5-5-1 4-3-3-5 5.5 1z" fill="currentColor" opacity="0.32"/>'));
      }
      frag.append(el);
    }
  }
  return frag;
}

function buildNewYearHud(anim){
  const frag=document.createDocumentFragment();
  const branchSvg='<path d="M4 18C42 18 76 14 136 4M28 16l-10-9M44 15l-11-11M62 13l-10-10M82 11l-9-9M102 8l-8-8M36 17l-12 10M58 15l-13 11M80 12l-11 10M106 8l-9 8" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" fill="none" opacity="0.48"/><path d="M56 15v14M98 10v18" stroke="currentColor" stroke-width="0.7" opacity="0.4"/><circle cx="56" cy="34" r="5" fill="none" stroke="currentColor" stroke-width="1.1" opacity="0.55"/><circle cx="98" cy="33" r="4.5" fill="currentColor" opacity="0.22"/>';
  frag.append(
    svgEl('0 0 150 64','hud-corner hud-tl hud-ny-pine',branchSvg),
    svgEl('0 0 150 64','hud-corner hud-tr hud-ny-pine',branchSvg)
  );
  // Nordic top frosted icicle/star rail
  const topGarland=document.createElement('div');
  topGarland.className='hud-top-garland hud-ny-garland';
  topGarland.append(svgEl('0 0 800 50','hud-crest-svg',
    '<path d="M0 8Q200 28 400 12Q600 28 800 8" stroke="currentColor" stroke-width="0.9" fill="none" opacity="0.32"/>'
    +'<g transform="translate(400,20)" stroke="currentColor" stroke-width="1.15" stroke-linecap="round" fill="none" opacity="0.75">'
    +'<path d="M0-11V11M-11 0H11M-8-8L8 8M8-8L-8 8M-2-8L0-6l2-2M-2 8L0 6l2 2M-8-2L-6 0l-2 2M8-2L6 0l2 2"/>'
    +'</g>'
    +'<circle cx="160" cy="20" r="2" fill="currentColor" opacity="0.45"/><circle cx="280" cy="19" r="1.5" fill="currentColor" opacity="0.35"/>'
    +'<circle cx="520" cy="19" r="1.5" fill="currentColor" opacity="0.35"/><circle cx="640" cy="20" r="2" fill="currentColor" opacity="0.45"/>'
  ));
  frag.append(topGarland);

  if(anim){
    const flakes=[6,14,24,35,47,58,69,78,87,94];
    flakes.forEach((x,idx)=>{
      const el=document.createElement('div');
      el.className='hud-particle hud-ny-flake';
      el.style.left=x+'%';
      el.style.animationDuration=(11+(idx%5)*2.5)+'s';
      el.style.animationDelay=(-(idx*2.3))+'s';
      const sz=idx%3===0?14:idx%2===0?11:8;
      el.append(svgEl('0 0 24 24','hud-p-svg',
        idx%2===0
          ?'<path d="M12 2v20M2 12h20M5 5l14 14M19 5L5 19" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" opacity="0.42"/>'
          :'<circle cx="12" cy="12" r="3" fill="currentColor" opacity="0.35"/>'
      ));
      el.style.width=sz+'px';
      el.style.height=sz+'px';
      frag.append(el);
    });
  }
  return frag;
}

function buildAutumnHud(anim){
  const frag=document.createDocumentFragment();
  const leafCorner='<path d="M6 12C48 14 84 32 118 76M44 19l12-10-4 14M74 34l16-6-8 14M96 54l16-3-10 12" stroke="currentColor" stroke-width="1.1" fill="none" opacity="0.45"/>';
  frag.append(
    svgEl('0 0 130 90','hud-corner hud-tl hud-au-leaf',leafCorner),
    svgEl('0 0 130 90','hud-corner hud-tr hud-au-leaf',leafCorner)
  );
  if(anim){
    [16,38,62,84].forEach((x,i)=>{
      const el=document.createElement('div');
      el.className='hud-particle hud-au-particle';
      el.style.left=x+'%';
      el.style.animationDuration=(13+i*2)+'s';
      el.style.animationDelay=(-(i*3.4))+'s';
      el.append(svgEl('0 0 24 24','hud-p-svg','<path d="M12 3l2 4.5 4.5-1-2 4.5 4.5 2-4.5 2 1 4.5L12 17v4M12 17l-5.5 2.5 1-4.5L3 13l4.5-2-2-4.5 4.5 1z" fill="none" stroke="currentColor" stroke-width="1.2" opacity="0.38"/>'));
      frag.append(el);
    });
  }
  return frag;
}

function buildSpringHud(anim){
  const frag=document.createDocumentFragment();
  const twig='<path d="M0 16C44 18 82 28 118 54M34 20l6-12M68 29l11-11M94 40l13-7" stroke="currentColor" stroke-width="1.05" fill="none" opacity="0.42"/><circle cx="41" cy="7" r="4" fill="currentColor" opacity="0.25"/><circle cx="80" cy="17" r="4.5" fill="currentColor" opacity="0.25"/><circle cx="109" cy="32" r="3.5" fill="currentColor" opacity="0.22"/>';
  frag.append(
    svgEl('0 0 130 68','hud-corner hud-tl hud-sp-bloom',twig),
    svgEl('0 0 130 68','hud-corner hud-tr hud-sp-bloom',twig)
  );
  if(anim){
    [18,45,76].forEach((x,i)=>{
      const el=document.createElement('div');
      el.className='hud-particle hud-sp-particle';
      el.style.left=x+'%';
      el.style.animationDuration=(14+i*2.5)+'s';
      el.style.animationDelay=(-(i*4))+'s';
      el.append(svgEl('0 0 20 20','hud-p-svg','<path d="M10 2c3 3 4 7 0 11-4-4-3-8 0-11z" fill="currentColor" opacity="0.34"/>'));
      frag.append(el);
    });
  }
  return frag;
}

function buildProgrammerHud(anim){
  const frag=document.createDocumentFragment();
  const pcb='<path d="M8 8h48l14 14h32M8 22h28l10 10h20M8 38h18l16 16" stroke="currentColor" stroke-width="1" fill="none" opacity="0.4"/><circle cx="104" cy="22" r="2.5" fill="currentColor" opacity="0.5"/><circle cx="68" cy="32" r="2" fill="currentColor" opacity="0.45"/><text x="14" y="18" font-size="8" font-family="monospace" fill="currentColor" opacity="0.38">0x100</text>';
  frag.append(
    svgEl('0 0 130 66','hud-corner hud-tl hud-pr-pcb',pcb),
    svgEl('0 0 130 66','hud-corner hud-tr hud-pr-pcb',pcb)
  );
  if(anim){
    [12,52,86].forEach((x,i)=>{
      const el=document.createElement('div');
      el.className='hud-particle hud-pr-particle';
      el.style.left=x+'%';
      el.style.animationDuration=(12+i*3)+'s';
      el.style.animationDelay=(-(i*2.7))+'s';
      el.append(svgEl('0 0 24 24','hud-p-svg','<text x="2" y="15" font-size="9" font-family="monospace" fill="currentColor" opacity="0.35">01</text>'));
      frag.append(el);
    });
  }
  return frag;
}

let _lastSig='';
O.applySeasonalHud=(rootEl,opts)=>{
  const host=rootEl||document.getElementById('seasonal-hud');
  const season=O.activeSeasonKey(opts);
  const anim=(opts&&opts.seasonalAnim!==undefined)?Boolean(opts.seasonalAnim):true;
  document.documentElement.dataset.season=season;
  document.documentElement.dataset.seasonAnim=anim?'1':'0';
  if(!host)return season;
  const sig=season+':'+(anim?'1':'0');
  if(sig===_lastSig)return season;
  _lastSig=sig;
  host.innerHTML='';
  host.className='seasonal-hud'+(season!=='none'?' season-'+season:'')+(anim?' anim':'');
  if(season==='halloween')host.append(buildHalloweenHud(anim));
  else if(season==='newyear')host.append(buildNewYearHud(anim));
  else if(season==='autumn')host.append(buildAutumnHud(anim));
  else if(season==='spring')host.append(buildSpringHud(anim));
  else if(season==='programmer')host.append(buildProgrammerHud(anim));
  return season;
};
})();
