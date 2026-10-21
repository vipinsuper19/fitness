/**
 * Release backfill: `DATABASE_URL=... npm run badges:backfill` (Render Free has
 * no shell, so run it locally like db:seed). Idempotent: re-running awards nothing new.
 */
import { PrismaPg } from '@prisma/adapter-pg';

import { PrismaClient } from '../generated/prisma/client';
import type { PrismaService } from '../prisma/prisma.service';
import { CATALOG_VERSION } from './achievements.rules';
import { AchievementsService } from './achievements.service';

export async function backfillAll(prisma: PrismaClient, log: (m: string) => void = () => undefined): Promise<{ users: number; badges: number; failed: number }> {
  const svc = new AchievementsService(prisma as unknown as PrismaService, { ACHIEVEMENTS_HOOKS: 'off' });
  let cursor: string | undefined;
  const total = { users: 0, badges: 0, failed: 0 };
  for (;;) {
    const users = await prisma.user.findMany({ select: { id: true }, orderBy: { id: 'asc' }, take: 200, ...(cursor ? { cursor: { id: cursor }, skip: 1 } : {}) });
    if (!users.length) break;
    for (const { id } of users) {
      try {
        const r = await svc.evaluate(id);
        await prisma.userProfile.updateMany({ where: { userId: id, badgesEvaluatedVersion: { lt: CATALOG_VERSION } }, data: { badgesEvaluatedVersion: CATALOG_VERSION } });
        total.badges += r.badgesAwarded.length;
      } catch (e) {
        total.failed += 1;
        log(`user ${id}: ${e instanceof Error ? e.message : String(e)}`);
      }
      total.users += 1;
    }
    cursor = users[users.length - 1]!.id;
  }
  return total;
}

async function main(): Promise<void> {
  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) throw new Error('DATABASE_URL is not set');
  const prisma = new PrismaClient({ adapter: new PrismaPg({ connectionString }) });
  try {
    const r = await backfillAll(prisma, (m) => console.error(m));
    console.log(`Evaluated ${r.users} users, awarded ${r.badges} badges, ${r.failed} failed (catalog v${CATALOG_VERSION}).`);
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
