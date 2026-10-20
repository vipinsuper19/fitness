import 'reflect-metadata';

import { NestFactory } from '@nestjs/core';

import { AppModule } from './app.module';
import { ENV } from './config/config.module';
import type { Env } from './config/env';
import { configureApp } from './setup';

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule);
  const env = app.get<Env>(ENV);
  configureApp(app, env);
  // Render injects PORT; bind all interfaces inside the container.
  await app.listen(env.PORT, '0.0.0.0');
}

void bootstrap();
