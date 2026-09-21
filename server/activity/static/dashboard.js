'use strict';
const $ = id => document.getElementById(id);
const zone = 'Asia/Shanghai';
const dateKey = value => new Intl.DateTimeFormat('en-CA', {timeZone: zone, year:'numeric', month:'2-digit', day:'2-digit'}).format(value);
const formatTime = (value, seconds = false) => new Intl.DateTimeFormat('zh-CN', {timeZone:zone, hour:'2-digit', minute:'2-digit', ...(seconds ? {second:'2-digit'} : {}), hourCycle:'h23'}).format(value);
const stamp = value => value == null ? '尚无记录' : `${dateKey(value).slice(5).replace('-', '/')} ${formatTime(value)}`;
const kinds = {unlock:['解锁','UNLOCK'], lock:['锁屏','LOCK'], screen_on:['亮屏','SCREEN ON'], screen_off:['熄屏','SCREEN OFF'], reset:['采集边界重置','RESET']};
let data = null;
let selected = null;
let range = 7;
let expanded = false;
let inFlight = false;
let fetchedAt = 0;
let failed = false;

function duration(id, ms, available) {
  const target = $(id);
  target.replaceChildren();
  if (!available) { target.textContent = '—'; return; }
  const minutes = Math.floor(ms / 60000);
  if (ms > 0 && minutes === 0) { target.textContent = '<1'; const unit = document.createElement('span'); unit.className='unit'; unit.textContent='分'; target.append(unit); return; }
  const pieces = minutes >= 60 ? [[Math.floor(minutes/60),'小时'], [minutes%60,'分']] : [[minutes,'分']];
  pieces.forEach(([number, label]) => { target.append(document.createTextNode(String(number))); const unit=document.createElement('span'); unit.className='unit'; unit.textContent=label; target.append(unit); });
}

function renderStatus() {
  if (!data) return;
  const s = data.snapshot;
  const now = data.server_time + Math.max(0, Date.now() - fetchedAt);
  const stale = !s || now - s.received > 7200000;
  let label = '最近已收到上报';
  let note = '按手机设置尝试同步，后台限制可能使上报延迟。';
  if (!s) { label='尚未收到上报'; note='手机完成首次同步后，这里会留下第一笔记录。'; }
  else if (stale) { label='数据已过期'; note='超过两小时未收到上报，当前状态未知。'; }
  else if (!s.permission || s.diagnostic === 'permission_denied') { label='采集权限未开启'; note='手机未获得使用情况访问权限，活动记录可能缺失。'; }
  else if (s.diagnostic !== 'ok') {
    label='采集有缺口';
    note=({history_gap:'部分系统历史已不可用，记录可能不完整。', user_locked:'等待手机首次解锁后继续采集。', clock_changed:'手机时间发生变化，异常时间段未计入统计。', query_failed:'最近一次查询失败，等待手机重试。'})[s.diagnostic] || '记录可能不完整，请查看手机采集状态。';
  }
  if (failed) { label='刷新失败'; note='无法获取最新数据；页面保留上次成功读取的记录。'; }
  $('status').textContent=label;
  $('status').classList.toggle('warning', failed || stale || !s?.permission || s?.diagnostic !== 'ok');
  $('sync-note').textContent=note;
}

function renderSummary() {
  const now = data.server_time;
  $('issue-date').textContent=dateKey(now).replaceAll('-', ' / ');
  $('issue-weekday').textContent=new Intl.DateTimeFormat('zh-CN',{timeZone:zone,weekday:'long'}).format(now)+' · 北京时间';
  const parts = data.last_unlock == null ? ['—','—'] : formatTime(data.last_unlock).split(':');
  const colon=document.createElement('span'); colon.className='colon'; colon.textContent=':';
  $('last-time').replaceChildren(document.createTextNode(parts[0]),colon,document.createTextNode(parts[1]));
  $('last-date').textContent=data.last_unlock == null ? '尚无解锁记录' : dateKey(data.last_unlock).replaceAll('-', ' / ');
  const s=data.snapshot;
  $('battery').textContent=s?.battery ?? '—';
  $('charging').textContent=s?.charging == null ? '状态未知' : s.charging ? '充电中' : '未充电';
  $('permission').textContent=s ? (s.permission ? '已开启' : '未开启') : '尚无上报';
  $('received').textContent=stamp(s?.received);
  $('covered').textContent=stamp(s?.covered_until);
  renderStatus();
}

function renderDay() {
  const day=data.daily.find(item=>item.date===selected);
  if (!day) return;
  $('selected-date').textContent=day.date.replaceAll('-',' / ');
  $('day-picker').min=data.daily[0].date;
  $('day-picker').max=data.daily.at(-1).date;
  $('day-picker').value=selected;
  $('previous-day').disabled=selected===data.daily[0].date;
  $('next-day').disabled=selected===data.daily.at(-1).date;
  // With no observations at all, zero is not evidence of no phone use.
  const available=Boolean(data.snapshot) && (!day.incomplete || day.unlocks>0 || day.screen_ms>0 || day.unlocked_ms>0);
  $('unlocks').textContent=available ? String(day.unlocks) : '—';
  duration('screen',day.screen_ms,available); duration('unlocked',day.unlocked_ms,available);
  $('coverage-note').textContent=!data.snapshot ? '尚未收到手机数据，完成同步后即可查看。' : day.incomplete ? '此日采集范围不完整，数值仅为已记录部分；缺失不等于没有使用。' : '已查询此日截至采集时间的范围；系统仍可能漏记，时长仅计入完整区间。';
  renderEvents();
  renderWeek();
  renderMosaic(day, available);
  animateDay(day, available);
}

function chooseDay(value) {
  if(!data || !data.daily.some(day=>day.date===value)) {
    $('day-picker').value=selected || '';
    return;
  }
  selected=value;expanded=false;
  if(!data.daily.slice(-range).some(day=>day.date===selected))range=30;
  document.querySelectorAll('[data-range]').forEach(button=>button.setAttribute('aria-pressed',String(Number(button.dataset.range)===range)));
  renderDay();renderChart();
}

function renderWeek() {
  const days=data.daily.slice(-7);
  const max=Math.max(1,...days.map(day=>day.screen_ms));
  const chart=$('week-chart');chart.replaceChildren();
  const ns='http://www.w3.org/2000/svg';
  days.forEach(day=>{
    const unknown=!data.snapshot || (day.incomplete && day.screen_ms===0);
    const minutes=Math.floor(day.screen_ms/60000);
    const button=document.createElement('button');button.type='button';button.className='week-day';
    button.setAttribute('aria-pressed',String(day.date===selected));
    button.dataset.date=day.date;
    button.title=`${day.date} · ${unknown?'暂无亮屏记录':minutes+' 分钟亮屏'}${day.incomplete?' · 采集不完整':''}`;
    button.setAttribute('aria-label',button.title);
    const svg=document.createElementNS(ns,'svg');svg.setAttribute('viewBox','0 0 70 32');svg.setAttribute('preserveAspectRatio','none');svg.setAttribute('aria-hidden','true');
    const rect=document.createElementNS(ns,'rect');const height=unknown?0:day.screen_ms/max*30;
    rect.setAttribute('x','0');rect.setAttribute('width','70');rect.setAttribute('y',String(32-height));rect.setAttribute('height',String(height));rect.setAttribute('rx','2');svg.append(rect);
    const label=document.createElement('span');label.textContent=new Intl.DateTimeFormat('zh-CN',{timeZone:zone,weekday:'short'}).format(new Date(day.date+'T00:00:00+08:00')).replace('周','');
    button.append(svg,label);button.addEventListener('click',()=>{chooseDay(day.date);chart.querySelector(`[data-date="${day.date}"]`)?.focus({preventScroll:true});});chart.append(button);
  });
}

function revealSelectedDay() {
  const chart=$('chart');const scroller=chart.parentElement;
  const button=chart.querySelector('[aria-pressed="true"]');
  if(button && scroller.scrollWidth>scroller.clientWidth) {
    const offset=button.getBoundingClientRect().left-scroller.getBoundingClientRect().left;
    if(offset<0 || offset+button.offsetWidth>scroller.clientWidth)scroller.scrollLeft+=offset-(scroller.clientWidth-button.offsetWidth)/2;
  }
}

function renderChart() {
  const days=data.daily.slice(-range);
  const max=Math.max(1,...days.map(d=>d.unlocks));
  const chart=$('chart'); chart.replaceChildren(); chart.classList.toggle('month',range===30);
  const ns='http://www.w3.org/2000/svg';
  days.forEach(day=>{
    const button=document.createElement('button'); button.type='button'; button.className='day-bar'; button.setAttribute('aria-pressed',String(day.date===selected));
    const unknown=day.incomplete && day.unlocks===0;
    const description=`${day.date}，${unknown?'暂无解锁记录':day.unlocks+' 次解锁'}${day.incomplete?'，采集不完整':''}`;
    button.setAttribute('aria-label',description); button.title=description;
    const count=document.createElement('span'); count.className='bar-count'; count.textContent=unknown?'—':String(day.unlocks);
    const svg=document.createElementNS(ns,'svg'); svg.setAttribute('viewBox','0 0 40 118'); svg.setAttribute('preserveAspectRatio','none'); svg.setAttribute('aria-hidden','true'); svg.classList.add('bar-svg');
    const bar=document.createElementNS(ns,'rect'); const height=day.unlocks/max*110; bar.setAttribute('x','5'); bar.setAttribute('width','30'); bar.setAttribute('y',String(118-height)); bar.setAttribute('height',String(height)); bar.classList.add('bar-fill'); svg.append(bar);
    if(day.incomplete){const line=document.createElementNS(ns,'line'); line.setAttribute('x1','0');line.setAttribute('x2','40');line.setAttribute('y1','117');line.setAttribute('y2','117');line.classList.add('bar-base');svg.append(line);}
    const label=document.createElement('span');label.className='bar-label';label.textContent=range===7?day.date.slice(5).replace('-','/'):day.date.slice(8);
    button.append(count,svg,label);
    button.addEventListener('click',()=>{selected=day.date;expanded=false;chart.querySelectorAll('.day-bar').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));renderDay();});
    chart.append(button);
  });
  $('chart-start').textContent=days[0]?.date.slice(5).replace('-','/')??'—';
  $('chart-end').textContent=days.at(-1)?.date.slice(5).replace('-','/')??'—';
  revealSelectedDay();
}

function renderEvents() {
  const filter=$('event-filter').value;
  const events=data.timeline.filter(e=>dateKey(e.at)===selected && (filter==='all'||e.kind===filter));
  const list=$('events');list.replaceChildren();
  if (!events.length) { const empty=document.createElement('li');empty.className='empty';empty.textContent='此日期暂无符合条件的事件。较早事件可能已超出最近 100 条范围。';list.append(empty); }
  (expanded?events:events.slice(0,6)).forEach(e=>{
    const names=kinds[e.kind]||['其他事件','EVENT']; const li=document.createElement('li');li.className='event-row '+(kinds[e.kind]?e.kind:'');
    const time=document.createElement('time');time.className='event-time';time.dateTime=new Date(e.at).toISOString();time.textContent=formatTime(e.at,true);
    const name=document.createElement('span');name.className='event-kind';const dot=document.createElement('span');dot.className='event-dot';dot.setAttribute('aria-hidden','true');name.append(dot,document.createTextNode(names[0]));
    const tag=document.createElement('span');tag.className='event-tag';tag.textContent=names[1];li.append(time,name,tag);list.append(li);
  });
  $('more-events').hidden=events.length<=6;
  $('more-events').setAttribute('aria-expanded',String(expanded));
  $('more-events').textContent=expanded?'收起记录 ↑':`展开其余 ${events.length-6} 条记录 ↓`;
}

async function refresh() {
  if(inFlight)return;
  inFlight=true;
  try {
    const response=await fetch('/api/v1/dashboard',{cache:'no-store',signal:AbortSignal.timeout(15000)});
    if(!response.ok)throw new Error('HTTP '+response.status);
    const next=await response.json();
    if(!Array.isArray(next.daily)||!next.daily.length||!Array.isArray(next.timeline)||!Number.isFinite(next.server_time))throw new Error('Invalid response');
    const wasToday=!data || selected===data.daily.at(-1).date;
    data=next; fetchedAt=Date.now(); failed=false;
    if(wasToday || !data.daily.some(d=>d.date===selected))selected=data.daily.at(-1).date;
    $('load-error').hidden=true;renderSummary();renderDay();renderChart();
  } catch(error) {
    failed=true;$('load-error').hidden=false;$('load-error').textContent=data?'刷新失败，以下为上次读取的记录。连接恢复后将自动重试。':'暂时无法读取手机记录，请检查连接，稍后将自动重试。';
    if(data)renderStatus();else { $('status').textContent='读取失败';$('status').classList.add('warning');$('sync-note').textContent='连接恢复后可重新读取。';$('coverage-note').textContent='数据暂不可用。';$('events').firstElementChild.textContent='暂时无法读取事件。'; }
  } finally {inFlight=false;}
}

const reducedMotion=matchMedia('(prefers-reduced-motion: reduce)');
const desktopMotion=matchMedia('(min-width: 881px)');
const spring='cubic-bezier(.16,1,.3,1)';
const ease='cubic-bezier(.22,.61,.36,1)';
const frameJobs=new Map();
let animationSignature='';
const shortDuration=ms=>ms==null?'—':ms>0&&ms<60000?'<1分':`${Math.floor(ms/3600000)?Math.floor(ms/3600000)+'小时':''}${Math.floor(ms/60000)%60}分`;
const clockMinute=minute=>`${String(Math.floor(minute/60)).padStart(2,'0')}:${String(minute%60).padStart(2,'0')}`;
function svgNode(name, attrs) {
  const node=document.createElementNS('http://www.w3.org/2000/svg',name);
  Object.entries(attrs).forEach(([key,value])=>node.setAttribute(key,String(value)));
  return node;
}
function motion(el,frames,options={}) {
  if(!el||reducedMotion.matches)return;
  return el.animate(frames,{duration:640,easing:spring,fill:'backwards',...options});
}
function countUp(id,value,{duration:dur=1200,delay=200,isDuration=false}={}) {
  cancelAnimationFrame(frameJobs.get(id));
  if(reducedMotion.matches||value==null)return;
  const start=performance.now()+delay;
  function tick(now) {
    const p=Math.max(0,Math.min(1,(now-start)/dur));
    const n=Math.round(value*(1-Math.pow(1-p,3)));
    if(isDuration)duration(id,n,true);else $(id).textContent=String(n);
    if(p<1)frameJobs.set(id,requestAnimationFrame(tick));else frameJobs.delete(id);
  }
  frameJobs.set(id,requestAnimationFrame(tick));
}
function renderMosaic(day,available) {
  $('daily-heading').textContent=selected===data.daily.at(-1).date?'今日亮屏':'当日亮屏';
  $('day-description').textContent=available?`当日解锁 ${day.unlocks} 次、完整亮屏 ${day.sessions} 段，最长一段 ${shortDuration(day.longest_ms)}。`:'这一天尚无可用记录，等待手机完成同步。';
  const p=available&&day.elapsed_ms?Math.min(1,day.screen_ms/day.elapsed_ms):0;
  const ratio=available?`${Math.round(p*100)}%`:'—';
  const today=selected===data.daily.at(-1).date;
  const complete=data.daily.filter(d=>d.date<=selected&&!d.incomplete).slice(-7);
  const average=complete.length===7?complete.reduce((sum,d)=>sum+d.screen_ms,0)/7:null;
  const diff=average==null?null:Math.round((day.screen_ms-average)/60000);
  $('screen-note').textContent=available?`${today?'占已过时间':'占全天'} ${ratio}　·　${diff==null?'仅计完整区间':`较近 7 个完整日均值 ${diff>=0?'+':''}${diff} 分钟`}　·　单段平均 ${day.sessions?(day.screen_ms/day.sessions/60000).toFixed(1):'0'} 分钟`:'暂无完整亮屏区间，缺失不等于没有使用';
  $('screen-total').textContent=available?shortDuration(day.screen_ms):'—';
  $('unlocked').textContent=available?shortDuration(day.unlocked_ms):'—';
  $('screen-ratio').textContent=ratio;
  $('ratio-caption').textContent=today?'占已过时间':'占全天';
  $('ring-label').textContent=ratio;
  $('screen-arc').setAttribute('stroke-dashoffset',String(119.38*(1-p)));
  $('screen-ring').setAttribute('aria-label',`已记录亮屏${today?'占今日已过时间':'占全天'} ${ratio}，其余不代表锁屏时长`);
  const hours=Array.from({length:24},(_,hour)=>day.screen_bins.slice(hour*6,hour*6+6).reduce((a,b)=>a+b,0));
  const max=Math.max(...hours);
  const peak=max>0?hours.indexOf(max):-1;
  const peakLabel=peak<0?'—':`${clockMinute(peak*60)} – ${clockMinute((peak+1)*60)}`;
  $('peak-time').textContent=peakLabel;
  $('peak-note').textContent=peak<0?'尚无完整亮屏区间':`该时段亮屏 ${shortDuration(max)} · 解锁 ${day.hour_unlocks[peak]} 次${day.incomplete?' · 已记录部分':''}`;
  $('timeline-note').textContent=`每格 10 分钟 · ${peak<0?'暂无亮屏高峰':`橙色为高峰 ${peakLabel}`} · 虚线为采集缺口`;
  $('first-unlock').textContent=day.first_unlock==null?'—':formatTime(day.first_unlock);
  $('last-lock').textContent=day.last_lock==null?'未记录':formatTime(day.last_lock);
  $('wake-span').textContent=day.first_unlock!=null&&day.last_lock>=day.first_unlock?shortDuration(day.last_lock-day.first_unlock):'—';
  renderDayTimeline(day,peak);
  renderBatteryHistory(day);
}
function renderDayTimeline(day,peak) {
  const timeline=$('day-timeline');timeline.replaceChildren();$('timeline-tip').textContent='';
  timeline.setAttribute('aria-label',`${selected} 全天亮屏，每格十分钟；${day.incomplete?'采集不完整':'已覆盖采集范围'}。可触摸或移动指针查看时段。`);
  day.screen_bins.forEach((ms,index)=>{
    const future=index*600000>=day.elapsed_ms;
    const expected=Math.min(600000,Math.max(0,day.elapsed_ms-index*600000));
    const missing=day.coverage_bins[index]<expected;
    const bar=document.createElement('i');
    bar.className=future?'future':ms>0?(Math.floor(index/6)===peak?'hi':'on'):missing?'missing':'';
    const height=ms>0?Math.max(7,ms/600000*80):missing?7:3;
    bar.style.height=height+'px';bar.dataset.h=height;
    const label=`${clockMinute(index*10)}–${clockMinute((index+1)*10)} · ${future?'尚未发生':`已记录亮屏 ${shortDuration(ms)}${missing?' · 采集不完整':''}`}`;
    bar.title=label;bar.setAttribute('aria-hidden','true');
    bar.addEventListener('pointerenter',()=>{$('timeline-tip').textContent=label;});
    bar.addEventListener('click',()=>{$('timeline-tip').textContent=label;});
    timeline.append(bar);
  });
  timeline.dataset.active='0';
}
function renderBatteryHistory(day) {
  const points=(data.battery_history||[]).filter(s=>dateKey(s.captured)===day.date);
  const svg=$('battery-curve');svg.replaceChildren();
  if(!points.length){$('battery-note').textContent='所选日期暂无电量采样 · 数字为最近上报';return;}
  const midnight=new Date(day.date+'T00:00:00+08:00').getTime();
  const coords=points.map(s=>[(s.captured-midnight)/86400000*480,56-s.battery/100*50]);
  if(coords.length>1){
    svg.append(svgNode('path',{d:`M${coords[0][0]},58 `+coords.map(p=>`L${p[0]},${p[1]}`).join(' ')+` L${coords.at(-1)[0]},58 Z`,fill:'rgba(59,110,240,.12)'}));
    svg.append(svgNode('polyline',{points:coords.map(p=>p.join(',')).join(' '),fill:'none',stroke:'#3b6ef0','stroke-width':1.6,'vector-effect':'non-scaling-stroke'}));
  }
  points.forEach((s,i)=>{if(s.charging)svg.append(svgNode('rect',{x:coords[i][0],y:56,width:3,height:4,rx:1,fill:'#ffb454'}));});
  if(coords.length===1)svg.append(svgNode('circle',{cx:coords[0][0],cy:coords[0][1],r:3,fill:'#3b6ef0'}));
  const low=points.reduce((a,b)=>a.battery<=b.battery?a:b);
  $('battery-note').textContent=points.length===1?`仅 1 次采样 · ${formatTime(low.captured)} 上报 ${low.battery}%`:`采样最低 ${low.battery}% · ${formatTime(low.captured)} · ${points.length} 次上报采样`;
  svg.setAttribute('aria-label',`${day.date} 电量采样，${points.length} 个采样点；曲线仅连接实际采样，不代表连续监测`);
}
function animateDay(day,available,force=false) {
  // Background refreshes do not restart animations if values are unchanged.
  const signature=[selected,day.screen_ms,day.unlocks,data.snapshot?.battery].join(':');
  const changed=force||signature!==animationSignature;
  animationSignature=signature;
  frameJobs.forEach(cancelAnimationFrame);frameJobs.clear();
  if(!changed||reducedMotion.matches)return;
  if(available){countUp('screen',day.screen_ms,{isDuration:true});countUp('unlocks',day.unlocks,{duration:1000});}
  countUp('battery',data.snapshot?.battery,{duration:1000,delay:220});
  document.querySelectorAll('.week-day rect').forEach((el,i)=>motion(el,[{transform:'scaleY(0)'},{transform:'scaleY(1)'}],{delay:420+i*55,duration:420}));
  motion($('battery-curve'),[{clipPath:'inset(0 100% 0 0)'},{clipPath:'inset(0 0 0 0)'}],{delay:340,duration:1200,easing:ease});
  document.querySelectorAll('.ms-dots i').forEach((el,i)=>motion(el,[{height:'0px'},{height:el.dataset.h+'px'}],{delay:260+i*6,duration:280,easing:ease}));
  motion($('screen-arc'),[{strokeDashoffset:119.38},{strokeDashoffset:$('screen-arc').getAttribute('stroke-dashoffset')}],{delay:340,duration:1100,easing:ease});
  motion($('peak-time'),[{opacity:0,transform:'translateY(10px)'},{opacity:1,transform:'none'}],{delay:460,duration:440,easing:ease});
  motion($('last-time'),[{opacity:.15},{opacity:1}],{delay:520,duration:560,easing:ease});
  document.querySelectorAll('.ms-e .ms-cap,.ms-g-l div').forEach((el,i)=>motion(el,[{opacity:0,transform:'translateY(7px)'},{opacity:1,transform:'none'}],{delay:600+i*70,duration:400,easing:ease}));
}
const cells=[...document.querySelectorAll('.ms-cell')];
function revealCell(cell,i=0){motion(cell,[{opacity:0,transform:'translateY(18px) scale(.97)'},{opacity:1,transform:'none'}],{delay:60+i*85,duration:640});}
const observer=new IntersectionObserver(entries=>{
  entries.filter(e=>e.isIntersecting).forEach((entry,i)=>{revealCell(entry.target,i);observer.unobserve(entry.target);});
},{threshold:.08});
cells.forEach(cell=>observer.observe(cell));
let scrollFrame=0;
function updateParallax(){
  scrollFrame=0;
  cells.forEach(cell=>{
    if(reducedMotion.matches||!desktopMotion.matches){cell.style.removeProperty('--py');return;}
    const r=cell.getBoundingClientRect();
    const applied=parseFloat(cell.style.getPropertyValue('--py'))||0;
    const off=r.top-applied+r.height/2-innerHeight/2;
    cell.style.setProperty('--py',Math.max(-30,Math.min(30,-off*Number(cell.dataset.speed))).toFixed(2)+'px');
  });
}
function scheduleParallax(){if(!scrollFrame)scrollFrame=requestAnimationFrame(updateParallax);}
window.addEventListener('scroll',scheduleParallax,{passive:true});
window.addEventListener('resize',scheduleParallax,{passive:true});
reducedMotion.addEventListener('change',()=>{
  document.getAnimations().forEach(a=>a.cancel());
  frameJobs.forEach(cancelAnimationFrame);frameJobs.clear();
  if(data){renderSummary();renderDay();}
  scheduleParallax();
});

scheduleParallax();

document.querySelector('.timeline-scroll').addEventListener('keydown',event=>{
  if(!['ArrowLeft','ArrowRight','Home','End'].includes(event.key))return;
  event.preventDefault();
  const timeline=$('day-timeline');
  let index=Number(timeline.dataset.active||0);
  index=event.key==='Home'?0:event.key==='End'?143:Math.max(0,Math.min(143,index+(event.key==='ArrowRight'?1:-1)));
  timeline.dataset.active=String(index);
  const bar=timeline.children[index];
  if(!bar)return;
  $('timeline-tip').textContent=bar.title;
  const scroller=event.currentTarget;
  const offset=bar.getBoundingClientRect().left-scroller.getBoundingClientRect().left;
  if(offset<0||offset>scroller.clientWidth-8)scroller.scrollLeft+=offset-scroller.clientWidth/2;
});

$('day-picker').addEventListener('change',event=>chooseDay(event.target.value));
$('previous-day').addEventListener('click',()=>{if(data)chooseDay(data.daily[data.daily.findIndex(day=>day.date===selected)-1]?.date);});
$('next-day').addEventListener('click',()=>{if(data)chooseDay(data.daily[data.daily.findIndex(day=>day.date===selected)+1]?.date);});
window.addEventListener('resize',revealSelectedDay);
$('event-filter').addEventListener('change',()=>{expanded=false;if(data)renderEvents();});
$('more-events').addEventListener('click',()=>{expanded=!expanded;renderEvents();});
document.querySelectorAll('[data-range]').forEach(button=>button.addEventListener('click',()=>{
  range=Number(button.dataset.range);
  document.querySelectorAll('[data-range]').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));
  if(data){if(!data.daily.slice(-range).some(d=>d.date===selected)){selected=data.daily.at(-1).date;expanded=false;renderDay();}renderChart();}
}));
document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});
setInterval(()=>{if(!document.hidden)refresh();},60000);
setInterval(renderStatus,15000);
refresh();
