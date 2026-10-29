import type { NextFunction, Response } from 'express';

import type { AuthedRequest } from '../auth/jwt-auth.guard';
import type { RequestWithId } from './request-id';

/**
 * One JSON line per request on stdout (docs/specs/request-log.md):
 * `{"type":"request","requestId","method","route","status","durationMs","userId"?}`.
 *
 * Deliberately minimal and PII-free: `route` is the matched route template (`/api/v1/goals/{goalId}`), never the raw
 * path, and never a query string; no bodies, emails, tokens, headers (Authorization, cookies, user agent) or IPs.
 * `userId` is the opaque account UUID, present only when the request was authenticated (set by JwtAuthGuard).
 * Unmatched paths (404 before routing) log `route: null`, so a probed URL never reaches the log.
 */
export interface RequestLogLine {
  type: 'request';
  requestId: string;
  method: string;
  route: string | null;
  status: number;
  durationMs: number;
  userId?: string;
}

/** Express route path (`/api/v1/goals/:goalId`) → template (`/api/v1/goals/{goalId}`); null when no route matched. */
export function routeTemplate(req: { baseUrl?: string; route?: { path?: unknown } }): string | null {
  const path = req.route?.path;
  if (typeof path !== 'string') return null;
  return `${req.baseUrl ?? ''}${path}`.replace(/:([A-Za-z0-9_]+)/g, '{$1}') || '/';
}

/** `/health` and anything under it (query strings excluded by req.path). */
export function isHealthPath(path: string): boolean {
  return path === '/health' || path.startsWith('/health/');
}

export function requestLogMiddleware(req: RequestWithId & AuthedRequest, res: Response, next: NextFunction): void {
  // Render's health checks (/health, /health?db=true, /health/deep) would flood the log; they're never logged.
  if (isHealthPath(req.path)) return next();
  const start = process.hrtime.bigint();
  res.on('finish', () => {
    const line: RequestLogLine = {
      type: 'request',
      requestId: req.requestId ?? '',
      method: req.method,
      route: routeTemplate(req),
      status: res.statusCode,
      durationMs: Math.round(Number(process.hrtime.bigint() - start) / 1e5) / 10,
    };
    if (typeof req.userId === 'string') line.userId = req.userId;
    process.stdout.write(`${JSON.stringify(line)}\n`);
  });
  next();
}
