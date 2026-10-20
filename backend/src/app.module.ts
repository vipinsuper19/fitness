import { Module } from '@nestjs/common';

import { ConfigModule } from './config/config.module';
import { HealthModule } from './health/health.module';
import { IdempotencyModule } from './idempotency/idempotency.module';
import { PrismaModule } from './prisma/prisma.module';

@Module({ imports: [ConfigModule, PrismaModule, IdempotencyModule, HealthModule] })
export class AppModule {}
