import type { NestExpressApplication } from '@nestjs/platform-express';
import { Test } from '@nestjs/testing';

import { AppModule } from '../src/app.module';
import { ENV } from '../src/config/config.module';
import { Env, loadEnv } from '../src/config/env';
import { PrismaService } from '../src/prisma/prisma.service';
import { configureApp } from '../src/setup';
import { installResponseChecker } from './support/openapi';

export function testEnv(overrides: Record<string, string> = {}): Env {
  return loadEnv({
    NODE_ENV: 'test',
    // Quiet test output; test/request-log.int-spec.ts turns it on.
    REQUEST_LOG: 'off',
    DATABASE_URL: process.env.DATABASE_URL ?? 'postgresql://user:pass@localhost:5432/fitness',
    JWT_ACCESS_SECRET: 'test-access-secret-0123456789abcdef',
    JWT_REFRESH_SECRET: 'test-refresh-secret-0123456789abcdef',
    AUTH_RATE_LIMIT_PER_MINUTE: '1000',
    AUTH_REFRESH_RATE_LIMIT_PER_MINUTE: '1000',
    EXPORT_RATE_LIMIT_PER_HOUR: '1000',
    TARGET_HISTORY_RATE_LIMIT_PER_MINUTE: '1000',
    NOTIFICATION_PREFS_RATE_LIMIT_PER_MINUTE: '1000',
    PROFILE_RATE_LIMIT_PER_MINUTE: '1000',
    GOALS_RATE_LIMIT_PER_MINUTE: '1000',
    PLANS_RATE_LIMIT_PER_MINUTE: '1000',
    WORKOUT_SESSIONS_RATE_LIMIT_PER_MINUTE: '1000',
    NUTRITION_RATE_LIMIT_PER_MINUTE: '1000',
    BODY_MEASUREMENTS_RATE_LIMIT_PER_MINUTE: '1000',
    CHALLENGES_RATE_LIMIT_PER_MINUTE: '1000',
    LOGIN_MAX_FAILURES_PER_ACCOUNT: '1000',
    // Off by default so specs asserting exact XP stay deterministic; achievements.int-spec turns it on.
    ACHIEVEMENTS_HOOKS: 'off',
    ...overrides,
  });
}

/** The real app, as main.ts builds it. Pass `prisma` to stub the database. */
export async function createTestApp(
  options: { env?: Record<string, string>; prisma?: unknown } = {},
): Promise<NestExpressApplication> {
  const env = testEnv(options.env);
  let builder = Test.createTestingModule({ imports: [AppModule] }).overrideProvider(ENV).useValue(env);
  if (options.prisma) builder = builder.overrideProvider(PrismaService).useValue(options.prisma);
  const moduleRef = await builder.compile();
  const app = moduleRef.createNestApplication<NestExpressApplication>({ bodyParser: false });
  installResponseChecker(app); // before configureApp's middleware, so it sees every JSON response
  configureApp(app, env);
  await app.init();
  return app;
}

/**
 * Target history (docs/specs/target-history.md): a PUT dates its targets "today", so tests that
 * read earlier days move the user's newest row to 1900-01-01, as if set long ago (like the backfill).
 */
export async function backdateTargets(prisma: PrismaService, userId: string): Promise<void> {
  const newest = await prisma.profileTargetHistory.findFirst({ where: { userId }, orderBy: { effectiveFrom: 'desc' } });
  if (!newest) return;
  const old = new Date('1900-01-01T00:00:00Z');
  await prisma.$transaction([
    prisma.profileTargetHistory.deleteMany({ where: { userId, effectiveFrom: { not: newest.effectiveFrom } } }),
    prisma.profileTargetHistory.update({ where: { userId_effectiveFrom: { userId, effectiveFrom: newest.effectiveFrom } }, data: { effectiveFrom: old } }),
  ]);
}
