import { Module } from '@nestjs/common';
import { APP_GUARD } from '@nestjs/core';
import { JwtModule } from '@nestjs/jwt';
import { ThrottlerModule } from '@nestjs/throttler';

import { ENV } from '../config/config.module';
import type { Env } from '../config/env';
import { AuthController, MeController } from './auth.controller';
import { AuthService } from './auth.service';
import { LoginFailureLimiter } from './login-failure-limiter';
import { JwtAuthGuard } from './jwt-auth.guard';
import { PasswordService } from './password.service';
import { TokenService } from './token.service';

@Module({
  imports: [
    JwtModule.register({}),
    ThrottlerModule.forRootAsync({
      inject: [ENV],
      useFactory: (env: Env) => [
        { name: 'auth', ttl: 60_000, limit: env.AUTH_RATE_LIMIT_PER_MINUTE },
        { name: 'refresh', ttl: 60_000, limit: env.AUTH_REFRESH_RATE_LIMIT_PER_MINUTE },
      ],
    }),
  ],
  controllers: [AuthController, MeController],
  providers: [AuthService, PasswordService, TokenService, LoginFailureLimiter, { provide: APP_GUARD, useClass: JwtAuthGuard }],
  exports: [TokenService],
})
export class AuthModule {}
