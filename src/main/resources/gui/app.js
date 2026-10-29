// SPDX-FileCopyrightText: 2026 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

'use strict';

const mode=document.body.dataset.mode;
const apiRoot=mode==='allowlist'?'/api/allowlist':'/api/blacklist';
const tokenFromUrl=location.hash.length>1?location.hash.slice(1):'';
if(tokenFromUrl){sessionStorage.setItem('modseekerToken',tokenFromUrl);history.replaceState(null,'',location.pathname+location.search)}
const token=sessionStorage.getItem('modseekerToken')||'';
const viewerBytes=new Uint8Array(16);
if(globalThis.crypto?.getRandomValues)crypto.getRandomValues(viewerBytes);
else for(let index=0;index<viewerBytes.length;index++)viewerBytes[index]=Math.floor(Math.random()*256);
const viewerId=Array.from(viewerBytes,value=>value.toString(16).padStart(2,'0')).join('');
const queryInput=document.getElementById('query');
const mcInput=document.getElementById('mc-version');
const searchState=document.getElementById('search-state');
const searchResults=document.getElementById('search-results');
const currentState=document.getElementById('current-state');
const currentList=document.getElementById('current-list');
const count=document.getElementById('count');
const connection=document.getElementById('connection');
const toastBox=document.getElementById('toast');
let currentIds=new Set();
let currentSlugs=new Set();
let currentHashes=new Set();
let searchTimer;
let searchSerial=0;
let searchController;
let currentLoad;
let heartbeatTimer;
let heartbeatPending=false;
let sessionOpened=false;
const thumbnailUrls=new Map();
const thumbnailLoads=new Map();

function node(tag,className,text){const value=document.createElement(tag);if(className)value.className=className;if(text!==undefined)value.textContent=text;return value}

function toast(message,error=false){toastBox.textContent=message;toastBox.className='toast visible'+(error?' error':'');clearTimeout(toastBox.timer);toastBox.timer=setTimeout(()=>toastBox.className='toast',2800)}

async function request(path,options={}){
  const headers=new Headers(options.headers||{});
  headers.set('Authorization','Bearer '+token);
  headers.set('Accept','application/json');
  if(options.body)headers.set('Content-Type','application/json');
  const response=await fetch(path,{...options,headers,credentials:'same-origin'});
  const type=response.headers.get('content-type')||'';
  const payload=type.includes('application/json')?await response.json():null;
  if(!response.ok||!payload||!payload.ok)throw new Error(payload&&payload.message?payload.message:'Request failed');
  return payload;
}

function initials(label){
  const words=(label||'?').trim().split(/\s+/).filter(Boolean);
  return (words.length>1?words[0][0]+words[1][0]:words[0]?.slice(0,2)||'?').toUpperCase();
}

function thumbnail(slug,label){
  const slot=node('div','thumb-slot');
  const fallback=node('span','thumb-fallback',initials(label));
  const image=node('img','thumb');image.alt='';
  slot.append(fallback,image);
  setThumbnail(image,slug,slot);
  return slot;
}

async function setThumbnail(image,slug,slot){
  if(!slug){slot.classList.add('missing');return}
  try{
    let url=thumbnailUrls.get(slug);
    if(!url){
      let load=thumbnailLoads.get(slug);
      if(!load){
        load=(async()=>{
          const response=await fetch('/api/icon?slug='+encodeURIComponent(slug),{headers:{Authorization:'Bearer '+token},credentials:'same-origin'});
          if(!response.ok)throw new Error();
          const created=URL.createObjectURL(await response.blob());
          thumbnailUrls.set(slug,created);
          return created;
        })().finally(()=>thumbnailLoads.delete(slug));
        thumbnailLoads.set(slug,load);
      }
      url=await load;
    }
    image.onload=()=>{slot.classList.add('loaded');slot.classList.remove('missing')};
    image.onerror=()=>{slot.classList.remove('loaded');slot.classList.add('missing')};
    image.src=url;
  }catch{slot.classList.add('missing')}
}

function setChipState(chip,className,selected){
  chip.classList.toggle(className,selected);
  chip.setAttribute('aria-pressed',String(selected));
}

function selectableVersion(label,className,selected,disabled,onChange){
  const chip=node('button','version-chip',label);
  chip.type='button';chip.disabled=disabled;
  setChipState(chip,className,selected);
  if(!disabled)chip.addEventListener('click',()=>{selected=!selected;setChipState(chip,className,selected);onChange(selected)});
  return chip;
}

function setVersionBox(box,open,button){
  box.dataset.open=String(open);
  button.setAttribute('aria-expanded',String(open));
  for(const animation of box.getAnimations())animation.cancel();
  if(window.matchMedia('(prefers-reduced-motion: reduce)').matches){box.hidden=!open;return}
  if(open){
    box.hidden=false;
    const height=box.scrollHeight;
    box.animate([
      {maxHeight:'0px',opacity:0,transform:'translateY(-4px)',overflow:'hidden'},
      {maxHeight:height+'px',opacity:1,transform:'translateY(0)',overflow:'hidden'}
    ],{duration:150,easing:'ease-out'});
    return;
  }
  if(box.hidden)return;
  const height=box.scrollHeight;
  const animation=box.animate([
    {maxHeight:height+'px',opacity:1,transform:'translateY(0)',overflow:'hidden'},
    {maxHeight:'0px',opacity:0,transform:'translateY(-4px)',overflow:'hidden'}
  ],{duration:120,easing:'ease-in'});
  animation.onfinish=()=>{if(box.dataset.open==='false')box.hidden=true};
}

function toggleVersionBox(box,button){
  setVersionBox(box,box.dataset.open!=='true',button);
}

function searchCard(hit){
  const present=currentSlugs.has(hit.slug)||currentIds.has(hit.slug);
  const selection={loaded:false,loading:null,selected:new Set(),available:0};
  const card=node('article','card');
  const image=thumbnail(hit.slug,hit.title||hit.slug);
  const content=node('div','card-content');
  content.append(node('h3','card-title',hit.title||hit.slug));
  content.append(node('div','card-meta',hit.slug));
  if(hit.description)content.append(node('div','card-description',hit.description));
  const actions=node('div','card-actions');
  const versions=node('button','button secondary','Versions');versions.type='button';versions.setAttribute('aria-expanded','false');
  const action=node('button','button','Add');action.type='button';
  const versionBox=node('div','versions');versionBox.hidden=true;
  versions.addEventListener('click',()=>loadVersions(hit.slug,versions,versionBox,action,selection,true));
  action.addEventListener('click',()=>{
    const selected=mode==='allowlist'&&selection.loaded?Array.from(selection.selected):undefined;
    return addEntry(hit.slug,action,selected);
  });
  actions.append(versions,action);
  card.append(image,content,actions,versionBox);
  if(mode==='allowlist'&&present){
    action.disabled=true;action.textContent='Checking';
    loadVersions(hit.slug,versions,versionBox,action,selection,false);
  }
  return card;
}

async function loadVersions(slug,button,box,action,selection,reveal){
  if(selection.loaded){if(reveal)toggleVersionBox(box,button);return}
  if(selection.loading){
    await selection.loading;
    if(reveal&&selection.loaded)setVersionBox(box,true,button);
    return;
  }
  const actionWasDisabled=action.disabled;
  const actionWasHidden=action.hidden;
  button.disabled=true;button.textContent='Loading';
  if(mode==='allowlist')action.disabled=true;
  selection.loading=(async()=>{
    const payload=await request('/api/versions?slug='+encodeURIComponent(slug)+'&mc='+encodeURIComponent(mcInput.value.trim()));
    box.replaceChildren();
    if(!payload.data.length)box.append(node('span','version-chip','No compatible versions'));
    if(mode==='blacklist'){
      for(const version of payload.data)box.append(node('span','version-chip',version.version+' / '+version.type));
    }else{
      for(const version of payload.data){
        const added=Boolean(version.hash)&&currentHashes.has(version.hash.toLowerCase());
        if(!added){selection.available++;selection.selected.add(version.id)}
        const chip=selectableVersion(version.version+' / '+version.type,'add-selected',!added,added,selected=>{
            selected?selection.selected.add(version.id):selection.selected.delete(version.id);
            action.disabled=selection.selected.size===0;
          });
        if(added)chip.title='Already allowed';
        box.append(chip);
      }
    }
    selection.loaded=true;
    if(mode==='allowlist'){
      action.hidden=selection.available===0;
      action.disabled=selection.selected.size===0;
      action.textContent='Add';
    }
  })();
  try{
    await selection.loading;
    if(reveal)setVersionBox(box,true,button);
  }catch(error){
    if(mode==='allowlist'){
      action.disabled=actionWasDisabled;action.hidden=actionWasHidden;action.textContent='Add';
    }
    if(reveal)toast(error.message,true);
  }finally{
    selection.loading=null;button.disabled=false;button.textContent='Versions';
  }
}

async function runSearch(){
  const serial=++searchSerial;
  if(searchController)searchController.abort();
  const query=queryInput.value.trim();
  const mc=mcInput.value.trim();
  if(!query||!mc){searchResults.replaceChildren();searchState.hidden=true;return}
  if(query.length<2){searchResults.replaceChildren();searchState.hidden=false;searchState.className='message';searchState.textContent='Type at least two characters.';return}
  const controller=new AbortController();
  searchController=controller;
  searchState.hidden=false;searchState.className='message';searchState.textContent='Searching mods';
  try{
    const payload=await request('/api/search?q='+encodeURIComponent(query)+'&mc='+encodeURIComponent(mc),{signal:controller.signal});
    if(serial!==searchSerial)return;
    if(currentLoad)await currentLoad;
    if(serial!==searchSerial)return;
    searchResults.replaceChildren();
    searchState.hidden=payload.data.length>0;
    if(!payload.data.length)searchState.textContent='No compatible mods found.';
    for(const hit of payload.data)searchResults.append(searchCard(hit));
    connection.textContent='Connected';connection.className='status online';
  }catch(error){
    if(error.name==='AbortError')return;
    if(serial!==searchSerial)return;
    searchState.hidden=false;searchState.className='message error';searchState.textContent=error.message;
    connection.textContent='Unavailable';connection.className='status offline';
  }finally{if(searchController===controller)searchController=null}
}

async function addEntry(slug,button,versionIds){
  const mcVersion=mcInput.value.trim();
  if(!mcVersion){toast('Enter a Minecraft version.',true);return}
  if(mode==='allowlist'&&versionIds&&versionIds.length===0){toast('Select at least one version.',true);return}
  button.disabled=true;button.textContent=mode==='blacklist'?'Resolving':'Adding';
  try{
    const body={slug,mcVersion};
    if(mode==='allowlist'&&versionIds)body.versionIds=versionIds;
    const payload=await request(apiRoot+'/add',{method:'POST',body:JSON.stringify(body)});
    toast(payload.message||'Updated.');
    await loadCurrent();
    await runSearch();
  }catch(error){toast(error.message,true)}
  finally{button.disabled=Boolean(mode==='allowlist'&&versionIds&&versionIds.length===0);button.textContent='Add'}
}

async function removeEntry(modId,button,hashes){
  if(mode==='allowlist'&&hashes&&hashes.length===0){toast('Select at least one version.',true);return}
  if(button){button.disabled=true;button.textContent='Removing'}
  try{
    const body={modId};
    if(mode==='allowlist'&&hashes)body.hashes=hashes;
    const payload=await request(apiRoot+'/remove',{method:'POST',body:JSON.stringify(body)});
    toast(payload.message||'Updated.');
    await loadCurrent();
    if(queryInput.value.trim())await runSearch();
  }catch(error){toast(error.message,true)}
  finally{if(button){button.disabled=Boolean(mode==='allowlist'&&hashes&&hashes.length===0);button.textContent='Remove'}}
}

function currentCard(item){
  const modId=mode==='allowlist'?item.modid:item.modId;
  const slug=mode==='allowlist'?item.modid:item.slug;
  const card=node('article','card');
  const titleText=mode==='allowlist'?(item.name&&item.name!=='unknown'?item.name:modId):modId;
  const image=thumbnail(slug,titleText);
  const content=node('div','card-content');
  const title=node('h3','card-title',titleText);
  if(mode==='allowlist'&&item.essential)title.append(node('span','badge','essential'));
  content.append(title);
  const meta=mode==='allowlist'?(item.hashCount+' hashes / '+item.versions.length+' versions / '+modId):(slug?slug:'Manual mod ID');
  content.append(node('div','card-meta',meta));
  if(mode==='allowlist')content.append(node('div','card-description',item.mcVersions.join(', ')));
  const actions=node('div','card-actions');
  const remove=node('button','button danger','Remove');remove.type='button';remove.disabled=Boolean(item.allEssential);
  if(mode==='allowlist'){
    const selection={loaded:false,selected:new Set()};
    const versions=node('button','button secondary','Versions');versions.type='button';versions.setAttribute('aria-expanded','false');
    const versionBox=node('div','versions');versionBox.hidden=true;
    versions.addEventListener('click',()=>{
      if(selection.loaded){toggleVersionBox(versionBox,versions);return}
      const counts=new Map();
      for(const entry of item.entries)counts.set(entry.version,(counts.get(entry.version)||0)+1);
      for(const entry of item.entries){
        const removable=!entry.essential;
        if(removable)selection.selected.add(entry.hash);
        const label=(entry.version||'unknown')+(counts.get(entry.version)>1&&entry.filename?' / '+entry.filename:'');
        const chip=selectableVersion(label,'remove-selected',removable,!removable,selected=>{
          selected?selection.selected.add(entry.hash):selection.selected.delete(entry.hash);
          remove.disabled=selection.selected.size===0;
        });
        if(!removable)chip.title='Essential versions cannot be removed';
        versionBox.append(chip);
      }
      selection.loaded=true;remove.disabled=selection.selected.size===0;setVersionBox(versionBox,true,versions);
    });
    remove.addEventListener('click',()=>removeEntry(modId,remove,selection.loaded?Array.from(selection.selected):undefined));
    actions.append(versions,remove);
    card.append(image,content,actions,versionBox);
  }else{
    remove.addEventListener('click',()=>removeEntry(modId,remove));
    actions.append(remove);
    card.append(image,content,actions);
  }
  return card;
}

function groupAllowlist(items){
  const groups=new Map();
  for(const item of items){
    const key=item.modid||'unknown';
    if(!groups.has(key))groups.set(key,{modid:key,name:item.name,essential:false,allEssential:true,hashCount:0,versions:[],mcVersions:[],entries:[]});
    const group=groups.get(key);
    group.entries.push(item);
    group.hashCount++;
    group.essential=group.essential||Boolean(item.essential);
    group.allEssential=group.allEssential&&Boolean(item.essential);
    if(item.version&&!group.versions.includes(item.version))group.versions.push(item.version);
    if(item.mcVersion&&!group.mcVersions.includes(item.mcVersion))group.mcVersions.push(item.mcVersion);
  }
  return Array.from(groups.values());
}

async function loadCurrent(){
  if(currentLoad)return currentLoad;
  const button=document.getElementById('refresh');
  currentLoad=(async()=>{
    button.disabled=true;button.textContent='Refreshing';
    currentState.hidden=false;currentState.className='message';currentState.textContent='Loading '+mode;
    try{
      const payload=await request(apiRoot+'/current');
      currentIds=new Set();currentSlugs=new Set();currentHashes=new Set();
      const items=payload.data||[];
      for(const item of items){
        const modId=mode==='allowlist'?item.modid:item.modId;
        if(modId)currentIds.add(modId);
        if(mode==='blacklist'&&item.slug)currentSlugs.add(item.slug);
        if(mode==='allowlist'){
          if(item.modid)currentSlugs.add(item.modid);
          if(item.hash)currentHashes.add(item.hash.toLowerCase());
        }
      }
      const displayed=mode==='allowlist'?groupAllowlist(items):items;
      const fragment=document.createDocumentFragment();
      for(const item of displayed)fragment.append(currentCard(item));
      currentList.replaceChildren(fragment);
      currentState.hidden=items.length>0;
      if(!items.length)currentState.textContent='No mods configured.';
      const unique=mode==='allowlist'?currentIds.size:items.length;
      count.textContent=mode==='allowlist'?(unique+' mods / '+items.length+' hashes'):(items.length+' mods');
      connection.textContent='Connected';connection.className='status online';
    }catch(error){
      currentState.hidden=false;currentState.className='message error';currentState.textContent=error.message;
      connection.textContent=token?'Unavailable':'Invalid link';connection.className='status offline';
    }
  })().finally(()=>{button.disabled=false;button.textContent='Refresh';currentLoad=null});
  return currentLoad;
}

function scheduleSearch(){clearTimeout(searchTimer);searchTimer=setTimeout(runSearch,500)}
queryInput.addEventListener('input',scheduleSearch);
mcInput.addEventListener('input',scheduleSearch);
for(const input of [queryInput,mcInput])input.addEventListener('keydown',event=>{if(event.key==='Enter'){clearTimeout(searchTimer);runSearch()}});
document.getElementById('refresh').addEventListener('click',loadCurrent);
async function connectSession(){
  try{
    await request('/api/session/open',{method:'POST',body:JSON.stringify({id:viewerId})});
    sessionOpened=true;
    heartbeatTimer=setInterval(async()=>{
      if(heartbeatPending)return;
      heartbeatPending=true;
      try{
        await request('/api/session/heartbeat',{method:'POST',body:JSON.stringify({id:viewerId})});
        connection.textContent='Connected';connection.className='status online';
      }catch(error){
        if(error.message==='This GUI link has expired.')clearInterval(heartbeatTimer);
        connection.textContent='Unavailable';connection.className='status offline';
      }
      finally{heartbeatPending=false}
    },10000);
    await loadCurrent();
  }catch(error){
    currentState.hidden=false;currentState.className='message error';currentState.textContent=error.message;
    connection.textContent='Invalid link';connection.className='status offline';
  }
}
window.addEventListener('pagehide',()=>{
  clearInterval(heartbeatTimer);
  if(sessionOpened)fetch('/api/session/close',{
    method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},
    body:JSON.stringify({id:viewerId}),credentials:'same-origin',keepalive:true
  }).catch(()=>{});
  for(const url of thumbnailUrls.values())URL.revokeObjectURL(url);
});
connectSession();
