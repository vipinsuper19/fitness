import { RequestMethod } from '@nestjs/common';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { DocumentBuilder, SwaggerModule, type OpenAPIObject } from '@nestjs/swagger';
import type { SchemaObject } from '@nestjs/swagger/dist/interfaces/open-api-spec.interface';
import helmet from 'helmet';

import { connectionCloseWhileDraining } from './common/graceful-shutdown';
import { HttpExceptionFilter } from './common/http-exception.filter';
import { requestIdMiddleware } from './common/request-id';
import { requestLogMiddleware } from './common/request-log';
import { globalValidationPipe } from './common/validation';
import type { Env } from './config/env';

/** JSON bodies above this are rejected with 413 PAYLOAD_TOO_LARGE. */
export const BODY_LIMIT = '100kb';

/**
 * Shared by main.ts and tests. Create the app with `{ bodyParser: false }`
 * so the size-limited parsers below are the only ones.
 */
export function configureApp(app: NestExpressApplication, env: Env): void {
  // Render terminates TLS in front of the service; trust one proxy hop for req.ip.
  app.set('trust proxy', env.TRUST_PROXY_HOPS);
  app.use(requestIdMiddleware);
  if (env.REQUEST_LOG === 'on') app.use(requestLogMiddleware);
  app.use(helmet());
  app.useBodyParser('json', { limit: BODY_LIMIT });
  app.useBodyParser('urlencoded', { limit: BODY_LIMIT, extended: false });
  app.useGlobalPipes(globalValidationPipe());
  app.useGlobalFilters(new HttpExceptionFilter());
  app.setGlobalPrefix('api/v1', { exclude: [
      { path: 'health', method: RequestMethod.GET },
      { path: 'health/deep', method: RequestMethod.GET },
    ] });
  // Signals are handled by installShutdownSignals (main.ts), not enableShutdownHooks: see common/graceful-shutdown.ts.
  app.use(connectionCloseWhileDraining);
  const origins = env.CORS_ORIGINS?.split(',').map((o) => o.trim()).filter(Boolean);
  if (origins && origins.length > 0) app.enableCors({ origin: origins });

  const swagger = env.SWAGGER_ENABLED ?? env.NODE_ENV !== 'production';
  if (swagger) {
    const config = swaggerConfig();
    SwaggerModule.setup('docs', app, () => SwaggerModule.createDocument(app, config));
  }
}

/** Error body (contract `Error`) and the error responses any operation may return; per-operation codes are in contracts/openapi.yaml. */
const ERROR_SCHEMA = {
  type: 'object',
  required: ['code', 'message', 'requestId'],
  properties: {
    code: { type: 'string', description: 'Stable machine code (UPPER_SNAKE_CASE), e.g. VALIDATION_FAILED, NOT_FOUND, RATE_LIMITED' },
    message: { type: 'string', description: 'Human-readable, safe to show' },
    details: { type: 'object', additionalProperties: true },
    requestId: { type: 'string', description: 'Same as the X-Request-Id response header' },
  },
} as const;

export function swaggerConfig(): Omit<OpenAPIObject, 'paths'> {
  const error = (status: number, description: string) => ({ status, description, schema: ERROR_SCHEMA as unknown as SchemaObject });
  return new DocumentBuilder()
    .setTitle('Fitness API')
    .setVersion('v1')
    .addBearerAuth()
    .addGlobalResponse(
      error(400, 'VALIDATION_FAILED (details.fields) or another 400 code'),
      error(401, 'UNAUTHENTICATED (routes that need a bearer token)'),
      error(413, 'PAYLOAD_TOO_LARGE: JSON body over the size cap'),
      error(429, 'RATE_LIMITED, with Retry-After'),
      error(500, 'INTERNAL'),
    )
    .build();
}
