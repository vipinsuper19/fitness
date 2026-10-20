import { INestApplication, RequestMethod } from '@nestjs/common';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';

import type { Env } from './config/env';

/** Shared by main.ts and tests: `/api/v1` prefix (health stays at `/health`), CORS, Swagger. */
export function configureApp(app: INestApplication, env: Env): void {
  app.setGlobalPrefix('api/v1', { exclude: [{ path: 'health', method: RequestMethod.GET }] });
  app.enableShutdownHooks();
  const origins = env.CORS_ORIGINS?.split(',').map((o) => o.trim()).filter(Boolean);
  if (origins && origins.length > 0) app.enableCors({ origin: origins });

  const swagger = env.SWAGGER_ENABLED ?? env.NODE_ENV !== 'production';
  if (swagger) {
    const config = new DocumentBuilder()
      .setTitle('Fitness API')
      .setVersion('v1')
      .addBearerAuth()
      .build();
    SwaggerModule.setup('docs', app, () => SwaggerModule.createDocument(app, config));
  }
}
