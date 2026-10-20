import { ArgumentsHost, Catch, ExceptionFilter, HttpException, HttpStatus, Logger } from '@nestjs/common';
import type { Response } from 'express';

import type { RequestWithId } from './request-id';

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
      const retryAfter = res.getHeader('Retry-After');
      if (!retryAfter) res.setHeader('Retry-After', '60');
    }
    res.status(status).json(body);
  }
}

/** body-parser errors (too large, bad JSON) carry `status` and `type`. */
function bodyParserStatus(exception: unknown): number | null {
  if (!exception || typeof exception !== 'object') return null;
  const e = exception as { status?: unknown; type?: unknown };
  if (typeof e.type === 'string' && typeof e.status === 'number' && e.status >= 400 && e.status < 500) return e.status;
  return null;
}
