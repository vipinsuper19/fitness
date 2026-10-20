import { z } from 'zod';

/**
 * Environment contract. Values come from the host (Render dashboard,
 * local `.env` that is git-ignored); nothing secret lives in the repo.
 */
export const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
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
  /** GET /me/export requests allowed per user per hour. */
  EXPORT_RATE_LIMIT_PER_HOUR: z.coerce.number().int().positive().default(5),
  AUTH_REFRESH_RATE_LIMIT_PER_MINUTE: z.coerce.number().int().positive().default(30),
  /** Failed logins allowed per account per 15 minutes before 429. */
  LOGIN_MAX_FAILURES_PER_ACCOUNT: z.coerce.number().int().positive().default(5),
  /** Proxy hops in front of the app (Render: 1, per Render's docs). Sets Express `trust proxy`. */
  TRUST_PROXY_HOPS: z.coerce.number().int().min(0).max(5).default(1),
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
