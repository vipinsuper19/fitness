#!/usr/bin/env node
/**
 * Fails when backend production dependencies (npm audit --omit=dev) have a high or critical advisory
 * that is not in .github/npm-audit-allowlist.json, or when an allowlist entry:
 *   - is past its reviewBy date (EXPIRED), or has no valid reviewBy date,
 *   - names a different package than the advisory npm reports (PACKAGE MISMATCH),
 *   - no longer matches a reported advisory (STALE).
 * Run from backend/. The pure logic is evaluate(); see npm-audit-prod.test.cjs.
 */
const { execFileSync } = require('node:child_process');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');

/** GHSA id -> { pkgs: Set, severity, title, url } for high/critical advisories in an npm audit --json report. */
function collect(report) {
  const found = new Map();
  for (const [pkg, v] of Object.entries(report.vulnerabilities ?? {})) {
    for (const via of v.via ?? []) {
      if (typeof via !== 'object' || !['high', 'critical'].includes(via.severity)) continue;
      const id = String(via.url).split('/').pop();
      const a = found.get(id) ?? { pkgs: new Set(), severity: via.severity, title: via.title, url: via.url };
      a.pkgs.add(via.name ?? pkg);
      found.set(id, a);
    }
  }
  return found;
}

/** Returns { problems: string[], notes: string[], found }. today is YYYY-MM-DD. */
function evaluate(report, allow, today) {
  const found = collect(report);
  const problems = [];
  const notes = [];
  for (const [id, a] of found) {
    const pkg = [...a.pkgs].join(',');
    const entry = allow[id];
    if (!entry) {
      problems.push(`NOT ALLOWED ${a.severity} ${pkg} ${id}: ${a.title} (${a.url})`);
      continue;
    }
    let ok = true;
    if (!a.pkgs.has(entry.package)) {
      problems.push(`PACKAGE MISMATCH allowlist entry ${id}: package "${entry.package}", but npm reports it for "${pkg}"`);
      ok = false;
    }
    if (typeof entry.reviewBy !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(entry.reviewBy)) {
      problems.push(`INVALID allowlist entry ${id}: reviewBy must be YYYY-MM-DD`);
      ok = false;
    } else if (entry.reviewBy < today) {
      problems.push(`EXPIRED allowlist entry ${id} (${pkg}), reviewBy ${entry.reviewBy}`);
      ok = false;
    }
    if (ok) notes.push(`allowed until ${entry.reviewBy}: ${a.severity} ${pkg} ${id}: ${a.title}`);
  }
  for (const id of Object.keys(allow)) {
    if (!found.has(id)) problems.push(`STALE allowlist entry ${id}: no longer reported; remove it`);
  }
  return { problems, notes, found };
}

function main() {
  const allow = JSON.parse(readFileSync(join(__dirname, '..', 'npm-audit-allowlist.json'), 'utf8')).advisories;
  let out;
  try {
    out = execFileSync('npm', ['audit', '--omit=dev', '--json'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  } catch (e) {
    out = e.stdout; // npm audit exits non-zero when it finds anything
  }
  const report = JSON.parse(out);
  if (report.error) throw new Error(`npm audit failed: ${JSON.stringify(report.error)}`);
  const { problems, notes, found } = evaluate(report, allow, new Date().toISOString().slice(0, 10));
  notes.forEach((n) => console.log(n));
  if (problems.length) {
    console.error(problems.join('\n'));
    process.exit(1);
  }
  console.log(`npm audit (production deps): no unallowed high/critical advisories (${found.size} allowlisted).`);
}

module.exports = { collect, evaluate };
if (require.main === module) main();
