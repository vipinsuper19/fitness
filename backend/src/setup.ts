import { RequestMethod } from '@nestjs/common';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';
import helmet from 'helmet';

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
  app.enableShutdownHooks();
  const origins = env.CORS_ORIGINS?.split(',').map((o) => o.trim()).filter(Boolean);
  if (origins && origins.length > 0) app.enableCors({ origin: origins });

  const swagger = env.SWAGGER_ENABLED ?? env.NODE_ENV !== 'production';
  if (swagger) {
    const config = new DocumentBuilder().setTitle('Fitness API').setVersion('v1').addBearerAuth().build();
    SwaggerModule.setup('docs', app, () => SwaggerModule.createDocument(app, config));
  }
}
