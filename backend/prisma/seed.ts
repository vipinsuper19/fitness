/**
 * Idempotent seed for the read-only catalogs (exercises, foods). Ids are
 * deterministic UUIDv5 values, so re-running it updates rows in place and
 * never creates duplicates. It never touches user data.
 *
 * Run: `npm run db:seed` (uses DATABASE_URL).
 */
import { PrismaPg } from '@prisma/adapter-pg';

import { PrismaClient } from '../src/generated/prisma/client';
import { EXERCISES } from './seed-data/exercises';
import { FOODS } from './seed-data/foods';
import { seedId } from './seed-data/ids';

export async function seed(prisma: PrismaClient): Promise<{ exercises: number; foods: number }> {
  for (const e of EXERCISES) {
    const data = {
      slug: e.slug,
      name: e.name,
      nameHi: e.nameHi ?? null,
      primaryMuscle: e.primaryMuscle,
      secondaryMuscles: e.secondaryMuscles,
      equipment: e.equipment,
      trackingType: e.trackingType,
      difficulty: e.difficulty,
      instructions: e.instructions,
    };
    const id = seedId('exercise', e.slug);
    await prisma.exercise.upsert({ where: { id }, create: { id, ...data }, update: data });
  }
  for (const f of FOODS) {
    const data = {
      slug: f.slug,
      name: f.name,
      nameHi: f.nameHi ?? null,
      servingSize: f.servingSize,
      servingUnit: f.servingUnit,
      gramsPerServing: f.gramsPerServing,
      energyKcal: f.energyKcal,
      proteinG: f.proteinG,
      carbsG: f.carbsG,
      fatG: f.fatG,
      fiberG: f.fiberG ?? null,
      isVegetarian: f.isVegetarian,
      source: 'catalog' as const,
    };
    const id = seedId('food', f.slug);
    await prisma.foodItem.upsert({ where: { id }, create: { id, ...data }, update: data });
  }
  return { exercises: EXERCISES.length, foods: FOODS.length };
}

async function main(): Promise<void> {
  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) throw new Error('DATABASE_URL is not set');
  const prisma = new PrismaClient({ adapter: new PrismaPg({ connectionString }) });
  try {
    const counts = await seed(prisma);
    console.log(`Seeded ${counts.exercises} exercises and ${counts.foods} foods.`);
  } finally {
    await prisma.$disconnect();
  }
}

if (require.main === module) {
  main().catch((error: unknown) => {
    console.error(error instanceof Error ? error.message : error);
    process.exit(1);
  });
}
