/**
 * T14 (backend) over HTTP against Postgres (DB_TESTS=1): food search and
 * custom foods, food and water logs, and daily totals.
 */
import { randomUUID } from 'node:crypto';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { seed } from '../prisma/seed';
import { seedId } from '../prisma/seed-data/ids';
import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;

const ROTI = seedId('food', 'roti'); // 1 piece: 120 kcal, 3.5 P, 20 C, 3 F, 2.5 fibre
const DAL = seedId('food', 'dal-tadka');
const DAY = '2026-10-20';

describeDb('T14: nutrition (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const http = () => request(app.getHttpServer());
  const api = (token: string) => {
    const auth = <T extends request.Test>(r: T) => r.set('Authorization', `Bearer ${token}`);
    const keyed = <T extends request.Test>(r: T, key: string | null) => (key === null ? auth(r) : auth(r).set('Idempotency-Key', key));
    return {
      get: (url: string) => auth(http().get(url)),
      post: (url: string, key: string | null = randomUUID()) => keyed(http().post(url), key),
      patch: (url: string, key: string | null = null) => keyed(http().patch(url), key),
      delete: (url: string, key: string | null = null) => keyed(http().delete(url), key),
    };
  };
  const signUp = async () =>
    (await http().post('/api/v1/auth/register').send({ email: `t14-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201)).body
      .accessToken as string;
  const custom = { name: 'Ghar ka poha', servingSize: 1, servingUnit: 'plate', gramsPerServing: 150, nutrients: { energyKcal: 250, proteinG: 5, carbsG: 45, fatG: 6 }, isVegetarian: true };

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await seed(prisma);
  });
  afterAll(async () => {
    await app.close();
  });

  describe('foods', () => {
    it('searches the catalog in English or Hindi, in household servings', async () => {
      const t = await signUp();
      const res = (await api(t).get('/api/v1/foods/search?query=roti').expect(200)).body;
      const roti = res.items.find((f: { id: string }) => f.id === ROTI);
      expect(roti).toMatchObject({ servingSize: 1, servingUnit: 'piece', gramsPerServing: 40, source: 'catalog', nutrients: { energyKcal: 120, proteinG: 3.5 } });
      const hi = (await api(t).get(`/api/v1/foods/search?query=${encodeURIComponent('रोटी')}`).expect(200)).body;
      expect(hi.items.map((f: { id: string }) => f.id)).toContain(ROTI);
      await api(t).get('/api/v1/foods/search').expect(400);
      await api(t).get('/api/v1/foods/search?query=%20').expect(400);
    });

    it('custom foods are visible only to their owner', async () => {
      const a = await signUp();
      const b = await signUp();
      const food = (await api(a).post('/api/v1/foods').send(custom).expect(201)).body;
      expect(food).toMatchObject({ source: 'custom', name: 'Ghar ka poha', nutrients: { energyKcal: 250, fiberG: null } });
      expect((await api(a).get('/api/v1/foods/search?query=poha').expect(200)).body.items.map((f: { id: string }) => f.id)).toContain(food.id);
      expect((await api(b).get('/api/v1/foods/search?query=poha').expect(200)).body.items.map((f: { id: string }) => f.id)).not.toContain(food.id);
      const logged = await api(b).post('/api/v1/food-logs').send({ foodId: food.id, date: DAY, mealType: 'breakfast', servings: 1 }).expect(404);
      expect(logged.body.code).toBe('NOT_FOUND');
    });

    it('validates custom foods and replays a retried create', async () => {
      const t = await signUp();
      await api(t).post('/api/v1/foods').send({ ...custom, servingSize: 0 }).expect(400);
      await api(t).post('/api/v1/foods').send({ ...custom, nutrients: { ...custom.nutrients, energyKcal: 9000 } }).expect(400);
      await api(t).post('/api/v1/foods').send({ ...custom, nutrients: { energyKcal: 1 } }).expect(400);
      await api(t).post('/api/v1/foods').send({ ...custom, servingUnit: 'bowl' }).expect(400);
      const key = randomUUID();
      const a = (await api(t).post('/api/v1/foods', key).send(custom).expect(201)).body;
      const b = await api(t).post('/api/v1/foods', key).send(custom).expect(201);
      expect(b.body.id).toBe(a.id);
      expect(b.headers['idempotent-replayed']).toBe('true');
    });

    it('pages search results with validated cursors', async () => {
      const t = await signUp();
      const all = (await api(t).get('/api/v1/foods/search?query=a&limit=100').expect(200)).body.items.map((f: { id: string }) => f.id);
      const seen: string[] = [];
      let cursor: string | null = null;
      do {
        const url: string = `/api/v1/foods/search?query=a&limit=9${cursor ? `&cursor=${cursor}` : ''}`;
        const page: { items: { id: string }[]; nextCursor: string | null } = (await api(t).get(url).expect(200)).body;
        seen.push(...page.items.map((f) => f.id));
        cursor = page.nextCursor;
      } while (cursor);
      expect(seen).toEqual(all);
      const forged = Buffer.from(JSON.stringify({ name: 'Roti', id: 'x' })).toString('base64url');
      const bad = await api(t).get(`/api/v1/foods/search?query=a&cursor=${forged}`).expect(400);
      expect(bad.body.details.fields).toHaveProperty('cursor');
    });
  });

  describe('food logs', () => {
    it('computes nutrients from servings, keeps user overrides, and restores with null', async () => {
      const t = await signUp();
      const missing = await api(t).post('/api/v1/food-logs', null).send({ foodId: ROTI, date: DAY, mealType: 'lunch', servings: 2 }).expect(400);
      expect(missing.body.details.fields).toHaveProperty(['Idempotency-Key']);
      const log = (await api(t).post('/api/v1/food-logs').send({ foodId: ROTI, date: DAY, mealType: 'lunch', servings: 2.5 }).expect(201)).body;
      expect(log).toMatchObject({ foodName: 'Roti / chapati (whole wheat)', date: DAY, servings: 2.5, nutrientsEdited: false, nutrients: { energyKcal: 300, proteinG: 8.8, carbsG: 50, fatG: 7.5, fiberG: 6.3 } });

      const more = (await api(t).patch(`/api/v1/food-logs/${log.id}`).send({ servings: 3 }).expect(200)).body;
      expect(more.nutrients.energyKcal).toBe(360);
      const own = (await api(t).patch(`/api/v1/food-logs/${log.id}`).send({ nutrientsOverride: { energyKcal: 330, proteinG: 9, carbsG: 55, fatG: 8 } }).expect(200)).body;
      expect(own).toMatchObject({ nutrientsEdited: true, nutrients: { energyKcal: 330, fiberG: null } });
      const kept = (await api(t).patch(`/api/v1/food-logs/${log.id}`).send({ servings: 4, mealType: 'dinner' }).expect(200)).body;
      expect(kept).toMatchObject({ nutrientsEdited: true, servings: 4, mealType: 'dinner', nutrients: { energyKcal: 330 } });
      const restored = (await api(t).patch(`/api/v1/food-logs/${log.id}`).send({ nutrientsOverride: null }).expect(200)).body;
      expect(restored).toMatchObject({ nutrientsEdited: false, nutrients: { energyKcal: 480 } });

      const day = (await api(t).get(`/api/v1/food-logs?date=${DAY}`).expect(200)).body.items;
      expect(day.map((l: { id: string }) => l.id)).toEqual([log.id]);
      expect((await api(t).get('/api/v1/food-logs?date=2026-10-21').expect(200)).body.items).toEqual([]);
      await api(t).get('/api/v1/food-logs').expect(400);
      await api(t).get('/api/v1/food-logs?date=2026-13-01').expect(400);
    });

    it('validates entries', async () => {
      const t = await signUp();
      await api(t).post('/api/v1/food-logs').send({ foodId: ROTI, date: DAY, mealType: 'brunch', servings: 1 }).expect(400);
      await api(t).post('/api/v1/food-logs').send({ foodId: ROTI, date: DAY, mealType: 'lunch', servings: 0 }).expect(400);
      await api(t).post('/api/v1/food-logs').send({ foodId: ROTI, date: DAY, mealType: 'lunch', servings: 101 }).expect(400);
      await api(t).post('/api/v1/food-logs').send({ foodId: randomUUID(), date: DAY, mealType: 'lunch', servings: 1 }).expect(404);
      await api(t).post('/api/v1/food-logs').send({ foodId: ROTI, date: DAY, mealType: 'lunch', servings: 1, nutrientsOverride: { energyKcal: -1, proteinG: 0, carbsG: 0, fatG: 0 } }).expect(400);
    });

    it("hides another user's entries: 404 on edit and delete, and absent from lists", async () => {
      const a = await signUp();
      const b = await signUp();
      const log = (await api(a).post('/api/v1/food-logs').send({ foodId: DAL, date: DAY, mealType: 'dinner', servings: 1 }).expect(201)).body;
      await api(b).patch(`/api/v1/food-logs/${log.id}`).send({ servings: 9 }).expect(404);
      await api(b).delete(`/api/v1/food-logs/${log.id}`).expect(404);
      expect((await api(b).get(`/api/v1/food-logs?date=${DAY}`).expect(200)).body.items).toEqual([]);
      expect((await api(a).get(`/api/v1/food-logs?date=${DAY}`).expect(200)).body.items).toHaveLength(1);
    });

    it('a retried create logs once; a retried delete replays 204', async () => {
      const t = await signUp();
      const key = randomUUID();
      const body = { foodId: ROTI, date: DAY, mealType: 'snack', servings: 1 };
      const results = await Promise.all([1, 2, 3].map(() => api(t).post('/api/v1/food-logs', key).send(body)));
      for (const r of results) expect([201, 409]).toContain(r.status);
      const later = await api(t).post('/api/v1/food-logs', key).send(body).expect(201);
      expect((await api(t).get(`/api/v1/food-logs?date=${DAY}`).expect(200)).body.items).toHaveLength(1);
      const del = randomUUID();
      await api(t).delete(`/api/v1/food-logs/${later.body.id}`, del).expect(204);
      expect((await api(t).delete(`/api/v1/food-logs/${later.body.id}`, del).expect(204)).headers['idempotent-replayed']).toBe('true');
      await api(t).delete(`/api/v1/food-logs/${later.body.id}`).expect(404);
    });
  });

  describe('water and daily totals', () => {
    it('logs, edits and deletes water, owner-only', async () => {
      const a = await signUp();
      const b = await signUp();
      const w = (await api(a).post('/api/v1/water-logs').send({ date: DAY, amountMl: 250 }).expect(201)).body;
      expect(w).toMatchObject({ date: DAY, amountMl: 250 });
      expect((await api(a).patch(`/api/v1/water-logs/${w.id}`).send({ date: DAY, amountMl: 300 }).expect(200)).body.amountMl).toBe(300);
      await api(a).patch(`/api/v1/water-logs/${w.id}`).send({ amountMl: 300 }).expect(400);
      await api(a).post('/api/v1/water-logs').send({ date: DAY, amountMl: 0 }).expect(400);
      await api(a).post('/api/v1/water-logs').send({ date: DAY, amountMl: 5001 }).expect(400);
      await api(a).post('/api/v1/water-logs', null).send({ date: DAY, amountMl: 200 }).expect(400);
      await api(b).patch(`/api/v1/water-logs/${w.id}`).send({ date: DAY, amountMl: 1 }).expect(404);
      await api(b).delete(`/api/v1/water-logs/${w.id}`).expect(404);
      expect((await api(a).get(`/api/v1/water-logs?date=${DAY}`).expect(200)).body.items).toHaveLength(1);
      await api(a).delete(`/api/v1/water-logs/${w.id}`).expect(204);
      expect((await api(a).get(`/api/v1/water-logs?date=${DAY}`).expect(200)).body.items).toEqual([]);
    });

    it('totals a day by meal against the profile targets; missing optional nutrients stay null', async () => {
      const t = await signUp();
      const empty = (await api(t).get(`/api/v1/nutrition/daily?date=${DAY}`).expect(200)).body;
      expect(empty).toMatchObject({ date: DAY, waterMl: 0, calorieTargetKcal: null, waterTargetMl: null, totals: { energyKcal: 0, fiberG: null } });

      await http().put('/api/v1/me/profile').set('Authorization', `Bearer ${t}`).send({ dailyCalorieTargetKcal: 1800, dailyWaterTargetMl: 2500 }).expect(200);
      await api(t).post('/api/v1/food-logs').send({ foodId: ROTI, date: DAY, mealType: 'lunch', servings: 2 }).expect(201);
      await api(t).post('/api/v1/food-logs').send({ foodId: ROTI, date: DAY, mealType: 'dinner', servings: 1, nutrientsOverride: { energyKcal: 150, proteinG: 4, carbsG: 22, fatG: 5 } }).expect(201);
      await api(t).post('/api/v1/food-logs').send({ foodId: ROTI, date: '2026-10-21', mealType: 'lunch', servings: 5 }).expect(201);
      await api(t).post('/api/v1/water-logs').send({ date: DAY, amountMl: 500 }).expect(201);
      await api(t).post('/api/v1/water-logs').send({ date: DAY, amountMl: 750 }).expect(201);

      const d = (await api(t).get(`/api/v1/nutrition/daily?date=${DAY}`).expect(200)).body;
      expect(d).toMatchObject({ waterMl: 1250, calorieTargetKcal: 1800, waterTargetMl: 2500 });
      expect(d.totals).toEqual({ energyKcal: 390, proteinG: 11, carbsG: 62, fatG: 11, fiberG: 5, sugarG: null, sodiumMg: null });
      expect(d.byMeal.lunch.energyKcal).toBe(240);
      expect(d.byMeal.dinner).toMatchObject({ energyKcal: 150, fiberG: null });
      expect(d.byMeal.breakfast).toMatchObject({ energyKcal: 0, fiberG: null });
      await api(t).get('/api/v1/nutrition/daily').expect(400);

      const other = await signUp();
      expect((await api(other).get(`/api/v1/nutrition/daily?date=${DAY}`).expect(200)).body.totals.energyKcal).toBe(0);
    });
  });

  it('nutrition writes never touch XP', async () => {
    expect(await prisma.xPTransaction.count({ where: { user: { email: { startsWith: 't14-' } } } })).toBe(0);
  });
});
