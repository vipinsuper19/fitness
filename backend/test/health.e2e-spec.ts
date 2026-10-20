import type { NestExpressApplication } from '@nestjs/platform-express';
import { Test } from '@nestjs/testing';
import request from 'supertest';

import { AppModule } from '../src/app.module';
import { ENV } from '../src/config/config.module';
import { loadEnv } from '../src/config/env';
import { PrismaService } from '../src/prisma/prisma.service';
import { configureApp } from '../src/setup';

describe('GET /health', () => {
  let app: NestExpressApplication;
  let dbUp = true;

  beforeAll(async () => {
    const env = loadEnv({
      NODE_ENV: 'test',
      DATABASE_URL: 'postgresql://user:pass@localhost:5432/fitness',
      JWT_ACCESS_SECRET: 'a'.repeat(32),
      JWT_REFRESH_SECRET: 'b'.repeat(32),
    });
    const moduleRef = await Test.createTestingModule({ imports: [AppModule] })
      .overrideProvider(ENV)
      .useValue(env)
      .overrideProvider(PrismaService)
      .useValue({
        $queryRaw: async () => {
          if (!dbUp) throw new Error('db down');
          return [{ '?column?': 1 }];
        },
        $disconnect: async () => undefined,
      })
      .compile();
    app = moduleRef.createNestApplication<NestExpressApplication>({ bodyParser: false });
    configureApp(app, env);
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  it('returns ok without touching the database', async () => {
    const res = await request(app.getHttpServer()).get('/health').expect(200);
    expect(res.body.status).toBe('ok');
    expect(res.body.db).toBeUndefined();
  });

  it('reports db up or down on ?db=true and still returns 200', async () => {
    dbUp = true;
    expect((await request(app.getHttpServer()).get('/health?db=true').expect(200)).body.db).toBe('up');
    dbUp = false;
    expect((await request(app.getHttpServer()).get('/health?db=true').expect(200)).body.db).toBe('down');
  });

  it('serves the API under /api/v1 and swagger JSON outside production', async () => {
    await request(app.getHttpServer()).get('/api/v1/health').expect(404);
    await request(app.getHttpServer()).get('/docs-json').expect(200);
  });
});
