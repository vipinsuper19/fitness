import { Inject, Injectable, OnModuleDestroy } from '@nestjs/common';
import { PrismaPg } from '@prisma/adapter-pg';

import { ENV } from '../config/config.module';
import type { Env } from '../config/env';
import { PrismaClient } from '../generated/prisma/client';

/**
 * One Prisma client per process over the pg driver adapter. Connects lazily
 * on the first query so the app (and /health) starts even when Supabase is
 * paused or cold.
 */
@Injectable()
export class PrismaService extends PrismaClient implements OnModuleDestroy {
  constructor(@Inject(ENV) env: Env) {
    super({ adapter: new PrismaPg({ connectionString: env.DATABASE_URL }) });
  }

  async onModuleDestroy(): Promise<void> {
    await this.$disconnect();
  }
}
