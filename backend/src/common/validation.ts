import { BadRequestException, ValidationError, ValidationPipe } from '@nestjs/common';

function collect(errors: ValidationError[], prefix = '', out: Record<string, string> = {}): Record<string, string> {
  for (const error of errors) {
    const path = prefix ? `${prefix}.${error.property}` : error.property;
    const first = error.constraints ? Object.values(error.constraints)[0] : undefined;
    if (first) out[path] = first;
    if (error.children?.length) collect(error.children, path, out);
  }
  return out;
}

/** Global pipe: unknown fields are rejected; errors use the contract shape. */
export function globalValidationPipe(): ValidationPipe {
  return new ValidationPipe({
    whitelist: true,
    forbidNonWhitelisted: true,
    transform: true,
    stopAtFirstError: true,
    exceptionFactory: (errors) =>
      new BadRequestException({
        code: 'VALIDATION_FAILED',
        message: 'Some fields are invalid.',
        details: { fields: collect(errors) },
      }),
  });
}
