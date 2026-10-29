const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { buildFilterPaths, deployableFiles, decide } = require('./deploy-changed.cjs');

test('reads the real buildFilter from render.yaml', () => {
  const p = buildFilterPaths(readFileSync(join(__dirname, '..', '..', 'render.yaml'), 'utf8'));
  assert.deepEqual(p, ['backend/**', 'render.yaml']);
});

test('buildFilter parser: ignoredPaths not included; missing filter -> null', () => {
  const y = 'services:\n  - name: a\n    buildFilter:\n      paths:\n        - "api/**"\n      ignoredPaths:\n        - api/docs/**\n    envVars: []\n';
  assert.deepEqual(buildFilterPaths(y), ['api/**']);
  assert.equal(buildFilterPaths('services:\n  - name: a\n'), null);
});

test('glob matching', () => {
  const pats = ['backend/**', 'render.yaml'];
  assert.deepEqual(
    deployableFiles(['backend/package-lock.json', 'backend/src/a/b.ts', 'render.yaml', '.github/workflows/x.yml', 'docs/security.md', 'android/app/x.kt', 'backendx/a', 'sub/render.yaml'], pats),
    ['backend/package-lock.json', 'backend/src/a/b.ts', 'render.yaml'],
  );
});

const pats = ['backend/**', 'render.yaml'];
const gitWith = (files) => (args) => {
  assert.deepEqual(args, ['diff', '--name-only', 'aaa..bbb']); // whole push range, not just head commit
  return files.join('\n') + '\n';
};

test('workflow/docs-only push is not deployable', () => {
  const r = decide({ before: 'aaa', after: 'bbb', patterns: pats, git: gitWith(['.github/workflows/codeql.yml', 'docs/security.md']) });
  assert.equal(r.deployable, false);
  assert.match(r.reason, /no deployable changes in 2 changed file/);
});

test('multi-commit push with any backend change is deployable', () => {
  const r = decide({ before: 'aaa', after: 'bbb', patterns: pats, git: gitWith(['docs/a.md', 'backend/package-lock.json']) });
  assert.equal(r.deployable, true);
  assert.deepEqual(r.files, ['backend/package-lock.json']);
});

test('conservative fallbacks: new branch, unknown before, no filter', () => {
  const boom = () => { throw new Error('bad object'); };
  assert.equal(decide({ before: '0000000000000000000000000000000000000000', after: 'b', patterns: pats, git: boom }).deployable, true);
  assert.equal(decide({ before: '', after: 'b', patterns: pats, git: boom }).deployable, true);
  assert.equal(decide({ before: 'aaa', after: 'bbb', patterns: pats, git: boom }).deployable, true);
  assert.equal(decide({ before: 'aaa', after: 'bbb', patterns: null, git: boom }).deployable, true);
});
