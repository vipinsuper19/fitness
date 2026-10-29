import 'reflect-metadata';

import { NestFactory } from '@nestjs/core';
import type { NestExpressApplication } from '@nestjs/platform-express';

import { AppModule } from './app.module';
import { ENV } from './config/config.module';
import type { Env } from './config/env';
import { installShutdownSignals } from './common/graceful-shutdown';
import { configureApp } from './setup';

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create<NestExpressApplication>(AppModule, { bodyParser: false });
  const env = app.get<Env>(ENV);
  configureApp(app, env);
  // Render injects PORT; bind all interfaces inside the container.
  await app.listen(env.PORT, '0.0.0.0');
  // Render: SIGTERM, then SIGKILL after 30 s by default (docs/DEPLOY.md); drain within SHUTDOWN_GRACE_MS (10 s).
  installShutdownSignals(app, env.SHUTDOWN_GRACE_MS);
}

void bootstrap();
