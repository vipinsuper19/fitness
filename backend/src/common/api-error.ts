import { HttpException, HttpStatus } from '@nestjs/common';

/** Throw this for a contract error code: `{ code, message, details? }`. */
export class ApiError extends HttpException {
  constructor(status: HttpStatus, code: string, message: string, details?: Record<string, unknown>) {
    super({ code, message, ...(details ? { details } : {}) }, status);
  }
}
