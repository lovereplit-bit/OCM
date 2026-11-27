(function(){
const O=window.OCM;
const pend=new Map(),extPend=new Map(),mediaPend=new Map();let seq=0;const L={};
O.on=(n,f)=>{(L[n]=L[n]||[]).push(f)};
window.OCEvent=(n,d)=>{
  if(n==='http'){finishHttp(d);return}
  if(n==='exthttp'){finishExtHttp(d);return}
  if(n==='media_cached'&&d&&d.id&&mediaPend.has(d.id)){const p=mediaPend.get(d.id);mediaPend.delete(d.id);p.res(d);return}
  (L[n]||[]).forEach(f=>{try{f(d)}catch(e){console.error(n,e)}})
};
O.dir='';
// api(method, path, body, {dir:false|string, timeout, raw})
O.api=(method,path,body,opt)=>new Promise((res,rej)=>{opt=opt||{};
  const id='r'+(++seq)+'_'+Date.now().toString(36);let p=path;
  if(opt.dir!==false){const d=typeof opt.dir==='string'?opt.dir:O.dir;if(d)p+=(p.includes('?')?'&':'?')+'directory='+encodeURIComponent(d)}
  pend.set(id,{res,rej,raw:opt.raw});
  try{OC.http(id,method,p,body==null?'':JSON.stringify(body))}catch(e){pend.delete(id);rej(e);return}
  setTimeout(()=>{if(pend.has(id)){pend.delete(id);rej(Object.assign(new Error('timeout'),{net:true}))}},opt.timeout||60000)});
async function finishHttp(d){const p=pend.get(d.id);if(!p)return;pend.delete(d.id);
  if(d.err){p.rej(Object.assign(new Error(d.err),{status:0,net:true}));return}
  let text=d.body||'';
  if(d.file){try{text=await(await fetch('/_resp/'+d.id)).text()}catch(e){p.rej(e);return}finally{try{OC.drop(d.id)}catch{}}}
  let data=text;if(!p.raw&&text&&(d.ct||'').includes('json')){try{data=JSON.parse(text)}catch{}}
  if(d.status>=200&&d.status<300)p.res(data);
  else p.rej(Object.assign(new Error((data&&data.data&&data.data.message)||(data&&data.message)||(typeof data==='string'&&data.slice(0,200))||('HTTP '+d.status)),{status:d.status,data}))}
async function finishExtHttp(d){const p=extPend.get(d.id);if(!p)return;extPend.delete(d.id);
  if(d.err&&!d.status){p.rej(Object.assign(new Error(d.err),{status:0,net:true}));return}
  let text=d.body||'';
  if(d.file){try{text=await(await fetch('/_resp/'+d.id)).text()}catch(e){p.rej(e);return}finally{try{OC.drop(d.id)}catch{}}}
  let data=text;
  if(text&&((d.ct||'').includes('json')||text.trim().startsWith('{')||text.trim().startsWith('['))){try{data=JSON.parse(text)}catch{}}
  p.res({ok:Boolean(d.ok||(d.status>=200&&d.status<300)),status:d.status||0,ct:d.ct||'',text,data});
}
O.extFetch=(url,opt)=>{opt=opt||{};
  const method=(opt.method||'GET').toUpperCase();
  const headers=opt.headers||{};
  const body=opt.body==null?'':(typeof opt.body==='string'?opt.body:JSON.stringify(opt.body));
  const ms=opt.timeout||15000;
  if(window.OC&&typeof OC.extHttp==='function'){
    return new Promise((res,rej)=>{
      const id='e'+(++seq)+'_'+Date.now().toString(36);
      extPend.set(id,{res,rej});
      try{OC.extHttp(id,method,url,JSON.stringify(headers),body)}catch(e){extPend.delete(id);rej(e);return}
      setTimeout(()=>{if(extPend.has(id)){extPend.delete(id);rej(new Error('Таймаут запроса к '+url))}},ms);
    });
  }
  const ctrl=new AbortController();const t=setTimeout(()=>ctrl.abort(),ms);
  return fetch(url,{method,headers,body:(method==='POST'||method==='PUT'||method==='PATCH')?body:undefined,signal:ctrl.signal})
    .then(async r=>{clearTimeout(t);const text=await r.text();let data=text;try{data=JSON.parse(text)}catch{};return{ok:r.ok,status:r.status,ct:r.headers.get('content-type')||'',text,data}})
    .catch(e=>{clearTimeout(t);throw e});
};
// native helpers (sync, local)
const j=s=>{try{return JSON.parse(s)}catch{return null}};
O.native={
  ls:p=>j(OC.ls(p))||{err:'ls failed'},
  fs:(op,p,arg)=>j(OC.fs(op,p,arg||''))||{ok:false,err:'fs failed'},
  readText:p=>j(OC.readText(p))||{ok:false},
  writeText:(p,t)=>j(OC.writeText(p,t))||{ok:false},
  cfgRead:()=>OC.cfgRead(),cfgWrite:t=>j(OC.cfgWrite(t))||{ok:false},
  copy:t=>{try{OC.copy(t);O.toast('Скопировано',1200)}catch{}},share:t=>OC.share(t),open:u=>OC.openLink(u),
  openApp:t=>(window.OC&&OC.openApp?OC.openApp(t):false),
  preparePreview:p=>(window.OC&&OC.preparePreview?OC.preparePreview(p):''),
  notify:(kind,title,text)=>{try{if(window.OC&&OC.notify)OC.notify(kind,title,text)}catch(_){}},
  state:()=>j(OC.state())||{},
  isOnline:()=>{try{return window.OC&&OC.isOnline?Boolean(OC.isOnline()):navigator.onLine!==false}catch{return navigator.onLine!==false}},
  warpStatus:()=>j(window.OC&&OC.warpStatus?OC.warpStatus():'{}')||{mode:'direct',activeMode:'direct',status:'idle',enabled:false},
  warpRefreshGeo:()=>{try{if(window.OC&&OC.warpRefreshGeo)OC.warpRefreshGeo()}catch{}},
  warpSetConfig:cfg=>{try{if(window.OC&&OC.warpSetConfig)OC.warpSetConfig(JSON.stringify(cfg||{}))}catch{}},
  warpRotate:sid=>{try{if(window.OC&&OC.warpRotate)OC.warpRotate(sid||'')}catch{}},
  warpClearBurned:()=>j(window.OC&&OC.warpClearBurned?OC.warpClearBurned():'{}')||{},
  warpImportWg:(name,conf)=>j(window.OC&&OC.warpImportWg?OC.warpImportWg(name||'',conf||''):'{}')||{ok:false},
  warpSelectWg:id=>j(window.OC&&OC.warpSelectWg?OC.warpSelectWg(id||''):'{}')||{},
  warpDeleteWg:id=>j(window.OC&&OC.warpDeleteWg?OC.warpDeleteWg(id||''):'{}')||{},
  cacheMedia:(src,filename)=>new Promise(res=>{
    if(!window.OC||typeof OC.cacheMediaImage!=='function'){res({ok:true,url:src,name:filename||'image.png'});return}
    const id='m'+(++seq)+'_'+Date.now().toString(36);
    mediaPend.set(id,{res});
    try{OC.cacheMediaImage(id,String(src||''),String(filename||'image.png'))}catch(e){mediaPend.delete(id);res({ok:false,error:e.message});return}
    setTimeout(()=>{if(mediaPend.has(id)){mediaPend.delete(id);res({ok:false,error:'timeout'})}},25000);
  }),
  saveImage:(src,filename)=>{try{if(window.OC&&OC.saveMediaImage)OC.saveMediaImage(String(src||''),String(filename||''))}catch{}},
  shareImage:(src,filename)=>{try{if(window.OC&&OC.shareImage)OC.shareImage(String(src||''),String(filename||''))}catch{}}
};
O.openUrl=u=>{if(/^https?:\/\//i.test(u)&&!/appassets\.androidplatform\.net/i.test(u))OC.openLink(u)};
document.addEventListener('click',e=>{const a=e.target.closest&&e.target.closest('a[href]');if(!a)return;const u=a.getAttribute('href');e.preventDefault();if(/^https?:\/\//i.test(u))O.openUrl(u)});
document.addEventListener('click',e=>{const b=e.target.closest&&e.target.closest('[data-copy]');if(!b)return;const pre=b.closest('.codeblock');if(pre)O.native.copy(pre.querySelector('pre').innerText)});
})();
