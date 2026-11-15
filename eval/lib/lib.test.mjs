// node --test eval/lib
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { redact } from './redactor.mjs';
import { ScoreFusion } from './fusion.mjs';

const load = (name) => JSON.parse(readFileSync(new URL(`../../shared/${name}`, import.meta.url), 'utf8'));

for (const v of load('redactor-vectors.json')) {
  test(`redactor: ${v.name}`, () => {
    const r = redact(v.in);
    assert.equal(r.text, v.out);
    assert.equal(r.codeSpoken, v.codeSpoken);
  });
}

for (const v of load('fusion-vectors.json')) {
  test(`fusion: ${v.name}`, () => {
    const f = new ScoreFusion(v.prior);
    v.steps.forEach((s, i) => {
      const snap = f.update(s);
      assert.equal(snap.display, s.display, `step ${i} display`);
      assert.equal(snap.band, s.band, `step ${i} band`);
    });
  });
}
