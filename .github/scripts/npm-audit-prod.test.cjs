const test = require('node:test');
const assert = require('node:assert/strict');
const { evaluate } = require('./npm-audit-prod.cjs');

const adv = (name, id, severity = 'high') => ({ name, severity, title: `${name} bug`, url: `https://github.com/advisories/${id}` });
const report = (...vias) => ({
  vulnerabilities: Object.fromEntries(vias.map((v) => [v.name, { via: [v] }])),
});
const TODAY = '2026-10-29';

test('allowlisted, matching package, not expired: passes', () => {
  const r = evaluate(report(adv('multer', 'GHSA-a')), { 'GHSA-a': { package: 'multer', reviewBy: '2026-11-12' } }, TODAY);
  assert.deepEqual(r.problems, []);
  assert.equal(r.notes.length, 1);
});

test('not allowlisted high: fails', () => {
  const r = evaluate(report(adv('multer', 'GHSA-a')), {}, TODAY);
  assert.match(r.problems[0], /^NOT ALLOWED high multer GHSA-a/);
});

test('moderate/low and transitive string vias are ignored', () => {
  const rep = report(adv('js-yaml', 'GHSA-m', 'moderate'));
  rep.vulnerabilities['@nestjs/swagger'] = { via: ['js-yaml'] };
  assert.deepEqual(evaluate(rep, {}, TODAY).problems, []);
});

test('package field must match the advisory package', () => {
  const r = evaluate(report(adv('multer', 'GHSA-a')), { 'GHSA-a': { package: 'body-parser', reviewBy: '2026-11-12' } }, TODAY);
  assert.equal(r.problems.length, 1);
  assert.match(r.problems[0], /^PACKAGE MISMATCH .*"body-parser".*"multer"/);
});

test('missing package field is a mismatch', () => {
  const r = evaluate(report(adv('multer', 'GHSA-a')), { 'GHSA-a': { reviewBy: '2026-11-12' } }, TODAY);
  assert.match(r.problems[0], /^PACKAGE MISMATCH/);
});

test('expired entry fails; reviewBy today still passes', () => {
  const allow = (d) => ({ 'GHSA-a': { package: 'multer', reviewBy: d } });
  assert.match(evaluate(report(adv('multer', 'GHSA-a')), allow('2026-10-28'), TODAY).problems[0], /^EXPIRED/);
  assert.deepEqual(evaluate(report(adv('multer', 'GHSA-a')), allow(TODAY), TODAY).problems, []);
});

test('invalid or missing reviewBy fails', () => {
  const r = evaluate(report(adv('multer', 'GHSA-a')), { 'GHSA-a': { package: 'multer' } }, TODAY);
  assert.match(r.problems[0], /^INVALID/);
});

test('stale entry fails', () => {
  const r = evaluate(report(), { 'GHSA-x': { package: 'body-parser', reviewBy: '2026-11-12' } }, TODAY);
  assert.match(r.problems[0], /^STALE allowlist entry GHSA-x/);
});

test('the checked-in allowlist is well-formed', () => {
  const { advisories } = require('../npm-audit-allowlist.json');
  for (const [id, e] of Object.entries(advisories)) {
    assert.match(id, /^GHSA-/);
    assert.ok(e.package, `${id} needs package`);
    assert.match(e.reviewBy, /^\d{4}-\d{2}-\d{2}$/);
    assert.ok(e.reason, `${id} needs reason`);
  }
});
