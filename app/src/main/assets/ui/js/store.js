(function(){
const O=window.OCM,api=O.api;
const bus=O.bus={h:{},on(n,f){(this.h[n]=this.h[n]||[]).push(f)},emit(n,...a){(this.h[n]||[]).forEach(f=>{try{f(...a)}catch(e){console.error(n,e)}})}};
const S=O.S={dir:'',sessions:[],childSessions:{},sid:null,status:{},unread:O.store.get('session_unread',{}),perms:[],questions:[],todos:{},diffs:{},
  providers:[],agents:[],commands:[],cfg:{},agent:'build',model:null,variant:null,mcp:{},lsp:[],branch:'',
  offline:false,up:false,gstatus:{},account:{email:null,checked:false},opts:Object.assign({thinking:true,details:true,theme:'opencode',accent:'auto',seasonalMode:'auto',seasonalAnim:true,fs:14},O.store.get('opts',{}))};
const activeWork=new Set(),seenUnreadMsgs=new Map();
const rootSid=sid=>{let cur=sid,seen=new Set();while(cur&&S.childSessions&&S.childSessions[cur]&&!seen.has(cur)){seen.add(cur);cur=S.childSessions[cur].parentID}return cur||sid};
const isViewing=sid=>!!(sid&&sid===S.sid&&(!O.getTab||O.getTab()==='chat'||innerWidth>=820)&&!document.hidden);
O.isSessionBusy=sid=>{const st=S.status&&S.status[sid];if(st&&st.type!=='idle')return true;return Object.values(S.childSessions||{}).some(c=>c.parentID===sid&&S.status&&S.status[c.id]&&S.status[c.id].type!=='idle')};
O.hasUnreadMessages=sid=>Number((S.unread&&S.unread[sid])||0);
O.markSessionUnread=(sid,msgId)=>{
  const rsid=rootSid(sid);
  if(!rsid||isViewing(rsid))return;
  if(msgId){
    let set=seenUnreadMsgs.get(rsid);
    if(!set){set=new Set();seenUnreadMsgs.set(rsid,set)}
    if(set.has(msgId))return;
    set.add(msgId);
  }else if(Number(S.unread[rsid]||0)>0){
    bus.emit('sessions');
    return;
  }
  S.unread[rsid]=Number(S.unread[rsid]||0)+1;
  O.store.set('session_unread',S.unread);
  bus.emit('sessions');
};
O.markSessionRead=sid=>{
  const rsid=rootSid(sid);
  if(!rsid)return;
  seenUnreadMsgs.delete(rsid);
  if(!S.unread||!S.unread[rsid])return;
  delete S.unread[rsid];
  O.store.set('session_unread',S.unread);
  bus.emit('sessions');
};
const THEME_ACCENTS={opencode:'#38bdf8',light:'#0284c7',tokyonight:'#7aa2f7',gruvbox:'#8ec07c',nord:'#88c0d0',catppuccin:'#89b4fa',dracula:'#8be9fd'};
const parseHex=hex=>{
  const m=/^#([0-9a-f]{6})$/i.exec(String(hex||'').trim());
  if(!m)return null;
  const n=parseInt(m[1],16);
  return{r:(n>>16)&255,g:(n>>8)&255,b:n&255,hex:'#'+m[1].toLowerCase()};
};
const mixHex=(c,target,amt)=>{
  const mix=(a,b)=>Math.max(0,Math.min(255,Math.round(a+(b-a)*amt)));
  const r=mix(c.r,target.r),g=mix(c.g,target.g),b=mix(c.b,target.b);
  return '#'+[r,g,b].map(v=>v.toString(16).padStart(2,'0')).join('');
};
const relLum=c=>{
  const ch=v=>{v/=255;return v<=0.03928?v/12.92:Math.pow((v+0.055)/1.055,2.4)};
  return 0.2126*ch(c.r)+0.7152*ch(c.g)+0.0722*ch(c.b);
};
O.resolveAccentHex=()=>{
  const raw=S.opts&&S.opts.accent;
  if(raw&&raw!=='auto'&&parseHex(raw))return parseHex(raw).hex;
  return THEME_ACCENTS[(S.opts&&S.opts.theme)||'opencode']||'#38bdf8';
};
O.applyAppearance=()=>{
  const theme=(S.opts&&S.opts.theme)||'opencode';
  const isLight=theme==='light';
  const root=document.documentElement;
  root.dataset.theme=theme;
  root.style.setProperty('--fs',((S.opts&&S.opts.fs)||14)+'px');
  const raw=parseHex(O.resolveAccentHex())||{r:56,g:189,b:248,hex:'#38bdf8'};
  const lum=relLum(raw);
  // Ensure primary fill contrast and legible accent text on both light and obsidian dark themes
  let textAcc=raw.hex;
  if(isLight&&lum>0.42)textAcc=mixHex(raw,{r:15,g:23,b:42},0.48);
  else if(!isLight&&lum<0.18)textAcc=mixHex(raw,{r:226,g:232,b:240},0.55);
  const priFg=lum>0.36?'#0d0d0f':'#f4f4f5';
  root.style.setProperty('--acc',textAcc);
  root.style.setProperty('--accent',textAcc);
  root.style.setProperty('--pri',raw.hex);
  root.style.setProperty('--pri-fg',priFg);
  root.style.setProperty('--acc-soft',`rgba(${raw.r},${raw.g},${raw.b},${isLight?0.12:0.14})`);
  root.style.setProperty('--acc-border',`rgba(${raw.r},${raw.g},${raw.b},${isLight?0.34:0.32})`);
  root.style.setProperty('--acc-ring',`rgba(${raw.r},${raw.g},${raw.b},0.28)`);
  if(O.applySeasonalHud)O.applySeasonalHud(null,S.opts);
};
O.saveOpts=()=>{O.store.set('opts',S.opts);O.applyAppearance()};
O.isOfflineMode=()=>Boolean(O.store.get('offline_mode',false)||(O.native.isOnline&&!O.native.isOnline())||S.offline);
O.setOfflineMode=(on,quiet)=>{
  const val=Boolean(on);
  O.store.set('offline_mode',val);
  try{if(window.OC&&OC.setFlag)OC.setFlag('offline_mode',val)}catch(_){}
  O.pickDefaults();
  bus.emit('conn');
  bus.emit('meta');
  if(!quiet)O.toast(val?(O.tr('Offline mode enabled — prioritizing local GGUF models','Офлайн-режим включён — приоритет локальных моделей')):(O.tr('Online mode restored','Сетевой режим восстановлен')),2200);
};
window.addEventListener('online',()=>bus.emit('conn'));
window.addEventListener('offline',()=>bus.emit('conn'));

// ---- IndexedDB key/value cache (offline reading) ----
let dbp;const db=()=>dbp||(dbp=new Promise((res,rej)=>{const r=indexedDB.open('ocm',1);r.onupgradeneeded=()=>r.result.createObjectStore('kv');r.onsuccess=()=>res(r.result);r.onerror=()=>rej(r.error)}));
O.kv={async get(k){try{const d=await db();return await new Promise(res=>{const q=d.transaction('kv').objectStore('kv').get(k);q.onsuccess=()=>res(q.result);q.onerror=()=>res(undefined)})}catch{return undefined}},
  async set(k,v){try{const d=await db();await new Promise(res=>{const t=d.transaction('kv','readwrite');t.objectStore('kv').put(v,k);t.oncomplete=res;t.onerror=res;t.onabort=res})}catch{}}};

// ---- message store ----
const M=new Map();
O.msgs=sid=>{let x=M.get(sid);if(!x){x={list:[],idx:new Map()};M.set(sid,x)}return x};
const cmp=(a,b)=>a.info.id<b.info.id?-1:a.info.id>b.info.id?1:0;
O.upsertMsg=info=>{const s=O.msgs(info.sessionID);let m=s.idx.get(info.id);
  if(m)m.info=info;else{m={info,parts:[]};s.idx.set(info.id,m);s.list.push(m);if(s.list.length>1&&cmp(s.list[s.list.length-2],m)>0)s.list.sort(cmp)}return m};
O.upsertPart=part=>{const s=O.msgs(part.sessionID);let m=s.idx.get(part.messageID);
  if(!m)m=O.upsertMsg({id:part.messageID,sessionID:part.sessionID,role:'assistant',time:{created:Date.now()}});
  if(m.local){m.parts=[];m.local=false}
  const i=m.parts.findIndex(p=>p.id===part.id);
  if(i>=0)m.parts[i]=part;else{m.parts.push(part);if(m.parts.length>1&&m.parts[m.parts.length-2].id>part.id)m.parts.sort((a,b)=>a.id<b.id?-1:1)}return m};
O.visibleMsgs=sid=>{const s=O.msgs(sid),ses=S.sessions.find(x=>x.id===sid)||O.child(sid),rv=ses&&ses.revert&&ses.revert.messageID;
  return rv?s.list.filter(m=>m.info.id<rv):s.list};
O.child=sid=>S.childSessions&&S.childSessions[sid];
const saveMsgs=O.debounce(sid=>{const l=O.msgs(sid).list.slice(-250).map(m=>({info:m.info,parts:m.parts}));O.kv.set('msgs:'+sid,l)},2000);

// ---- server events ----
let liveSyncTimer=null;
O.syncSessionMessages=async(sid)=>{
  if(!sid||sid!==S.sid)return;
  try{
    const arr=await api('GET','/session/'+sid+'/message',null,{timeout:15000});
    if(!Array.isArray(arr))return;
    for(const m of arr){
      O.upsertMsg(m.info);
      if(Array.isArray(m.parts)){
        for(const pt of m.parts) O.upsertPart(pt);
      }
    }
    bus.emit('msg',sid);
    saveMsgs(sid);
  }catch(e){}
};
O.startLiveSync=(sid)=>{
  if(liveSyncTimer)return;
  liveSyncTimer=setInterval(async()=>{
    if(!S.sid){O.stopLiveSync();return}
    try{
      const stMap=await api('GET','/session/status',null,{timeout:4000});
      if(stMap){
        for(const k in stMap)S.status[k]=stMap[k];
        if(!stMap[S.sid]||stMap[S.sid].type==='idle'){
          S.status[S.sid]={type:'idle'};
          bus.emit('status',S.sid);
          O.stopLiveSync();
          O.syncSessionMessages(S.sid);
          return;
        }
      }
    }catch(_){}
    const st=S.status[S.sid];
    if(!st||st.type==='idle'){O.stopLiveSync();return}
    O.syncSessionMessages(S.sid);
  },3000);
};
O.stopLiveSync=()=>{
  if(liveSyncTimer){clearInterval(liveSyncTimer);liveSyncTimer=null;}
};

O.onServerEvent=raw=>{let o;try{o=JSON.parse(raw)}catch{return}
  const ev=o.payload||o,p=ev.properties||{};
  const targetSid=p.sessionID||(p.info&&p.info.sessionID)||(p.part&&p.part.sessionID);
  if(o.directory&&o.directory!=='global'&&S.dir&&o.directory!==S.dir&&targetSid!==S.sid&&!/^(permission|question)\./.test(ev.type))return;
  switch(ev.type){
  case'server.connected':S.up=true;bus.emit('conn');break;
  case'session.created':case'session.updated':{const i=p.info;if(!i)break;
    if(i.parentID){(S.childSessions=S.childSessions||{})[i.id]=i;break}
    const k=S.sessions.findIndex(x=>x.id===i.id);if(k>=0)S.sessions[k]=i;else S.sessions.unshift(i);
    S.sessions.sort((a,b)=>b.time.updated-a.time.updated);bus.emit('sessions');if(i.id===S.sid)bus.emit('session');break}
  case'session.deleted':{const did=p.info&&p.info.id;S.sessions=S.sessions.filter(x=>x.id!==did);if(did&&S.unread&&S.unread[did]){delete S.unread[did];O.store.set('session_unread',S.unread)}bus.emit('sessions');if(did===S.sid){S.sid=null;bus.emit('open',null)}break}
  case'session.status':
    S.status[p.sessionID]=p.status;
    if(p.status&&p.status.type!=='idle')activeWork.add(p.sessionID);
    bus.emit('status',p.sessionID);
    if(p.sessionID===S.sid&&p.status&&p.status.type!=='idle')O.startLiveSync(p.sessionID);
    break;
  case'session.idle':{
    S.status[p.sessionID]={type:'idle'};
    if(activeWork.has(p.sessionID)){activeWork.delete(p.sessionID);O.markSessionUnread(p.sessionID)}
    bus.emit('status',p.sessionID);bus.emit('idle',p.sessionID);
    if(p.sessionID===S.sid){O.stopLiveSync();O.syncSessionMessages(p.sessionID)}
    break}
  case'session.error':bus.emit('serror',p);break;
  case'session.diff':S.diffs[p.sessionID]=p.diff||[];bus.emit('diff',p.sessionID);break;
  case'message.updated':{const m=O.upsertMsg(p.info);if(p.info&&p.info.role==='assistant')O.markSessionUnread(p.info.sessionID,p.info.id);bus.emit('msg',p.info.sessionID,m);saveMsgs(p.info.sessionID);break}
  case'message.removed':{const s=O.msgs(p.sessionID);s.list=s.list.filter(m=>m.info.id!==p.messageID);s.idx.delete(p.messageID);bus.emit('msg-removed',p.sessionID,p.messageID);break}
  case'message.part.updated':{const m=O.upsertPart(p.part);if(m&&m.info&&m.info.role==='assistant')O.markSessionUnread(p.part.sessionID,m.info.id);bus.emit('part',p.part.sessionID,m,p.part);saveMsgs(p.part.sessionID);break}
  case'message.part.delta':{
    const s=O.msgs(p.sessionID);let m=s.idx.get(p.messageID);
    if(!m)m=O.upsertMsg({id:p.messageID,sessionID:p.sessionID,role:'assistant',time:{created:Date.now()}});
    let pt=m.parts.find(x=>x.id===p.partID);
    const isThink=(p.field==='reasoning'||p.field==='thinking'||(pt&&pt.type==='reasoning'));
    if(!pt){
      pt={id:p.partID,sessionID:p.sessionID,messageID:p.messageID,type:isThink?'reasoning':'text',text:'',time:{start:Date.now()}};
      if(p.field)pt[p.field]='';
      m.parts.push(pt);
    }
    const f=p.field||'text';
    pt[f]=(pt[f]||'')+p.delta;
    if(isThink){
      pt.type='reasoning';
      if(!pt.text||pt.text!==pt[f])pt.text=pt[f];
    }
    if(m.info&&m.info.role==='assistant')O.markSessionUnread(p.sessionID,m.info.id);
    bus.emit('part',p.sessionID,m,pt);break}
  case'message.part.removed':{const m=O.msgs(p.sessionID).idx.get(p.messageID);if(m){m.parts=m.parts.filter(x=>x.id!==p.partID);bus.emit('part-removed',p.sessionID,m,p.partID)}break}
  case'permission.updated':case'permission.asked':{const q=normPerm(p);if(!S.perms.some(x=>x.id===q.id))S.perms.push(q);bus.emit('perm');break}
  case'permission.replied':{const id=p.permissionID||p.requestID||p.id;S.perms=S.perms.filter(x=>x.id!==id);bus.emit('perm');break}
  case'question.asked':{if(!S.questions.some(x=>x.id===p.id))S.questions.push(p);bus.emit('perm');break}
  case'question.replied':case'question.rejected':{const id=p.requestID||p.id;S.questions=S.questions.filter(x=>x.id!==id);bus.emit('perm');break}
  case'todo.updated':S.todos[p.sessionID]=p.todos||[];bus.emit('todo',p.sessionID);break;
  case'file.edited':case'file.watcher.updated':bus.emit('files');break;
  case'vcs.branch.updated':S.branch=p.branch||'';bus.emit('vcs');break;
  case'mcp.tools.changed':case'lsp.updated':bus.emit('statuspanel');break;
  case'pty.exited':case'pty.deleted':bus.emit('pty',p);break}};
const normPerm=p=>({id:p.id,sessionID:p.sessionID,title:p.title||p.permission||p.type||'Разрешение',patterns:[].concat(p.patterns||p.pattern||[]),metadata:p.metadata||{},tool:p.tool,raw:p});

// Brief SSE drops (app frozen in background for a second) must not flash the reconnect banner:
// delay it, cancel when the stream comes back.
let upT=null;
O.on('sse',d=>{if(d.t==='data')O.onServerEvent(d.d);else if(d.t==='open'){if(upT){clearTimeout(upT);upT=null}S.up=true;bus.emit('conn')}
  else if(d.t==='close'){S.up=false;if(!upT)upT=setTimeout(()=>{upT=null;if(!S.up)bus.emit('conn')},5000)}});
document.addEventListener('visibilitychange',()=>{if(!document.hidden){if(!S.up){try{OC.sseStart()}catch(e){}}if(isViewing(S.sid))O.markSessionRead(S.sid)}});

// ---- opencode account (Zen) status: known once checked, so hints never nag after a login ----
O.checkAccount=async()=>{try{const r=await api('GET','/experimental/console/orgs',null,{timeout:10000});
  const em=r&&r.orgs&&r.orgs.length?[...new Set(r.orgs.map(o=>o.accountEmail))][0]:null;
  S.account={email:em||null,checked:true};bus.emit('meta')}catch{S.account.checked=true}};

// ---- loaders (network first, IndexedDB fallback) ----
O.loadMeta=async()=>{
  const [cp,ag,cm,cf,pr,br]=await Promise.allSettled([api('GET','/config/providers'),api('GET','/agent'),api('GET','/command'),api('GET','/config'),api('GET','/project',null,{dir:false}),api('GET','/vcs')]);
  if(cp.status==='fulfilled'){S.providers=cp.value.providers||[];S.defaults=cp.value.default||{};O.kv.set('providers',cp.value)}
  else{const c=await O.kv.get('providers');if(c){S.providers=c.providers||[];S.defaults=c.default||{}}}
  if(ag.status==='fulfilled'){S.agents=ag.value;O.kv.set('agents',ag.value)}else S.agents=(await O.kv.get('agents'))||[];
  if(cm.status==='fulfilled'){S.commands=cm.value;O.kv.set('commands',cm.value)}else S.commands=(await O.kv.get('commands'))||[];
  if(cf.status==='fulfilled'){S.cfg=cf.value;O.kv.set('cfg',cf.value)}else S.cfg=(await O.kv.get('cfg'))||{};
  S.projects=pr.status==='fulfilled'?pr.value:((await O.kv.get('projects'))||[]);
  if(pr.status==='fulfilled')O.kv.set('projects',pr.value);
  if(br.status==='fulfilled')S.branch=br.value&&br.value.branch||'';
  O.pickDefaults();bus.emit('meta')};
O.customProviderIds=()=>{
  try{
    const raw=O.native&&O.native.cfgRead?O.native.cfgRead():'';
    if(!raw||!raw.trim())return new Set();
    const obj=O.parseJsonc(raw);
    const prov=obj&&obj.provider?obj.provider:{};
    return new Set(Object.keys(prov).filter(k=>k!=='local'));
  }catch(_){return new Set()}
};
O.isModelFree=x=>{
  if(!x)return false;
  if(x.isLocal || x.pid==='local')return true;
  if(x.free===true)return true;
  const m=x.m||x;
  if(m.free===true || m.local===true)return true;
  const c=m.cost;
  if(c&&typeof c.input==='number'&&typeof c.output==='number'){
    if(c.input===0&&c.output===0)return true;
    if(c.input>0||c.output>0)return false;
  }
  const str=((x.name||'')+' '+(x.mid||'')+' '+(x.pid||'')).toLowerCase();
  if(/\bfree\b|бесплатн|локальн/i.test(str))return true;
  if(x.pid==='opencode'&&(!c||(!c.input&&!c.output)))return true;
  return false;
};
O.primaryAgents=()=>S.agents.filter(a=>a.mode!=='subagent'&&!a.hidden);
O.modelInfo=(pid,mid)=>{
  const p=S.providers.find(x=>x.id===pid);
  if(p&&p.models&&p.models[mid])return p.models[mid];
  if(pid==='local'){
    try{
      const locals=O.native.getLocalModels?O.native.getLocalModels():[];
      const f=locals.find(x=>x.id===mid);
      if(f&&f.downloaded){
        const displayName=f.name.includes('(Локально)')?f.name:(f.name+' (Локально)');
        return {id:f.id,name:displayName,free:true,local:true,attachment:f.type==='image'||f.type==='vlm',limit:{context:f.type==='image'?4096:32768}};
      }
    }catch(_){}
  }
  return null;
};
O.allModels=()=>{
  const locals=(O.native.getLocalModels?O.native.getLocalModels():[]).filter(x=>x.downloaded);
  const downloadedIds=new Set(locals.map(x=>x.id));
  const customIds=O.customProviderIds();

  const list=S.providers.flatMap(p=>{
    if(p.id==='local'){
      // Never show ghost or un-downloaded models in the active picker
      return Object.values(p.models||{}).filter(m=>downloadedIds.has(m.id)).map(m=>({
        pid:p.id,
        pname:p.name||'Локально (GGUF)',
        mid:m.id,
        name:m.name||m.id,
        isLocal:true,
        m
      }));
    }
    const isCustom=customIds.has(p.id)&&p.id!=='opencode';
    return Object.values(p.models||{}).map(m=>({pid:p.id,pname:p.name,mid:m.id,name:m.name||m.id,isCustom,m}));
  });

  try{
    locals.forEach(loc=>{
      const displayName=loc.name.includes('(Локально)')?loc.name:(loc.name+' (Локально)');
      const exists=list.find(m=>m.pid==='local'&&m.mid===loc.id);
      if(exists){
        exists.name=displayName;
        exists.isLocal=true;
        exists.m=exists.m||{};
        exists.m.free=true;
        exists.m.local=true;
      }else{
        list.unshift({
          pid:'local',
          pname:'Локально (GGUF)',
          mid:loc.id,
          name:displayName,
          isLocal:true,
          m:{
            id:loc.id,
            name:displayName,
            free:true,
            local:true,
            attachment:loc.type==='image'||loc.type==='vlm',
            limit:{context:loc.type==='image'?4096:32768}
          }
        });
      }
    });
  }catch(_){}

  return list;
};
O.pickDefaults=()=>{
  const prim=O.primaryAgents();if(!prim.some(a=>a.name===S.agent))S.agent=(prim[0]&&prim[0].name)||'build';
  const ok=m=>m&&O.modelInfo(m.providerID,m.modelID);
  const preferLocal=O.isOfflineMode();
  let m=null;
  if(preferLocal){
    const allLoc=O.allModels().filter(x=>x.isLocal||x.pid==='local');
    if(allLoc.length){
      const curOk=S.sid&&ok(O.store.get('session.model.'+S.sid))&&O.store.get('session.model.'+S.sid).providerID==='local';
      m=curOk?O.store.get('session.model.'+S.sid):{providerID:'local',modelID:allLoc[0].mid};
    }
  }
  if(!m&&S.sid){
    const sesSaved=O.store.get('session.model.'+S.sid);
    if(ok(sesSaved))m=sesSaved;
  }
  if(!m){
    const saved=O.store.get('model.'+S.agent);
    if(ok(saved))m=saved;
  }
  const ag=S.agents.find(a=>a.name===S.agent);
  if(!m&&ag&&ag.model&&ok(ag.model))m=ag.model;
  if(!m&&typeof S.cfg.model==='string'&&S.cfg.model.includes('/')){const[i,...r]=S.cfg.model.split('/');const c={providerID:i,modelID:r.join('/')};if(ok(c))m=c}
  if(!m){
    const all=O.allModels();
    const onlyFree=O.store.get('onlyFreeModels',true);
    let d=null;
    if(onlyFree){
      d=all.find(x=>O.isModelFree(x)&&S.defaults[x.pid]===x.mid)||all.find(x=>O.isModelFree(x)||x.isCustom);
    }
    if(!d)d=all.find(x=>S.defaults[x.pid]===x.mid)||all[0];
    if(d)m={providerID:d.pid,modelID:d.mid};
  }
  S.model=m||null;
  let v=null;
  if(S.sid){
    const sesV=O.store.get('session.variant.'+S.sid);
    if(sesV&&m&&O.modelInfo(m.providerID,m.modelID)&&O.modelInfo(m.providerID,m.modelID).variants&&O.modelInfo(m.providerID,m.modelID).variants[sesV])v=sesV;
  }
  if(!v){
    const globalV=O.store.get('variant.'+(m?m.providerID+'/'+m.modelID:''));
    if(globalV&&m&&O.modelInfo(m.providerID,m.modelID)&&O.modelInfo(m.providerID,m.modelID).variants&&O.modelInfo(m.providerID,m.modelID).variants[globalV])v=globalV;
  }
  S.variant=v;
};
O.setModel=(pid,mid)=>{
  S.model={providerID:pid,modelID:mid};
  O.store.set('model.'+S.agent,S.model);
  if(S.sid)O.store.set('session.model.'+S.sid,S.model);
  O.pickDefaults();
  if(O.updateReadmeForModel) O.updateReadmeForModel(mid);
  bus.emit('meta');
};
O.setAgent=n=>{S.agent=n;O.pickDefaults();bus.emit('meta')};
O.setVariant=v=>{
  S.variant=v;
  if(S.model)O.store.set('variant.'+S.model.providerID+'/'+S.model.modelID,v);
  if(S.sid)O.store.set('session.variant.'+S.sid,v);
  bus.emit('meta');
};

O.loadSessions=async()=>{
  try{const l=await api('GET','/session');S.sessions=l.filter(s=>!s.parentID).sort((a,b)=>b.time.updated-a.time.updated);S.offline=false;O.kv.set('sessions:'+S.dir,S.sessions)}
  catch(e){const c=await O.kv.get('sessions:'+S.dir);if(c)S.sessions=c;S.offline=true}
  try{const st=await api('GET','/session/status');if(st&&typeof st==='object'){S.status={};for(const k in st)S.status[k]=st[k]}}catch{}
  bus.emit('sessions')};
O.loadPending=async()=>{
  try{const p=await api('GET','/permission');S.perms=(p||[]).map(normPerm)}catch{}
  try{const q=await api('GET','/question');S.questions=q||[]}catch{}
  bus.emit('perm')};
O.openSession=async(sid,fresh)=>{
  S.sid=sid;O.store.set('sid.'+S.dir,sid);
  if(sid)O.markSessionRead(sid);
  if(!sid){bus.emit('open',null);return}
  const ok=m=>m&&O.modelInfo(m.providerID,m.modelID);
  const sesSaved=O.store.get('session.model.'+sid);
  if(ok(sesSaved)){
    S.model=sesSaved;
    S.variant=O.store.get('session.variant.'+sid)||null;
  }else{
    const s0=O.msgs(sid);
    const lastM=[...s0.list].reverse().find(x=>x.info&&x.info.providerID&&x.info.modelID);
    if(lastM&&ok({providerID:lastM.info.providerID,modelID:lastM.info.modelID})){
      S.model={providerID:lastM.info.providerID,modelID:lastM.info.modelID};
      O.store.set('session.model.'+sid,S.model);
    }else{
      O.pickDefaults();
    }
  }
  bus.emit('meta');
  const s=O.msgs(sid);
  if(!s.list.length){const c=await O.kv.get('msgs:'+sid);if(c)c.forEach(m=>{s.list.push(m);s.idx.set(m.info.id,m)})}
  bus.emit('open',sid);
  if(fresh)return;
  try{const arr=await api('GET','/session/'+sid+'/message',null,{timeout:120000});
    s.list=[];s.idx=new Map();for(const m of arr){const x={info:m.info,parts:m.parts};s.list.push(x);s.idx.set(m.info.id,x)}
    if(!sesSaved){
      const lastRemote=[...s.list].reverse().find(x=>x.info&&x.info.providerID&&x.info.modelID);
      if(lastRemote&&ok({providerID:lastRemote.info.providerID,modelID:lastRemote.info.modelID})){
        S.model={providerID:lastRemote.info.providerID,modelID:lastRemote.info.modelID};
        O.store.set('session.model.'+sid,S.model);
        bus.emit('meta');
      }
    }
    S.offline=false;bus.emit('open',sid);saveMsgs(sid)}catch(e){S.offline=true;bus.emit('conn')}
  api('GET','/session/'+sid+'/todo').then(t=>{S.todos[sid]=t;bus.emit('todo',sid)}).catch(()=>{});
  api('GET','/session/'+sid+'/diff').then(d=>{S.diffs[sid]=d;bus.emit('diff',sid)}).catch(()=>{});
  O.loadPending()};
O.newSession=async()=>{const s=await api('POST','/session',{});if(!S.sessions.some(x=>x.id===s.id))S.sessions.unshift(s);bus.emit('sessions');await O.openSession(s.id,true);return s};
O.setDir=async(dir)=>{
  S.dir=dir;O.dir=dir;O.store.set('dir',dir);
  const rec=O.store.get('recent',[]).filter(x=>x!==dir);rec.unshift(dir);O.store.set('recent',rec.slice(0,12));
  S.sessions=[];S.sid=null;S.perms=[];S.questions=[];S.diffs={};S.todos={};bus.emit('dir');
  await Promise.all([O.loadMeta(),O.loadSessions()]);
  const last=O.store.get('sid.'+dir);const pick=S.sessions.find(x=>x.id===last)||S.sessions[0];
  await O.openSession(pick?pick.id:null);bus.emit('files')};
O.sessionTitle=s=>s?(s.title&&!/^(New session|Child session|Новая сессия) - /.test(s.title)?s.title:'Новая сессия'):'OpenCode';
O.contextUsage=sid=>{const l=O.visibleMsgs(sid);for(let i=l.length-1;i>=0;i--){const x=l[i].info;if(x.role==='assistant'&&x.tokens&&(x.tokens.input||x.tokens.output)){
  const t=x.tokens,tot=(t.input||0)+(t.output||0)+(t.reasoning||0)+((t.cache&&t.cache.read)||0)+((t.cache&&t.cache.write)||0);
  const mi=O.modelInfo(x.providerID,x.modelID),lim=mi&&mi.limit&&mi.limit.context;return{tot,pct:lim?Math.round(tot*100/lim):null}}}return null};
O.replyPermission=async(p,r)=>{
  const tries=[()=>api('POST','/permission/'+p.id+'/reply',{reply:r}),()=>api('POST','/session/'+p.sessionID+'/permissions/'+p.id,{response:r}),()=>api('POST','/api/session/'+p.sessionID+'/permission/'+p.id+'/reply',{reply:r})];
  for(const t of tries){try{await t();S.perms=S.perms.filter(x=>x.id!==p.id);bus.emit('perm');return}catch(e){if(e.status!==404&&e.status!==405&&e.status!==400)throw e}}
  throw new Error('Ответ на разрешение не поддерживается этой версией сервера')};
})();
