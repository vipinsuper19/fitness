import { applyDecorators, Injectable, UseGuards } from '@nestjs/common';
import { SkipThrottle, ThrottlerGuard } from '@nestjs/throttler';

import type { Env } from '../config/env';
import type { AuthedRequest } from '../auth/jwt-auth.guard';

/**
 * Every named throttle. Each one has its own env var (docs/DEPLOY.md), and test/rate-limits.spec.ts requires every
 * write route to use exactly one of them. `perIp` throttles count by client IP (unauthenticated routes); the rest
 * count per signed-in user.
 */
export const THROTTLES = {
  auth: { env: 'AUTH_RATE_LIMIT_PER_MINUTE', ttl: 60_000, perIp: true },
  refresh: { env: 'AUTH_REFRESH_RATE_LIMIT_PER_MINUTE', ttl: 60_000, perIp: true },
  export: { env: 'EXPORT_RATE_LIMIT_PER_HOUR', ttl: 3_600_000, perIp: false },
  targetHistory: { env: 'TARGET_HISTORY_RATE_LIMIT_PER_MINUTE', ttl: 60_000, perIp: false },
  notificationPrefs: { env: 'NOTIFICATION_PREFS_RATE_LIMIT_PER_MINUTE', ttl: 60_000, perIp: false },
  profile: { env: 'PROFILE_RATE_LIMIT_PER_MINUTE', ttl: 60_000, perIp: false },
  goals: { env: 'GOALS_RATE_LIMIT_PER_MINUTE', ttl: 60_000, perIp: false },
  plans: { env: 'PLANS_RATE_LIMIT_PER_MINUTE', ttl: 60_000, perIp: false },
  sessions: { env: 'WORKOUT_SESSIONS_RATE_LIMIT_PER_MINUTE', ttl: 60_000, perIp: false },
  nutrition: { env: 'NUTRITION_RATE_LIMIT_PER_MINUTE', ttl: 60_000, perIp: false },
  bodyMeasurements: { env: 'BODY_MEASUREMENTS_RATE_LIMIT_PER_MINUTE', ttl: 60_000, perIp: false },
  challenges: { env: 'CHALLENGES_RATE_LIMIT_PER_MINUTE', ttl: 60_000, perIp: false },
  healthDeep: { env: 'HEALTH_DEEP_RATE_LIMIT_PER_MINUTE', ttl: 60_000, perIp: true },
} as const satisfies Record<string, { env: keyof Env; ttl: number; perIp: boolean }>;

export type ThrottleName = keyof typeof THROTTLES;
export const THROTTLE_NAMES = Object.keys(THROTTLES) as ThrottleName[];

export function throttlerOptions(env: Env) {
  return THROTTLE_NAMES.map((name) => ({ name, ttl: THROTTLES[name].ttl, limit: env[THROTTLES[name].env] as number }));
}

/** Counts per signed-in user rather than per IP. */
@Injectable()
export class UserThrottlerGuard extends ThrottlerGuard {
  protected override async getTracker(req: Record<string, unknown>): Promise<string> {
    return (req as unknown as AuthedRequest).userId ?? String(req.ip);
  }
}

/**
 * Applies exactly one named throttle (all others skipped). Put it on each handler, or on a controller whose
 * handlers have none of their own; don't stack it on both (the guard would count twice).
 */
export function RateLimit(name: ThrottleName) {
  return applyDecorators(
    UseGuards(THROTTLES[name].perIp ? ThrottlerGuard : UserThrottlerGuard),
    SkipThrottle(Object.fromEntries(THROTTLE_NAMES.map((n) => [n, n !== name]))),
  );
}
