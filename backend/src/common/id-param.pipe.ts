import { HttpStatus, Injectable, PipeTransform } from '@nestjs/common';

import { ApiError } from './api-error';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const notFound = (what = 'Resource') => new ApiError(HttpStatus.NOT_FOUND, 'NOT_FOUND', `${what} not found.`);

/** Path ids: anything that is not a UUID is simply 404, like a missing or foreign id. */
@Injectable()
export class IdParamPipe implements PipeTransform<string, string> {
  transform(value: string): string {
    if (typeof value !== 'string' || !UUID.test(value)) throw notFound();
    return value.toLowerCase();
  }
}
