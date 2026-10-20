import type { NestExpressApplication } from '@nestjs/platform-express';
import { Test } from '@nestjs/testing';

import { AppModule } from '../src/app.module';
import { ENV } from '../src/config/config.module';
import { Env, loadEnv } from '../src/config/env';
import { PrismaService } from '../src/prisma/prisma.service';
import { configureApp } from '../src/setup';

export function testEnv(overrides: Record<string, string> = {}): Env {
  return loadEnv({
    NODE_ENV: 'test',
    DATABASE_URL: process.env.DATABASE_URL ?? 'postgresql://user:pass@localhost:5432/fitness',
    JWT_ACCESS_SECRET: 'test-access-secret-0123456789abcdef',
    JWT_REFRESH_SECRET: 'test-refresh-secret-0123456789abcdef',
    AUTH_RATE_LIMIT_PER_MINUTE: '1000',
    AUTH_REFRESH_RATE_LIMIT_PER_MINUTE: '1000',
    LOGIN_MAX_FAILURES_PER_ACCOUNT: '1000',
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
  configureApp(app, env);
  await app.init();
  return app;
}
