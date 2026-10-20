import { HttpStatus, Inject, Injectable } from '@nestjs/common';

import { ApiError } from '../common/api-error';
import { ENV } from '../config/config.module';
import type { Env } from '../config/env';

const WINDOW_MS = 15 * 60_000;
const MAX_TRACKED = 50_000;

/**
 * Per-account cap on failed logins (on top of the per-IP throttle), so one
 * account cannot be brute-forced from many IPs. In-memory and per instance:
 * fine for a single Render instance; move to a shared store if we scale out.
 * Unknown emails are counted too, so the limit does not reveal which exist.
 */
@Injectable()
export class LoginFailureLimiter {
  private readonly failures = new Map<string, { count: number; windowStart: number }>();

  constructor(@Inject(ENV) private readonly env: Env) {}

  assertAllowed(email: string, now = Date.now()): void {
    const entry = this.failures.get(email);
    if (!entry) return;
    if (now - entry.windowStart >= WINDOW_MS) {
      this.failures.delete(email);
      return;
    }
    if (entry.count >= this.env.LOGIN_MAX_FAILURES_PER_ACCOUNT) {
      const retryAfter = Math.ceil((entry.windowStart + WINDOW_MS - now) / 1000);
      throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, 'RATE_LIMITED', 'Too many failed sign-in attempts. Try again later.', {
        retryAfterSeconds: retryAfter,
      });
    }
  }

  recordFailure(email: string, now = Date.now()): void {
    const entry = this.failures.get(email);
    if (!entry || now - entry.windowStart >= WINDOW_MS) {
      if (this.failures.size >= MAX_TRACKED) this.prune(now);
      this.failures.set(email, { count: 1, windowStart: now });
    } else {
      entry.count += 1;
    }
  }

  reset(email: string): void {
    this.failures.delete(email);
  }

  private prune(now: number): void {
    for (const [key, entry] of this.failures) {
      if (now - entry.windowStart >= WINDOW_MS) this.failures.delete(key);
    }
    if (this.failures.size >= MAX_TRACKED) this.failures.clear();
  }
}
