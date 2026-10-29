import { z } from 'zod';

/**
 * Environment contract. Values come from the host (Render dashboard,
 * local `.env` that is git-ignored); nothing secret lives in the repo.
 */
export const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  /** One PII-free JSON line per request on stdout (docs/specs/request-log.md). */
  REQUEST_LOG: z.enum(['on', 'off']).default('on'),
  PORT: z.coerce.number().int().min(1).max(65535).default(3000),
  /** Supabase pooled connection string (pgbouncer, port 6543) used at runtime. */
  DATABASE_URL: z.string().url().refine((v) => /^postgres(ql)?:\/\//.test(v), 'must be a postgres URL'),
  /** Supabase direct connection (port 5432) for migrations. Optional at runtime. */
  DIRECT_URL: z.string().url().optional(),
  /** HS256 signing secrets for our own JWTs (T7). At least 32 chars. */
  JWT_ACCESS_SECRET: z.string().min(32),
  JWT_REFRESH_SECRET: z.string().min(32),
  JWT_ACCESS_TTL_SECONDS: z.coerce.number().int().positive().default(900),
  JWT_REFRESH_TTL_DAYS: z.coerce.number().int().positive().default(30),
  /** Requests per minute per client IP on auth and account routes. */
  AUTH_RATE_LIMIT_PER_MINUTE: z.coerce.number().int().positive().default(10),
  /** Looser per-IP limit for POST /auth/refresh (apps refresh on every launch). */
  AUTH_REFRESH_RATE_LIMIT_PER_MINUTE: z.coerce.number().int().positive().default(30),
  /** GET /me/export requests allowed per user per hour. */
  EXPORT_RATE_LIMIT_PER_HOUR: z.coerce.number().int().positive().default(5),
  /** GET /me/target-history requests allowed per user per minute. */
  TARGET_HISTORY_RATE_LIMIT_PER_MINUTE: z.coerce.number().int().positive().default(60),
  NOTIFICATION_PREFS_RATE_LIMIT_PER_MINUTE: z.coerce.number().int().positive().default(60),
  /**
   * Per-user write limits (requests per minute) for routes that had no throttle before this audit. Defaults are
   * generous so no real client is affected; they bound abuse (docs/DEPLOY.md, test/rate-limits.spec.ts).
   */
  PROFILE_RATE_LIMIT_PER_MINUTE: z.coerce.number().int().positive().default(60),
  GOALS_RATE_LIMIT_PER_MINUTE: z.coerce.number().int().positive().default(60),
  PLANS_RATE_LIMIT_PER_MINUTE: z.coerce.number().int().positive().default(60),
  /** Set logging bursts during a workout and offline-queue replays. */
  WORKOUT_SESSIONS_RATE_LIMIT_PER_MINUTE: z.coerce.number().int().positive().default(300),
  /** Foods, food logs and water logs (offline-queue replays). */
  NUTRITION_RATE_LIMIT_PER_MINUTE: z.coerce.number().int().positive().default(300),
  BODY_MEASUREMENTS_RATE_LIMIT_PER_MINUTE: z.coerce.number().int().positive().default(60),
  CHALLENGES_RATE_LIMIT_PER_MINUTE: z.coerce.number().int().positive().default(60),
  /** GET /health/deep requests per client IP per minute (it queries the database). */
  HEALTH_DEEP_RATE_LIMIT_PER_MINUTE: z.coerce.number().int().positive().default(12),
  /** GET /me/export caps (newest rows kept; cut collections are listed in `truncated`). */
  EXPORT_MAX_ROWS: z.coerce.number().int().positive().max(50_000).default(5000),
  EXPORT_MAX_SESSIONS: z.coerce.number().int().positive().max(50_000).default(2000),
  EXPORT_MAX_PLAN_VERSIONS: z.coerce.number().int().positive().max(10_000).default(200),
  EXPORT_MAX_SETS_TOTAL: z.coerce.number().int().positive().max(200_000).default(20_000),
  EXPORT_MAX_PLAN_EXERCISES_TOTAL: z.coerce.number().int().positive().max(100_000).default(10_000),
  /** Failed logins allowed per account per 15 minutes before 429. */
  LOGIN_MAX_FAILURES_PER_ACCOUNT: z.coerce.number().int().positive().default(5),
  /** Proxy hops in front of the app (Render: 1, per Render's docs). Sets Express `trust proxy`. */
  TRUST_PROXY_HOPS: z.coerce.number().int().min(0).max(5).default(1),
  /** Background badge/challenge evaluation after writes. `off` only for tests that assert exact XP. */
  ACHIEVEMENTS_HOOKS: z.enum(['on', 'off']).default('on'),
  CORS_ORIGINS: z.string().optional(),
  /** Enables Swagger UI at /docs. Defaults on outside production. */
  SWAGGER_ENABLED: z
    .enum(['true', 'false'])
    .optional()
    .transform((v) => (v === undefined ? undefined : v === 'true')),
});

export type Env = z.infer<typeof envSchema>;

export class EnvValidationError extends Error {
  constructor(public readonly issues: string[]) {
    super(`Invalid environment: ${issues.join('; ')}`);
    this.name = 'EnvValidationError';
  }
}

/** Validates env without echoing any values, so secrets never reach logs. */
export function loadEnv(source: NodeJS.ProcessEnv = process.env): Env {
  const parsed = envSchema.safeParse(source);
  if (!parsed.success) {
    throw new EnvValidationError(
      parsed.error.issues.map((issue) => `${issue.path.join('.') || '(root)'}: ${issue.message}`),
    );
  }
  return parsed.data;
}
