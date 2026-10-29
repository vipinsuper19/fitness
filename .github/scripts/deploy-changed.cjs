#!/usr/bin/env node
/**
 * Decides whether a push to main changed anything Render would deploy, using the same
 * buildFilter.paths as render.yaml (read from the file, not duplicated here).
 *
 * Usage: node .github/scripts/deploy-changed.cjs <before-sha> <after-sha>
 * Prints and writes deployable=true|false (+ reason) to $GITHUB_OUTPUT when set.
 *
 * Conservative: if `before` is missing/all zeros/not in history (new branch, force push,
 * shallow clone) or render.yaml has no buildFilter, it reports deployable=true so the
 * smoke run waits for a deploy as before.
 */
const { execFileSync } = require('node:child_process');
const { readFileSync, appendFileSync } = require('node:fs');
const { join } = require('node:path');

/** buildFilter.paths of every service in render.yaml text (minimal YAML list reader). null if none. */
function buildFilterPaths(yamlText) {
  const lines = yamlText.split(/\r?\n/);
  const out = [];
  let found = false;
  for (let i = 0; i < lines.length; i++) {
    const m = /^(\s*)buildFilter:\s*$/.exec(lines[i]);
    if (!m) continue;
    found = true;
    const base = m[1].length;
    let inPaths = false;
    for (let j = i + 1; j < lines.length; j++) {
      const l = lines[j];
      if (!l.trim() || l.trim().startsWith('#')) continue;
      const ind = l.length - l.trimStart().length;
      if (ind <= base) break;
      const key = /^\s*([A-Za-z]+):\s*$/.exec(l);
      if (key) { inPaths = key[1] === 'paths'; continue; }
      const item = /^\s*-\s*["']?([^"'#]+?)["']?\s*(#.*)?$/.exec(l);
      if (item && inPaths) out.push(item[1]);
    }
  }
  return found && out.length ? [...new Set(out)] : null;
}

/** Glob -> RegExp: ** spans directories, * and ? stay within one segment. */
function globToRegExp(glob) {
  let re = '';
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === '*' && glob[i + 1] === '*') {
      i++;
      if (glob[i + 1] === '/') { i++; re += '(?:.*/)?'; } else re += '.*';
    } else if (c === '*') re += '[^/]*';
    else if (c === '?') re += '[^/]';
    else re += c.replace(/[.+^${}()|[\]\\]/g, '\\$&');
  }
  return new RegExp(`^${re}$`);
}

function deployableFiles(files, patterns) {
  const res = patterns.map(globToRegExp);
  return files.filter((f) => res.some((r) => r.test(f)));
}

function decide({ before, after, patterns, git }) {
  if (!patterns) return { deployable: true, reason: 'render.yaml has no buildFilter.paths; every change deploys' };
  if (!before || /^0+$/.test(before)) return { deployable: true, reason: 'no previous commit to diff against' };
  let files;
  try {
    files = git(['diff', '--name-only', `${before}..${after}`]).split('\n').filter(Boolean);
  } catch {
    return { deployable: true, reason: `cannot diff ${before}..${after} (not in history)` };
  }
  const hit = deployableFiles(files, patterns);
  return hit.length
    ? { deployable: true, reason: `${hit.length} deployable file(s) changed, e.g. ${hit.slice(0, 5).join(', ')}`, files: hit }
    : { deployable: false, reason: `no deployable changes in ${files.length} changed file(s) (buildFilter: ${patterns.join(', ')})`, files: [] };
}

function main() {
  const [before, after = 'HEAD'] = process.argv.slice(2);
  const root = join(__dirname, '..', '..');
  const patterns = buildFilterPaths(readFileSync(join(root, 'render.yaml'), 'utf8'));
  const git = (args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  const r = decide({ before, after, patterns, git });
  console.log(`deployable=${r.deployable}: ${r.reason}`);
  if (process.env.GITHUB_OUTPUT) {
    appendFileSync(process.env.GITHUB_OUTPUT, `deployable=${r.deployable}\nreason=${r.reason.replace(/\n/g, ' ')}\n`);
  }
}

module.exports = { buildFilterPaths, globToRegExp, deployableFiles, decide };
if (require.main === module) main();
