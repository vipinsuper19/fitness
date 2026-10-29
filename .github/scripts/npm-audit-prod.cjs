#!/usr/bin/env node
/**
 * Fails when backend production dependencies (npm audit --omit=dev) have a high or critical advisory
 * that is not in .github/npm-audit-allowlist.json, when an allowlist entry is past its reviewBy date,
 * or when an entry no longer matches a reported advisory (stale). Run from backend/.
 */
const { execFileSync } = require('node:child_process');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');

const allow = JSON.parse(readFileSync(join(__dirname, '..', 'npm-audit-allowlist.json'), 'utf8')).advisories;
let out;
try {
  out = execFileSync('npm', ['audit', '--omit=dev', '--json'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
} catch (e) {
  out = e.stdout; // npm audit exits non-zero when it finds anything
}
const report = JSON.parse(out);
if (report.error) throw new Error(`npm audit failed: ${JSON.stringify(report.error)}`);

const found = new Map(); // GHSA id -> { pkg, severity, title, url }
for (const [pkg, v] of Object.entries(report.vulnerabilities ?? {})) {
  for (const via of v.via) {
    if (typeof via !== 'object' || !['high', 'critical'].includes(via.severity)) continue;
    const id = String(via.url).split('/').pop();
    found.set(id, { pkg: via.name ?? pkg, severity: via.severity, title: via.title, url: via.url });
  }
}

const today = new Date().toISOString().slice(0, 10);
const problems = [];
for (const [id, a] of found) {
  const entry = allow[id];
  if (!entry) problems.push(`NOT ALLOWED ${a.severity} ${a.pkg} ${id}: ${a.title} (${a.url})`);
  else if (entry.reviewBy < today) problems.push(`EXPIRED allowlist entry ${id} (${a.pkg}), reviewBy ${entry.reviewBy}`);
  else console.log(`allowed until ${entry.reviewBy}: ${a.severity} ${a.pkg} ${id}: ${a.title}`);
}
for (const id of Object.keys(allow)) if (!found.has(id)) problems.push(`STALE allowlist entry ${id}: no longer reported; remove it`);

if (problems.length) {
  console.error(problems.join('\n'));
  process.exit(1);
}
console.log(`npm audit (production deps): no unallowed high/critical advisories (${found.size} allowlisted).`);
