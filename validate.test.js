const assert = require('assert');
const { parseAiJson, validateCandidates } = require('../core/validate');
const meta = { duration: 300 };
const words = [{w:'so',a:19.5,b:19.9},{w:'this',a:20.0,b:20.3},{w:'is',a:20.3,b:20.5},{w:'crazy',a:20.5,b:21.2},{w:'wow',a:49.8,b:50.6},{w:'ok',a:52,b:52.3}];
let n = 0; const t = (name, f) => { f(); n++; console.log('ok -', name); };

t('parses fenced JSON', () => assert(parseAiJson('```json\n[{"start":1,"end":2}]\n```').ok));
t('rejects garbage', () => assert(!parseAiJson('sorry I cannot').ok));
t('rejects invalid JSON', () => assert(!parseAiJson('[{"start":').ok));
t('rejects out-of-range timestamps', () => {
  const r = validateCandidates([{start:290,end:340,confidence:.9},{start:400,end:430,confidence:.9}], meta, words);
  assert.equal(r.accepted.length, 0); assert.equal(r.rejected.length, 2);
});
t('rejects non-numeric and reversed', () => {
  const r = validateCandidates([{start:'a',end:5,confidence:.9},{start:60,end:50,confidence:.9}], meta, words);
  assert.equal(r.accepted.length, 0);
});
t('snaps a start that lands inside a word', () => {
  const r = validateCandidates([{start:20.4,end:45,confidence:.8,title:'x'}], meta, words);
  assert.equal(r.accepted.length, 1); assert(r.accepted[0].start <= 20.4 && r.accepted[0].snapped);
});
t('rejects mid-speech with no nearby pause', () => {
  const dense = []; for (let i=0;i<200;i++) dense.push({w:'x',a:i*.5,b:i*.5+.49});
  const r = validateCandidates([{start:10.2,end:40,confidence:.9}], meta, dense, {maxShift:.01});
  assert.equal(r.accepted.length, 0);
});
t('drops overlapping lower-confidence duplicates', () => {
  const r = validateCandidates([{start:100,end:130,confidence:.9},{start:105,end:135,confidence:.6},{start:200,end:230,confidence:.7}], meta, []);
  assert.equal(r.accepted.length, 2); assert.equal(r.accepted[0].confidence, .9);
});
t('enforces duration limits and missing confidence', () => {
  const r = validateCandidates([{start:10,end:14,confidence:.9},{start:10,end:100,confidence:.9},{start:150,end:170}], meta, []);
  assert.equal(r.accepted.length, 0); assert.equal(r.rejected.length, 3);
});
t('respects clip limit', () => {
  const items = []; for (let i=0;i<5;i++) items.push({start:i*50,end:i*50+20,confidence:.9-i*.05});
  assert.equal(validateCandidates(items, meta, [], {max:3}).accepted.length, 3);
});
console.log(n + ' tests passed');
