'use strict';
/* Shared by Android (WebView) and Windows (Electron): validates AI highlight JSON locally before anything is rendered.
   Never trusts the model: every timestamp is checked against real media metadata and the real transcript. */
const SCHEMA_VERSION = 1;
const CATEGORIES = ['gaming','podcast','interview','movie','sports','vlog','tutorial','reaction','documentary','general'];

function parseAiJson(text) {
  if (typeof text !== 'string') return { ok: false, error: 'empty response' };
  const t = text.replace(/```json|```/g, '').trim();
  const s = t.indexOf('['), o = t.indexOf('{');
  const start = s >= 0 && (o < 0 || s < o) ? s : o;
  if (start < 0) return { ok: false, error: 'no JSON found' };
  try {
    const v = JSON.parse(t.slice(start, t.lastIndexOf(start === s ? ']' : '}') + 1));
    const arr = Array.isArray(v) ? v : (Array.isArray(v.candidates) ? v.candidates : null);
    return arr ? { ok: true, items: arr } : { ok: false, error: 'no candidates array' };
  } catch (e) { return { ok: false, error: 'invalid JSON: ' + e.message }; }
}

/* words: [{w,a,b}] from the local transcriber. Moves a boundary out of a spoken word to the nearest pause. */
function snapToPause(t, words, dir, maxShift) {
  const w = words.find(x => t > x.a + 0.05 && t < x.b - 0.05);
  if (!w) return { t, snapped: false };
  const cand = dir < 0 ? w.a - 0.05 : w.b + 0.1;
  return Math.abs(cand - t) <= maxShift ? { t: Math.max(0, cand), snapped: true } : { t, snapped: false, midSpeech: true };
}

function validateCandidates(items, meta, words, opt) {
  opt = Object.assign({ minLen: 8, maxLen: 60, maxShift: 2.5, overlap: 0.5, max: 10, minConf: 0.35 }, opt || {});
  words = words || [];
  const accepted = [], rejected = [];
  const rej = (it, reason) => rejected.push({ item: it, reason });
  for (const it of items) {
    if (!it || typeof it !== 'object') { rej(it, 'not an object'); continue; }
    let a = Number(it.start), b = Number(it.end);
    if (!isFinite(a) || !isFinite(b)) { rej(it, 'timestamps are not numbers'); continue; }
    if (a < 0 || b > meta.duration + 0.5 || a >= meta.duration) { rej(it, 'timestamp outside the video (' + meta.duration.toFixed(1) + 's)'); continue; }
    b = Math.min(b, meta.duration);
    if (b <= a) { rej(it, 'end is not after start'); continue; }
    const sa = snapToPause(a, words, -1, opt.maxShift), sb = snapToPause(b, words, +1, opt.maxShift);
    if (sa.midSpeech || sb.midSpeech) { rej(it, 'starts or ends in the middle of speech and no nearby pause was found'); continue; }
    a = sa.t; b = Math.min(sb.t, meta.duration);
    const len = b - a;
    if (len < opt.minLen || len > opt.maxLen) { rej(it, 'duration ' + len.toFixed(1) + 's outside ' + opt.minLen + '-' + opt.maxLen + 's'); continue; }
    const conf = Math.max(0, Math.min(1, Number(it.confidence)));
    if (!isFinite(conf) || conf < opt.minConf) { rej(it, 'confidence too low or missing'); continue; }
    accepted.push({
      start: a, end: b, duration: len, confidence: conf,
      category: CATEGORIES.includes(it.category) ? it.category : 'general',
      explanation: String(it.explanation || '').slice(0, 300), hook: String(it.hook || '').slice(0, 140),
      title: String(it.title || '').slice(0, 80), style: it.style && typeof it.style === 'object' ? it.style : {},
      scores: it.scores && typeof it.scores === 'object' ? it.scores : {}, snapped: sa.snapped || sb.snapped
    });
  }
  accepted.sort((x, y) => y.confidence - x.confidence);
  const kept = [];
  for (const c of accepted) {
    const dup = kept.find(k => Math.max(0, Math.min(k.end, c.end) - Math.max(k.start, c.start)) / Math.min(k.duration, c.duration) > opt.overlap);
    if (dup) rej(c, 'overlaps a higher-ranked clip'); else if (kept.length < opt.max) kept.push(c); else rej(c, 'over the clip limit');
  }
  return { accepted: kept, rejected, schema: SCHEMA_VERSION };
}

if (typeof module !== 'undefined') module.exports = { parseAiJson, validateCandidates, snapToPause, SCHEMA_VERSION, CATEGORIES };
