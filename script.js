/**
 * Smart Car Parking System -- script.js v4.0
 * 20 slots, Dijkstra popup confirmation, pre-filled slots 1-3 and 18-20
 */

// ── Client ID from URL ───────────────────────────────────────
const urlParams = new URLSearchParams(window.location.search);
let clientId    = urlParams.get('client') || 'CLIENT-1';

// ── Graph: Entry(0) -> 20 slots, distances in metres ─────────
const DISTANCES = [
   0,                              // node 0 = entry
  10, 15, 20, 25, 30,             // slots 1-5   row A
  35, 40, 45, 50, 55,             // slots 6-10  row B
  60, 65, 70, 75, 80,             // slots 11-15 row C
  85, 90, 95,100,105              // slots 16-20 row D
];
const TOTAL_SLOTS = 20;

// ── State ────────────────────────────────────────────────────
let slots        = Array(TOTAL_SLOTS).fill(0);
let slotOwners   = Array(TOTAL_SLOTS).fill('');
let mySlot       = -1;
let waiting      = false;
let waitPos      = -1;
let isBusy       = false;

// Popup state
let suggestedSlot = -1;
let suggestedDist = -1;
let altOptions    = [];   // [{slot, dist}, ...]

// ── Helpers ──────────────────────────────────────────────────
function ts() { return new Date().toLocaleTimeString('en-IN',{hour12:false}); }

function addLog(msg, type='') {
  const log  = document.getElementById('log-scroll');
  const line = document.createElement('div');
  line.className = `log-line ${type}`;
  line.textContent = `[${ts()}] ${msg}`;
  log.appendChild(line);
  log.scrollTop = log.scrollHeight;
}

function clearLog() {
  document.getElementById('log-scroll').innerHTML =
    '<div class="log-line info">[system] Log cleared.</div>';
}

function setResponse(msg, type='') {
  const box = document.getElementById('response-box');
  box.className = `response-box ${type}`;
  const cls = type==='success'?'response-ok':type==='error'?'response-err':
              type==='info'?'response-info':type==='warn'?'response-warn':'response-idle';
  box.innerHTML = `<span class="${cls}">${msg}</span>`;
}

function setConn(state) {
  const b = document.getElementById('conn-badge');
  const l = document.getElementById('conn-label');
  b.className = `conn-badge ${state}`;
  l.textContent = state==='online'?'Connected':state==='offline'?'Offline':'Connecting...';
}

function setButtons(busy) {
  isBusy = busy;
  document.getElementById('btn-exit').disabled = busy;
  const park = document.getElementById('btn-park');
  if (busy) {
    park.innerHTML = '<div class="spinner"></div> Processing...';
    park.disabled  = true;
    return;
  }
  if (mySlot > 0) {
    park.innerHTML = 'Already parked in Slot ' + mySlot;
    park.disabled  = true;
  } else if (waiting) {
    park.innerHTML = 'In queue — position ' + waitPos;
    park.disabled  = true;
  } else {
    park.innerHTML = `<svg width="16" height="16" viewBox="0 0 16 16" fill="none">
      <rect x="2" y="5" width="12" height="8" rx="2" fill="currentColor" opacity=".8"/>
      <rect x="4" y="3" width="8" height="4" rx="1" fill="currentColor" opacity=".5"/>
      <circle cx="5" cy="13" r="1.5" fill="white"/>
      <circle cx="11" cy="13" r="1.5" fill="white"/>
    </svg> Park Vehicle`;
    park.disabled = false;
  }
}

function updateClientDisplay() {
  document.querySelectorAll('.client-id-val').forEach(e => e.textContent = clientId);
  const el1 = document.getElementById('client-id-status');
  if (el1) el1.textContent = clientId;
  document.title = clientId + ' -- Smart Parking';

  const ms = document.getElementById('my-slot-display');
  if (ms) {
    if (mySlot > 0)      { ms.textContent = 'Slot ' + mySlot + ' (parked)'; ms.className = 'my-slot-val parked'; }
    else if (waiting)    { ms.textContent = 'Waiting — pos ' + waitPos;      ms.className = 'my-slot-val waiting'; }
    else                 { ms.textContent = 'Not parked';                     ms.className = 'my-slot-val idle'; }
  }
}

function updateStats() {
  const occ = slots.filter(s => s===1).length;
  const avEl = document.getElementById('stat-num-avail');
  const ocEl = document.getElementById('stat-num-occ');
  if (avEl) avEl.textContent = TOTAL_SLOTS - occ;
  if (ocEl) ocEl.textContent = occ;
}

function updateLastDist(d) {
  const el = document.getElementById('stat-last-dist');
  if (el) el.textContent = d >= 0 ? d + 'm' : '--';
}

// ── Render slot grid (split into 4 rows of 5) ─────────────────
function renderSlots() {
  const rows = ['a','b','c','d'];
  rows.forEach((row, ri) => {
    const grid = document.getElementById('slots-grid-' + row);
    if (!grid) return;
    grid.innerHTML = '';
    const start = ri * 5;     // slots start..start+4
    for (let i = start; i < start + 5; i++) {
      const slotNum  = i + 1;
      const occ      = slots[i] === 1;
      const isMine   = mySlot === slotNum;
      const owner    = slotOwners[i] || '';
      const isPre    = (slotNum <= 3 || slotNum >= 18) && occ && owner.startsWith('SIM-');
      const isNearest = suggestedSlot === slotNum && !occ;

      let cls = 'slot-card ';
      if (isMine)          cls += 'mine';
      else if (isPre)      cls += 'prefill';
      else if (occ)        cls += 'occupied';
      else if (isNearest)  cls += 'nearest-preview';
      else                 cls += 'free';

      const icon = isMine    ? '🔑'
                 : isPre     ? '🚙'
                 : occ       ? '🚗'
                 : isNearest ? '🔵' : '🅿️';

      const statusLabel = isMine    ? 'MY SLOT'
                        : isPre     ? 'Pre-filled'
                        : occ       ? (owner || 'Occupied')
                        : isNearest ? 'Nearest!'
                        : 'Free';

      const card = document.createElement('div');
      card.className = cls;
      card.setAttribute('role', 'button');
      card.setAttribute('tabindex', '0');
      card.title = occ
        ? `Slot ${slotNum} — ${owner || 'occupied'}`
        : `Slot ${slotNum} — Free (${DISTANCES[slotNum]}m from entry). Click to select.`;

      card.innerHTML = `
        <span class="slot-icon">${icon}</span>
        <span class="slot-num">S${slotNum}</span>
        <span class="slot-dist">${DISTANCES[slotNum]}m</span>
        <span class="slot-owner">${occ ? (owner||'') : ''}</span>
        <span class="slot-status">${statusLabel}</span>
      `;

      card.addEventListener('click', () => {
        if (isBusy) return;
        if (isMine) {
          // Click own slot -> fill exit input
          document.getElementById('exit-input').value = slotNum;
          doExit();
        } else if (!occ) {
          // Click free slot -> open popup with THIS slot pre-selected
          openModalWithChoice(slotNum);
        } else {
          setResponse(`Slot ${slotNum} is occupied by ${owner||'another client'}.`, 'warn');
        }
      });
      card.addEventListener('keydown', e => { if (e.key==='Enter') card.click(); });
      grid.appendChild(card);
    }
  });

  updateStats();
  updateClientDisplay();
  setButtons(isBusy);
  updatePathDistances();
}

// ── Show distance badges for all slots ───────────────────────
function updatePathDistances() {
  const el = document.getElementById('path-distances');
  if (!el) return;
  let html = '';
  for (let i = 1; i <= TOTAL_SLOTS; i++) {
    const occ  = slots[i-1] === 1;
    const near = suggestedSlot === i;
    let cls = occ ? 'occ-b' : near ? 'nearest-b' : 'normal-b';
    html += `<span class="dist-item"><span class="dist-badge ${cls}">S${i}:${DISTANCES[i]}m</span></span>`;
  }
  el.innerHTML = html;
}

// ── API call (always passes cid) ──────────────────────────────
async function api(path) {
  const sep = path.includes('?') ? '&' : '?';
  const res = await fetch(path + sep + 'cid=' + encodeURIComponent(clientId));
  return await res.json();
}

function applyJson(json) {
  if (json.clientId)               clientId = json.clientId;
  if (json.mySlot  !== undefined)  mySlot   = json.mySlot;
  if (json.waiting !== undefined)  waiting  = json.waiting;
  if (json.waitPos !== undefined)  waitPos  = json.waitPos;
  return json.reply || '';
}

// ── STEP 1: Park button clicked -> ask server for suggestion ──
async function doPark() {
  if (isBusy || mySlot > 0) return;
  setButtons(true);
  setResponse('Running Dijkstra algorithm...', 'info');
  addLog(`[${clientId}] Running Dijkstra...`, 'info');

  try {
    const json  = await api('/api/suggest');
    const reply = applyJson(json);
    setConn('online');
    const p = reply.split('|');

    if (p[0] === 'SUGGESTION') {
      // Parse: SUGGESTION|nearestSlot|dist|s1:d1|s2:d2|s3:d3|s4:d4|s5:d5
      suggestedSlot = parseInt(p[1]);
      suggestedDist = parseInt(p[2]);

      // Parse alternatives (all free slots from server)
      altOptions = [];
      for (let i = 3; i < p.length; i++) {
        const parts = p[i].split(':');
        if (parts.length === 2) {
          altOptions.push({ slot: parseInt(parts[0]), dist: parseInt(parts[1]) });
        }
      }

      addLog(`Dijkstra result: Slot ${suggestedSlot} = ${suggestedDist}m (shortest path)`, 'ok');
      renderSlots();   // highlight the suggested slot
      openModal();

    } else if (p[0] === 'FULL') {
      setResponse('Parking is completely full! All 20 slots occupied.', 'error');
      addLog('[X] Parking FULL', 'err');

    } else {
      setResponse('Error: ' + reply, 'error');
    }

  } catch (err) {
    setConn('offline');
    setResponse('Cannot reach server. Is ParkingClient.java running?', 'error');
    addLog('[X] ' + err.message, 'err');
  } finally {
    setButtons(false);
  }
}

// ── Open popup with Dijkstra result ───────────────────────────
function openModal() {
  document.getElementById('rec-slot-num').textContent  = 'Slot ' + suggestedSlot;
  document.getElementById('rec-slot-dist').textContent = suggestedDist + 'm from entry';
  document.getElementById('rec-path').textContent      =
    'Entry → Slot ' + suggestedSlot + ' = ' + suggestedDist + 'm (shortest path)';
  document.getElementById('modal-btn-slot').textContent = suggestedSlot;

  // Build alternative slot buttons (excluding the suggested one, show up to 4)
  const altsDiv = document.getElementById('modal-alts');
  altsDiv.innerHTML = '';
  const others = altOptions.filter(o => o.slot !== suggestedSlot).slice(0, 4);
  if (others.length === 0) {
    altsDiv.innerHTML = '<span style="font-size:.75rem;color:var(--text-3);font-family:var(--font-mono)">No other free slots available.</span>';
  } else {
    others.forEach(opt => {
      const btn = document.createElement('div');
      btn.className = 'alt-btn';
      btn.innerHTML = `<span class="ab-slot">Slot ${opt.slot}</span><span class="ab-dist">${opt.dist}m</span>`;
      btn.onclick = () => confirmParkChosen(opt.slot, opt.dist);
      altsDiv.appendChild(btn);
    });
  }

  document.getElementById('modal-overlay').classList.add('active');
}

// Open popup when user clicks a specific free slot card
function openModalWithChoice(slotNum) {
  suggestedSlot = slotNum;
  suggestedDist = DISTANCES[slotNum];
  // Still show Dijkstra's actual best as info
  openModal();
}

function closeModal() {
  document.getElementById('modal-overlay').classList.remove('active');
  suggestedSlot = -1;
  renderSlots();
}

// Close modal on overlay click (outside modal box)
document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('modal-overlay').addEventListener('click', function(e) {
    if (e.target === this) closeModal();
  });
});

// ── STEP 2a: Confirm Dijkstra's choice ────────────────────────
async function confirmParkDijkstra() {
  closeModal();
  await parkInSlot(suggestedSlot, true);
}

// ── STEP 2b: User chose a different slot ─────────────────────
async function confirmParkChosen(slot, dist) {
  closeModal();
  await parkInSlot(slot, false);
}

// ── Actually send PARK request ────────────────────────────────
async function parkInSlot(slot, isDijkstraChoice) {
  setButtons(true);
  const label = isDijkstraChoice ? `Dijkstra's choice` : `your choice`;
  addLog(`[${clientId}] Parking in Slot ${slot} (${label})...`, 'info');
  setResponse(`Parking in Slot ${slot}...`, 'info');

  try {
    // Use /api/parkslot/N to request a specific slot
    const json  = await api('/api/parkslot/' + slot);
    const reply = applyJson(json);
    setConn('online');
    const p = reply.split('|');

    if (p[0] === 'PARKED' || p[0] === 'SLOT_TAKEN_PARKED') {
      const allocSlot = parseInt(p[1]);
      const allocDist = parseInt(p[2]);
      slots[allocSlot-1]      = 1;
      slotOwners[allocSlot-1] = clientId;
      suggestedSlot = -1;
      updateLastDist(allocDist);

      if (p[0] === 'SLOT_TAKEN_PARKED') {
        setResponse(`Slot ${slot} was just taken! Auto-assigned Slot ${allocSlot} (${allocDist}m) instead.`, 'warn');
        addLog(`[WARN] Slot ${slot} taken. Auto-assigned Slot ${allocSlot}`, 'warn');
      } else {
        setResponse(`[${clientId}] Parked in Slot ${allocSlot} &nbsp;·&nbsp; ${allocDist}m from entry`, 'success');
        addLog(`[OK] ${clientId} -> Slot ${allocSlot} (${allocDist}m). ${isDijkstraChoice?'Dijkstra choice.':'User choice.'}`, 'ok');
      }

    } else if (p[0] === 'WAITING') {
      waiting = true;
      waitPos = parseInt(p[1]);
      setResponse(`Parking full! ${clientId} in wait queue at position ${waitPos}`, 'warn');
      addLog(`[WAIT] ${clientId} queued at pos ${waitPos}`, 'warn');

    } else if (p[0] === 'FULL') {
      setResponse('Parking completely full and wait queue is full too.', 'error');
      addLog(`[X] FULL`, 'err');

    } else if (p[0] === 'ALREADY_PARKED') {
      mySlot = parseInt(p[1]);
      setResponse(`Already parked in Slot ${mySlot}`, 'warn');

    } else {
      setResponse('Error: ' + reply, 'error');
      addLog('[X] ' + reply, 'err');
    }

    renderSlots();

  } catch (err) {
    setConn('offline');
    setResponse('Cannot reach server.', 'error');
    addLog('[X] ' + err.message, 'err');
  } finally {
    setButtons(false);
  }
}

// ── EXIT ──────────────────────────────────────────────────────
async function doExit() {
  if (isBusy) return;
  const input = document.getElementById('exit-input');
  const s     = parseInt(input.value);
  if (isNaN(s) || s < 1 || s > TOTAL_SLOTS) {
    setResponse('Enter a valid slot number (1–20)', 'error');
    input.focus();
    return;
  }

  setButtons(true);
  addLog(`[${clientId}] Sending EXIT ${s}...`, 'info');
  setResponse(`Exiting Slot ${s}...`, 'info');

  try {
    const json  = await api('/api/exit/' + s);
    const reply = applyJson(json);
    setConn('online');
    const p = reply.split('|');

    if (p[0] === 'FREED') {
      const slot = parseInt(p[1]);
      slots[slot-1]      = 0;
      slotOwners[slot-1] = '';
      if (mySlot === slot) mySlot = -1;
      suggestedSlot = -1;
      updateLastDist(-1);
      setResponse(`Slot ${slot} freed. Next waiting client will be auto-allocated.`, 'success');
      addLog(`[OK] ${clientId} freed Slot ${slot}`, 'ok');
      input.value = '';
    } else {
      const msg = p.slice(1).join(' ');
      setResponse('Error: ' + msg, 'error');
      addLog('[X] ' + msg, 'err');
    }
    renderSlots();

  } catch (err) {
    setConn('offline');
    setResponse('Cannot reach server.', 'error');
    addLog('[X] ' + err.message, 'err');
  } finally {
    setButtons(false);
  }
}

// ── Poll server every 3 seconds ───────────────────────────────
async function pollStatus() {
  try {
    const json  = await api('/api/status');
    setConn('online');
    const reply = json.reply || '';
    const p     = reply.split('|');

    // STATUS|s1|s2|...|s20|OWNERS|o1|o2|...|o20
    if (p[0] === 'STATUS' && p.length >= TOTAL_SLOTS + 1) {
      for (let i = 0; i < TOTAL_SLOTS; i++) slots[i] = parseInt(p[i+1]);
      const oi = p.indexOf('OWNERS');
      if (oi !== -1) {
        for (let i = 0; i < TOTAL_SLOTS; i++) {
          const o = p[oi+1+i] || 'FREE';
          slotOwners[i] = o === 'FREE' ? '' : o;
        }
      }
      // Sync mySlot
      const found = slotOwners.findIndex(o => o === clientId);
      if (found !== -1) { mySlot = found+1; waiting = false; waitPos = -1; }
    }

    if (json.mySlot  !== undefined && json.mySlot  !== -1) mySlot  = json.mySlot;
    if (json.waiting !== undefined)                         waiting = json.waiting;
    if (json.waitPos !== undefined)                         waitPos = json.waitPos;

    const qJson = await api('/api/queue');
    updateQueuePanel(qJson.reply || '');
    renderSlots();

  } catch { setConn('offline'); }
}

function updateQueuePanel(qReply) {
  const el = document.getElementById('queue-list');
  if (!el) return;
  const p = qReply.split('|');
  if (p[0] !== 'QUEUE' || p[1] === 'EMPTY' || p.length < 2) {
    el.innerHTML = '<div class="queue-empty">No clients waiting</div>';
    return;
  }
  let html = '';
  for (let i = 1; i < p.length; i++) {
    const item   = p[i];
    const isMine = item.includes(clientId);
    html += `<div class="queue-item${isMine?' queue-mine':''}">
      <span class="q-pos">${i}</span>
      <span class="q-client">${item}</span>
      ${isMine ? '<span class="q-you">YOU</span>' : ''}
    </div>`;
  }
  el.innerHTML = html;
}

// ── Init ──────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('exit-input').addEventListener('keydown', e => {
    if (e.key === 'Enter') doExit();
  });

  updateClientDisplay();
  renderSlots();
  addLog(`[init] ${clientId} ready. Slots 1-3 and 18-20 pre-filled.`, 'info');

  pollStatus();
  setInterval(pollStatus, 3000);
});
