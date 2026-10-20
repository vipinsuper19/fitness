import { Injectable } from '@nestjs/common';

import { decodeCursor, encodeCursor } from '../common/cursor';
import { notFound } from '../common/id-param.pipe';
import type { Exercise, Prisma } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import type { ExerciseListQuery } from './exercises.dto';

export interface ExerciseView {
  id: string;
  slug: string;
  name: string;
  nameHi: string | null;
  primaryMuscle: string;
  secondaryMuscles: string[];
  equipment: string[];
  trackingType: string;
  difficulty: string;
  instructions: string[];
  videoUrl: string | null;
}

export const toExerciseView = (e: Exercise): ExerciseView => ({
  id: e.id,
  slug: e.slug,
  name: e.name,
  nameHi: e.nameHi,
  primaryMuscle: e.primaryMuscle,
  secondaryMuscles: e.secondaryMuscles,
  equipment: e.equipment,
  trackingType: e.trackingType,
  difficulty: e.difficulty,
  instructions: e.instructions,
  videoUrl: e.videoUrl,
});

/** Read-only seeded catalog: search by text, muscle (primary or secondary) and equipment. */
@Injectable()
export class ExercisesService {
  constructor(private readonly prisma: PrismaService) {}

  async list(q: ExerciseListQuery): Promise<{ items: ExerciseView[]; nextCursor: string | null }> {
    const limit = q.limit ?? 20;
    const after = decodeCursor(q.cursor, ['name', 'id'] as const);
    const and: Prisma.ExerciseWhereInput[] = [];
    if (q.query) {
      and.push({
        OR: [
          { name: { contains: q.query, mode: 'insensitive' } },
          { nameHi: { contains: q.query, mode: 'insensitive' } },
          { slug: { contains: q.query.toLowerCase().replace(/\s+/g, '-') } },
        ],
      });
    }
    if (q.muscle) and.push({ OR: [{ primaryMuscle: q.muscle }, { secondaryMuscles: { has: q.muscle } }] });
    if (q.equipment) and.push({ equipment: { has: q.equipment } });
    if (after) and.push({ OR: [{ name: { gt: after.name } }, { name: after.name, id: { gt: after.id } }] });

    const rows = await this.prisma.exercise.findMany({
      where: { AND: and },
      orderBy: [{ name: 'asc' }, { id: 'asc' }],
      take: limit + 1,
    });
    const page = rows.slice(0, limit);
    const last = page.at(-1);
    return {
      items: page.map(toExerciseView),
      nextCursor: rows.length > limit && last ? encodeCursor({ name: last.name, id: last.id }) : null,
    };
  }

  async get(id: string): Promise<ExerciseView> {
    const row = await this.prisma.exercise.findUnique({ where: { id } });
    if (!row) throw notFound('Exercise');
    return toExerciseView(row);
  }
}
