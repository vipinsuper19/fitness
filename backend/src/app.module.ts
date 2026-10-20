import { Module } from '@nestjs/common';

import { AuthModule } from './auth/auth.module';
import { ConfigModule } from './config/config.module';
import { ExercisesModule } from './exercises/exercises.module';
import { GoalsModule } from './goals/goals.module';
import { HealthModule } from './health/health.module';
import { IdempotencyModule } from './idempotency/idempotency.module';
import { PrismaModule } from './prisma/prisma.module';
import { ProfileModule } from './profile/profile.module';
import { PlansModule } from './workout-plans/plans.module';

@Module({
  imports: [
    ConfigModule,
    PrismaModule,
    IdempotencyModule,
    AuthModule,
    HealthModule,
    ProfileModule,
    GoalsModule,
    ExercisesModule,
    PlansModule,
  ],
})
export class AppModule {}
