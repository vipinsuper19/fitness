/** Static check (no DB) that the index review's migration and schema.prisma agree on the index set. */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { ADDED, DROPPED } from './support/index-review-sql';

const root = join(__dirname, '..', 'prisma');
const migration = readFileSync(join(root, 'migrations', '20261029001600_index_review', 'migration.sql'), 'utf8');
const schema = readFileSync(join(root, 'schema.prisma'), 'utf8');
const model = (name: string) => new RegExp(`model ${name} \\{[\\s\\S]*?\\n\\}`).exec(schema)?.[0] ?? '';

describe('index review migration', () => {
  it('creates every added index and drops every redundant one', () => {
    for (const i of ADDED) expect(migration).toContain(`CREATE INDEX "${i}"`);
    for (const i of DROPPED) expect(migration).toContain(`DROP INDEX "${i}"`);
  });

  it('schema.prisma declares the added indexes and not the dropped ones', () => {
    expect(model('WorkoutSession')).toContain('@@index([planVersionId])');
    expect(model('WorkoutSession')).toContain('@@index([plannedWorkoutId])');
    expect(model('WorkoutSession')).not.toMatch(/@@index\(\[userId, completedAt\]\)/);
    expect(model('WorkoutPlan')).not.toMatch(/@@index\(\[userId\]\)/);
    expect(model('IdempotencyKey')).toContain('@@index([userId])');
  });
});
