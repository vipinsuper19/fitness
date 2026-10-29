/**
 * Target-history reconcile: `DATABASE_URL=... npm run targets:backfill` (docs/specs/target-history.md).
 * Run once after the app that writes history is live, and again after any redeploy that follows a
 * rollback. Idempotent: re-running writes nothing. Render Free has no shell, so run it locally or via
 * the manual `targets-backfill` GitHub Actions workflow (docs/DEPLOY.md).
 */
import { PrismaPg } from '@prisma/adapter-pg';

import { PrismaClient } from '../generated/prisma/client';
import { reconcileAll } from './target-history';

async function main(): Promise<void> {
  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) throw new Error('DATABASE_URL is not set');
  const prisma = new PrismaClient({ adapter: new PrismaPg({ connectionString }) });
  try {
    const r = await reconcileAll(prisma, new Date(), (m) => console.error(m));
    console.log(`Checked ${r.profiles} profiles, wrote ${r.written} target-history rows, ${r.failed} failed.`);
    if (r.failed) process.exitCode = 1;
  } finally {
    await prisma.$disconnect();
  }
}

if (require.main === module) {
  main().catch((error: unknown) => {
    console.error(error instanceof Error ? error.message : error);
    process.exit(1);
  });
}
