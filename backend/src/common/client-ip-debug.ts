/**
 * TEMPORARY — REMOVE IN THE TRUST_PROXY_HOPS FIX PR.
 *
 * Client-IP diagnostics for working out the real proxy hop count on Render.
 * Off unless DEBUG_CLIENT_IP=true. Only auth routes (register, login, refresh).
 * Logs addressing data only: never bodies, tokens, emails, Authorization or cookies.
 */
import { Logger } from "@nestjs/common";
import type { NextFunction, Request, Response } from "express";

export const CLIENT_IP_DEBUG_PATHS = new Set([
  "/api/v1/auth/register",
  "/api/v1/auth/login",
  "/api/v1/auth/refresh",
]);
export const CLIENT_IP_DEBUG_MAX_PER_MINUTE = 20;

export interface ClientIpDebugRecord {
  route: string;
  ip: string | undefined;
  ips: string[];
  remoteAddress: string | undefined;
  xForwardedFor: string | null;
  xForwardedForLength: number;
  cfConnectingIp: string | null;
  trueClientIp: string | null;
  xRealIp: string | null;
  trustProxy: unknown;
}

function header(req: Request, name: string): string | null {
  const v = req.headers[name];
  if (v === undefined) return null;
  return Array.isArray(v) ? v.join(", ") : v;
}

/** Builds the record from an explicit allow-list of fields; nothing else is read. */
export function buildClientIpRecord(req: Request): ClientIpDebugRecord {
  const xff = header(req, "x-forwarded-for");
  return {
    route: `${req.method} ${req.path}`,
    ip: req.ip,
    ips: req.ips ?? [],
    remoteAddress: req.socket?.remoteAddress,
    xForwardedFor: xff,
    xForwardedForLength: xff
      ? xff.split(",").filter((s) => s.trim()).length
      : 0,
    cfConnectingIp: header(req, "cf-connecting-ip"),
    trueClientIp: header(req, "true-client-ip"),
    xRealIp: header(req, "x-real-ip"),
    trustProxy: req.app?.get("trust proxy"),
  };
}

export function createClientIpDebugMiddleware(
  enabled: boolean,
  log: (line: string) => void = (l) => new Logger("ClientIpDebug").log(l),
  now: () => number = Date.now,
  maxPerMinute = CLIENT_IP_DEBUG_MAX_PER_MINUTE,
) {
  let windowStart = 0;
  let count = 0;
  return (req: Request, _res: Response, next: NextFunction): void => {
    if (
      enabled &&
      req.method === "POST" &&
      CLIENT_IP_DEBUG_PATHS.has(req.path)
    ) {
      const t = now();
      if (t - windowStart >= 60_000) {
        windowStart = t;
        count = 0;
      }
      if (count < maxPerMinute) {
        count += 1;
        log(JSON.stringify(buildClientIpRecord(req)));
      }
    }
    next();
  };
}
