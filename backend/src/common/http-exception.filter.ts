import { ArgumentsHost, Catch, ExceptionFilter, HttpException, HttpStatus, Logger } from '@nestjs/common';
import { ThrottlerException } from '@nestjs/throttler';
import type { Response } from 'express';

import type { RequestWithId } from './request-id';

/** Client-facing message for the general throttler's 429. */
export const RATE_LIMITED_MESSAGE = 'Too many requests. Try again later.';

const DEFAULT_CODES: Record<number, string> = {
  400: 'VALIDATION_FAILED',
  401: 'UNAUTHENTICATED',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  405: 'METHOD_NOT_ALLOWED',
  409: 'CONFLICT',
  413: 'PAYLOAD_TOO_LARGE',
  415: 'UNSUPPORTED_MEDIA_TYPE',
  422: 'UNPROCESSABLE',
  429: 'RATE_LIMITED',
};

interface ErrorBody {
  code: string;
  message: string;
  details?: Record<string, unknown>;
  requestId: string;
}

/**
 * Every error becomes `{ code, message, details?, requestId }` (contract).
 * Unknown errors are a generic 500; their message and stack never reach the
 * client, and request bodies are never logged.
 */
@Catch()
export class HttpExceptionFilter implements ExceptionFilter {
  private readonly logger = new Logger('HttpError');

  catch(exception: unknown, host: ArgumentsHost): void {
    const ctx = host.switchToHttp();
    const req = ctx.getRequest<RequestWithId>();
    const res = ctx.getResponse<Response>();
    const requestId = req.requestId ?? 'unknown';

    let status = HttpStatus.INTERNAL_SERVER_ERROR;
    let body: ErrorBody = { code: 'INTERNAL', message: 'Something went wrong. Please try again.', requestId };

    const parserStatus = bodyParserStatus(exception);
    if (exception instanceof HttpException) {
      status = exception.getStatus();
      const raw = exception.getResponse();
      const obj = typeof raw === 'object' && raw !== null ? (raw as Record<string, unknown>) : {};
      const code = typeof obj.code === 'string' ? obj.code : DEFAULT_CODES[status] ?? (status >= 500 ? 'INTERNAL' : 'ERROR');
      const message =
        status >= 500
          ? body.message
          : typeof obj.message === 'string'
            ? obj.message
            : typeof raw === 'string'
              ? raw
              : exception.message;
      body = { code, message, requestId };
      if (obj.details && typeof obj.details === 'object') body.details = obj.details as Record<string, unknown>;
    } else if (parserStatus) {
      status = parserStatus;
      body = {
        code: DEFAULT_CODES[status] ?? 'VALIDATION_FAILED',
        message: status === 413 ? 'Request body is too large.' : 'Malformed request body.',
        requestId,
      };
    }

    if (status >= 500) {
      const name = exception instanceof Error ? exception.name : typeof exception;
      this.logger.error(`unhandled error requestId=${requestId} type=${name}`);
    }
    if (status === 429) {
      // Every 429 is the standard RATE_LIMITED Error with details.retryAfterSeconds equal to Retry-After. The general
      // throttler's own text ("ThrottlerException: Too Many Requests") never reaches clients.
      const seconds = retryAfterSeconds(res, body.details?.retryAfterSeconds);
      res.setHeader('Retry-After', String(seconds));
      if (exception instanceof ThrottlerException) body.message = RATE_LIMITED_MESSAGE;
      body = { ...body, code: 'RATE_LIMITED', details: { ...body.details, retryAfterSeconds: seconds } };
    }
    res.status(status).json(body);
  }
}

/**
 * Every 429 carries a plain `Retry-After` in whole seconds (>= 1). Sources, in order: an explicit
 * `details.retryAfterSeconds` (login failure limiter); else the throttler's own headers. @nestjs/throttler v6 names
 * them `Retry-After-<throttle>` for named throttles (only `default` gets a bare `Retry-After`), so the longest of those
 * wins; else 60.
 */
function retryAfterSeconds(res: Response, fromDetails: unknown): number {
  const positive = (v: unknown) => {
    const n = Math.ceil(Number(v));
    return Number.isFinite(n) && n >= 1 ? n : null;
  };
  const explicit = positive(fromDetails);
  if (explicit) return explicit;
  const fromHeaders = res
    .getHeaderNames()
    .filter((h) => /^retry-after(-.+)?$/i.test(h))
    .map((h) => positive(res.getHeader(h)))
    .filter((n): n is number => n !== null);
  return fromHeaders.length ? Math.max(...fromHeaders) : 60;
}

/** body-parser errors (too large, bad JSON) carry `status` and `type`. */
function bodyParserStatus(exception: unknown): number | null {
  if (!exception || typeof exception !== 'object') return null;
  const e = exception as { status?: unknown; type?: unknown };
  if (typeof e.type === 'string' && typeof e.status === 'number' && e.status >= 400 && e.status < 500) return e.status;
  return null;
}
