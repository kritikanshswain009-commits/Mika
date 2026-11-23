'use strict';
/* The power-button sheet. Everything dynamic is escaped; buttons that carry data use data-* attributes. */
const B = window.AssistBridge || null;
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function sync(m, ...a) { try { if (!B || typeof B[m] !== 'function') return null; const r = B[m](...a); if (r === undefined || r === null || r === '') return null; return JSON.parse(r); } catch (e) { return null; } }
const pending = {}; let nextCb = 1;
function later(m, args, cb) {
  if (!B || typeof B[m] !== 'function') { if (cb) cb(null); return; }
  const id = nextCb++; pending[id] = cb;
  try { B[m](...args, id); } catch (e) { delete pending[id]; if (cb) cb(null); }
}
window.__kcb = (id, json) => { const f = pending[id]; delete pending[id]; let v = null; try { v = JSON.parse(json); } catch (e) { } if (f) f(v); };

const S = { state: 'idle', typing: false, typeMode: 'command', pc: { paired: false, online: false, route: '' }, features: { offline: [], online: [] }, spoken: false };
let pcTimer = 0;

function kami(size) {
  return `<svg width="${size}" height="${size}" viewBox="0 0 128 128" fill="none"><defs><radialGradient id="f" cx="50%" cy="68%" r="65%"><stop offset="0%" stop-color="#FFE875"/><stop offset="50%" stop-color="#FF6A00"/><stop offset="100%" stop-color="#D92200"/></radialGradient></defs>
    <path d="M65.5 12C68.5 24 61 34 55 41C51 35 50 28 52 22C36 31 23 49 23 72C23 97.5 41.5 116 64 116C86.5 116 105 97.5 105 72C105 56 97 42 87 33C88 41 84 47 79 50C81 36 75 21 65.5 12Z" fill="url(#f)"/>
    <ellipse cx="50" cy="76" rx="5.5" ry="7" fill="#1C0803"/><ellipse cx="78" cy="76" rx="5.5" ry="7" fill="#1C0803"/>
    <path id="mouth" d="M56.5 88Q64 95 71.5 88" stroke="#1C0803" stroke-width="3.5" stroke-linecap="round" fill="none"/></svg>`;
}

// The stamp, in two places on purpose. This copy lives in the page assets; the other is Build.kt and shows
// in the PC pill. They are written by different halves of an upload, so a screenshot can tell you WHICH half
// is missing: stamp here but not in the pill = your .kt files did not land; pill stamped but not here = the
// assets did not. Neither = the phone is running an older build than the one you think you installed.
const APP_BUILD = '11-22a';

function setState(state, title, sub) {
  S.state = state;
  const av = $('avatar');
  av.className = (state === 'listening' ? 'kami-listening' : 'kami-bob') + ' shrink-0';
  $('micBtn').classList.toggle('mic-live', state === 'listening');
  const t = { idle: 'How can I help?', listening: 'Listening…', thinking: 'On it…', speaking: 'Kami' }[state];
  const s = { idle: 'Tap the mic, or pick one below.', listening: 'Speak now', thinking: '', speaking: '' }[state];
  let subTxt = sub != null ? sub : s;
  if (sub == null && (state === 'idle' || state === 'listening')) subTxt += ' · app ' + APP_BUILD;
  $('stateTitle').textContent = title != null ? title : t;
  $('stateSub').textContent = subTxt;
}

// ---------------------------------------------------------------- the three quick buttons (+ PC ones)
function chipHtml(f, kind) {
  return `<button data-act="chip" data-kind="${kind}" data-id="${esc(f.id)}" class="chip flex items-center gap-1.5 px-3 py-2 rounded-2xl bg-[#1E1E25] border border-white/10 text-[12.5px] font-medium text-[#F4F4F8]"><span>${esc(f.icon)}</span><span>${esc(f.label)}</span></button>`;
}
function renderQuick() {
  const q = sync('quick');
  if (q) S.features = { offline: q.offline || [], online: q.online || [] };
  $('quickRow').innerHTML = S.features.offline.slice(0, 3).map((f) => chipHtml(f, 'offline')).join('');
  paintPcBlock();
}
function paintPcBlock() {
  const show = S.pc.online && S.features.online.length > 0;
  $('pcBlock').classList.toggle('hidden', !show);
  if (show) $('pcRow').innerHTML = S.features.online.slice(0, 3).map((f) => chipHtml(f, 'online')).join('');
}
function paintPc() {
  const p = S.pc, pill = $('pcPill'), dot = $('pcDot'), tx = $('pcText');
  if (!p.paired) { tx.textContent = 'Phone only'; pill.className = 'flex items-center gap-1.5 px-2.5 py-1 rounded-full border bg-white/5 border-white/15 text-[#888896]'; dot.className = 'w-2 h-2 rounded-full bg-[#888896]'; }
  else if (p.online) { tx.textContent = 'PC linked · ' + (p.route || ''); pill.className = 'flex items-center gap-1.5 px-2.5 py-1 rounded-full border bg-[#40E0C4]/10 border-[#40E0C4]/35 text-[#40E0C4]'; dot.className = 'w-2 h-2 rounded-full bg-[#40E0C4]'; }
  else { tx.textContent = 'PC offline · phone only'; pill.className = 'flex items-center gap-1.5 px-2.5 py-1 rounded-full border bg-[#FF5050]/10 border-[#FF5050]/30 text-[#FF8A80]'; dot.className = 'w-2 h-2 rounded-full bg-[#FF5050]'; }
  paintPcBlock();
}
function refreshPc() { later('pcStatus', [], (r) => { if (r) S.pc = r; paintPc(); }); }
function pillTap() { if (!S.pc.paired || !S.pc.online) refreshPc(); }

// ---------------------------------------------------------------- running things
function showResult(text, choices) {
  $('resultCard').classList.remove('hidden');
  $('resultText').textContent = text;
  $('resultChoices').innerHTML = (choices || []).map((c) => `<button data-act="choice" data-text="${esc(c)}" class="chip px-3 py-1.5 rounded-xl bg-[#40E0C4]/12 border border-[#40E0C4]/35 text-[#40E0C4] text-[12px] font-semibold">${esc(c)}</button>`).join('');
}
function runCmd(text, spoken, featureId) {
  const t = String(text || '').trim();
  if (!t) return;
  setState('thinking', 'On it…', t);
  later('command', [t, featureId || ''], (r) => {
    if (!r) { setState('idle'); showResult('Something went wrong. Try again.', []); return; }
    showResult(r.text, r.choices || []);
    renderQuick();
    if (spoken && B && typeof B.speak === 'function') { setState('speaking', 'Kami', ''); try { B.speak(r.text); } catch (e) { } setTimeout(() => { if (S.state === 'speaking') setState('idle'); }, 9000); }
    else setState('idle');
  });
}
function micTap() {
  if (S.state === 'listening') { sync('stopListening'); return; }
  startMic();
}
function startMic() {
  sync('stopSpeaking');
  $('typeBox').classList.add('hidden'); S.typing = false;
  setState('listening');
  sync('listen');
}
window.__partial = (t) => { if (S.state === 'listening') $('stateSub').textContent = '“' + t + '”'; };
window.__final = (t) => { runCmd(t, true, ''); };
window.__micEnd = (reason) => {
  if (S.state !== 'listening') return;
  if (reason === 'ok') return;       // the result is already on its way
  setState('idle');
  const msg = reason === 'permission' ? 'Allow the microphone when Android asks, then tap the mic.' : reason === 'unavailable' ? 'This phone has no speech recognition. You can type instead.' : 'I didn\u2019t catch that. Tap the mic to try again.';
  showResult(msg, []);
};
window.__micGranted = () => startMic();
window.__level = (db) => { const b = $('micBtn'); if (S.state === 'listening') b.style.transform = 'scale(' + (1 + Math.max(0, Math.min(1, (db + 2) / 12)) * 0.12).toFixed(3) + ')'; else b.style.transform = ''; };
window.__spoke = () => { if (S.state === 'speaking') setState('idle'); };
window.__again = () => { openSheet(true); };

// ---------------------------------------------------------------- typing
function toggleTyping(prefill, mode) {
  S.typing = !(S.typing && prefill == null);
  if (prefill != null) S.typing = true;
  S.typeMode = mode || 'command';
  $('typeBox').classList.toggle('hidden', !S.typing);
  $('typeInput').placeholder = S.typeMode === 'sendpc' ? 'Paste a link or note for your PC…' : 'Type here…';
  if (S.typing) {
    sync('stopListening');
    $('typeInput').value = prefill || '';
    setTimeout(() => { const i = $('typeInput'); i.focus(); try { i.setSelectionRange(i.value.length, i.value.length); } catch (e) { } }, 50);
  }
}
function submitTyped(e) {
  e.preventDefault();
  const v = $('typeInput').value.trim();
  if (!v) return;
  $('typeInput').value = '';
  if (S.typeMode === 'sendpc') {
    setState('thinking', 'Sending to your PC…', '');
    later('shareToPc', [v], (r) => { setState('idle'); showResult(r && r.ok ? 'Sent to your PC.' : ((r && r.error) || 'Couldn\u2019t send.'), []); renderQuick(); });
    $('typeBox').classList.add('hidden'); S.typing = false; S.typeMode = 'command';
  } else runCmd(v, false, '');
}

// ---------------------------------------------------------------- taps on buttons
function chipTap(kind, id) {
  const list = kind === 'online' ? S.features.online : S.features.offline;
  const f = list.find((x) => x.id === id);
  if (!f) return;
  if (f.mode === 'run') runCmd(f.text, false, f.id);
  else if (f.mode === 'prompt') { sync('use', f.id); toggleTyping(f.text, 'command'); }
  else if (f.mode === 'pc') { sync('use', f.id); toggleTyping('', 'command'); }   // ask here; the sheet reaches the PC itself
  else if (f.mode === 'sendpc') { toggleTyping('', 'sendpc'); }
  else if (f.mode === 'syncnow') { setState('thinking', 'Sharing calendar…', ''); later('syncCal', [], (r) => { setState('idle'); showResult((r && r.text) || 'Couldn\u2019t reach the PC.', []); renderQuick(); }); }
}
document.addEventListener('click', (e) => {
  const el = e.target.closest ? e.target.closest('[data-act]') : null;
  if (!el) return;
  if (el.dataset.act === 'chip') chipTap(el.dataset.kind, el.dataset.id);
  if (el.dataset.act === 'choice') runCmd(el.dataset.text, false, '');
});

function openKami() { sync('openKami'); }
function closeSheet() { clearInterval(pcTimer); $('sheet').classList.remove('open'); $('scrim').classList.remove('open'); $('rim').classList.remove('open'); setTimeout(() => sync('close'), 220); }
function openSheet(again) {
  $('sheet').classList.add('open'); $('scrim').classList.add('open'); $('rim').classList.add('open');
  renderQuick(); refreshPc();
  // One probe at open was the whole problem: a single slow route is not a verdict, so the pill could sit
  // on "PC offline" for the entire session while the main screen already said the PC was online. Keep
  // asking, but only while we still think it is down, so a working connection costs nothing.
  clearInterval(pcTimer);
  pcTimer = setInterval(() => { if (!S.pc.online) refreshPc(); }, 5000);
  if (again) { $('resultCard').classList.add('hidden'); }
  const want = sync('shouldListen');
  if (want) setTimeout(startMic, 250);
}
window.addEventListener('DOMContentLoaded', () => {
  $('avatar').innerHTML = kami(64);
  setTimeout(() => openSheet(false), 30);
});
