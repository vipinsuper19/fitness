import { Controller, Get, HttpCode, Query, Res } from '@nestjs/common';
import { ApiOkResponse, ApiQuery, ApiTags } from '@nestjs/swagger';
import type { Response } from 'express';

import { Public } from '../auth/public.decorator';
import { RateLimit } from '../common/rate-limit';
import { PrismaService } from '../prisma/prisma.service';

/** GET /health/deep (docs/specs/health-deep.md): nothing beyond these three fields is ever returned. */
export interface DeepHealthResponse {
  status: 'ok' | 'degraded';
  db: 'up' | 'down';
  /** Latest applied Prisma migration name, or null when it could not be read. */
  migration: string | null;
}

/** Deep-check budget; a slow or paused database counts as down. */
export const DEEP_TIMEOUT_MS = 2000;

export interface HealthResponse {
  status: 'ok';
  uptimeSeconds: number;
  db?: 'up' | 'down';
}

/**
 * Liveness for Render's health check. It never fails on the database, so a
 * paused Supabase project does not restart-loop the service. Pass
 * `?db=true` to also probe the database.
 */
@ApiTags('health')
@Public()
@Controller('health')
export class HealthController {
  constructor(private readonly prisma: PrismaService) {}

  @Get()
  @HttpCode(200)
  @ApiQuery({ name: 'db', required: false, type: Boolean })
  @ApiOkResponse({ description: 'Service is up' })
  async health(@Query('db') db?: string): Promise<HealthResponse> {
    const body: HealthResponse = { status: 'ok', uptimeSeconds: Math.round(process.uptime()) };
    if (db === 'true') body.db = await this.probeDb();
    return body;
  }

  private async probeDb(): Promise<'up' | 'down'> {
    try {
      await this.prisma.$queryRaw`SELECT 1`;
      return 'up';
    } catch {
      return 'down';
    }
  }

  /**
   * Readiness/diagnostics: one `SELECT 1` and the latest applied migration name, within DEEP_TIMEOUT_MS.
   * 200 `ok` (both read), 200 `degraded` (db up, migration unreadable), 503 `degraded`/`down` (db failed or timed out).
   * Errors are swallowed on purpose: no message, host, version or stack reaches the body. Rate-limited per IP.
   */
  @Get('deep')
  @RateLimit('healthDeep')
  @ApiOkResponse({ description: 'Database reachable' })
  async deep(@Res({ passthrough: true }) res: Response): Promise<DeepHealthResponse> {
    if ((await withTimeout(this.prisma.$queryRaw`SELECT 1`)) === FAILED) {
      res.status(503);
      return { status: 'degraded', db: 'down', migration: null };
    }
    const rows = await withTimeout(
      this.prisma.$queryRaw<{ migration_name: string }[]>`SELECT migration_name FROM _prisma_migrations WHERE finished_at IS NOT NULL AND rolled_back_at IS NULL ORDER BY finished_at DESC, migration_name DESC LIMIT 1`,
    );
    const name = rows === FAILED ? undefined : rows[0]?.migration_name;
    const migration = typeof name === 'string' && /^[A-Za-z0-9_.-]{1,255}$/.test(name) ? name : null;
    return { status: migration ? 'ok' : 'degraded', db: 'up', migration };
  }
}

const FAILED: unique symbol = Symbol('failed');
type Failed = typeof FAILED;

/** Resolves to the value, or FAILED on any error or after DEEP_TIMEOUT_MS. Never rejects. */
async function withTimeout<T>(p: PromiseLike<T>): Promise<T | typeof FAILED> {
  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<typeof FAILED>((resolve) => {
    timer = setTimeout(() => resolve(FAILED), DEEP_TIMEOUT_MS);
  });
  try {
    const settled = Promise.resolve(p).then(
      (v): T | Failed => v,
      (): T | Failed => FAILED,
    );
    return await Promise.race<T | Failed>([settled, timeout]);
  } finally {
    clearTimeout(timer);
  }
}
