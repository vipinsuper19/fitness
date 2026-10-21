// TEMPORARY: remove with src/common/client-ip-debug.ts in the TRUST_PROXY_HOPS fix PR.
import type { Request, Response } from "express";

import {
  buildClientIpRecord,
  createClientIpDebugMiddleware,
} from "../src/common/client-ip-debug";
import { envSchema } from "../src/config/env";

const base = {
  DATABASE_URL: "postgresql://u:p@localhost:5432/db",
  JWT_ACCESS_SECRET: "a".repeat(32),
  JWT_REFRESH_SECRET: "b".repeat(32),
};

function req(path: string, method = "POST"): Request {
  return {
    method,
    path,
    ip: "10.0.0.1",
    ips: ["203.0.113.7", "10.0.0.1"],
    socket: { remoteAddress: "10.0.0.2" },
    headers: {
      "x-forwarded-for": "203.0.113.7, 172.70.1.1",
      "cf-connecting-ip": "203.0.113.7",
      authorization: "Bearer secret-token",
      cookie: "sid=secret-cookie",
    },
    body: {
      email: "person@example.com",
      password: "hunter2hunter2",
      refreshToken: "rt-secret",
    },
    app: { get: (k: string) => (k === "trust proxy" ? 2 : undefined) },
  } as unknown as Request;
}

function run(
  mw: ReturnType<typeof createClientIpDebugMiddleware>,
  r: Request,
): void {
  const next = jest.fn();
  mw(r, {} as Response, next);
  expect(next).toHaveBeenCalledTimes(1);
}

describe("client IP debug (temporary)", () => {
  it("is off by default", () => {
    expect(envSchema.parse(base).DEBUG_CLIENT_IP).toBe(false);
    expect(
      envSchema.parse({ ...base, DEBUG_CLIENT_IP: "true" }).DEBUG_CLIENT_IP,
    ).toBe(true);
    expect(
      envSchema.safeParse({ ...base, DEBUG_CLIENT_IP: "yes" }).success,
    ).toBe(false);
    const log = jest.fn();
    run(createClientIpDebugMiddleware(false, log), req("/api/v1/auth/login"));
    expect(log).not.toHaveBeenCalled();
  });

  it("logs only POST register, login and refresh", () => {
    const log = jest.fn();
    const mw = createClientIpDebugMiddleware(true, log);
    for (const p of [
      "/api/v1/auth/register",
      "/api/v1/auth/login",
      "/api/v1/auth/refresh",
    ])
      run(mw, req(p));
    expect(log).toHaveBeenCalledTimes(3);
    for (const p of [
      "/api/v1/auth/logout",
      "/api/v1/me",
      "/health",
      "/api/v1/workout-plans/generate",
    ])
      run(mw, req(p));
    run(mw, req("/api/v1/auth/login", "GET"));
    expect(log).toHaveBeenCalledTimes(3);
  });

  it("logs only the allow-listed addressing fields", () => {
    const rec = buildClientIpRecord(req("/api/v1/auth/login"));
    expect(Object.keys(rec).sort()).toEqual(
      [
        "cfConnectingIp",
        "ip",
        "ips",
        "remoteAddress",
        "route",
        "trueClientIp",
        "trustProxy",
        "xForwardedFor",
        "xForwardedForLength",
        "xRealIp",
      ].sort(),
    );
    expect(rec).toMatchObject({
      xForwardedForLength: 2,
      trustProxy: 2,
      cfConnectingIp: "203.0.113.7",
      trueClientIp: null,
    });
    const log = jest.fn();
    run(createClientIpDebugMiddleware(true, log), req("/api/v1/auth/login"));
    const line = log.mock.calls[0][0] as string;
    for (const secret of [
      "secret-token",
      "secret-cookie",
      "person@example.com",
      "hunter2",
      "rt-secret",
      "authorization",
      "cookie",
      "password",
    ]) {
      expect(line.toLowerCase()).not.toContain(secret.toLowerCase());
    }
  });

  it("rate-limits its own log lines per minute", () => {
    const log = jest.fn();
    let t = 1_000_000;
    const mw = createClientIpDebugMiddleware(true, log, () => t, 3);
    for (let i = 0; i < 10; i++) run(mw, req("/api/v1/auth/login"));
    expect(log).toHaveBeenCalledTimes(3);
    t += 60_000;
    run(mw, req("/api/v1/auth/login"));
    expect(log).toHaveBeenCalledTimes(4);
  });
});
