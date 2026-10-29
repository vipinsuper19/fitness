import { Injectable } from '@nestjs/common';

import { num } from '../common/decimal';
import { notFound } from '../common/id-param.pipe';
import type { UserProfile } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { recordTargetChange, snapshotOf } from '../targets/target-history';
import type { ProfileUpdateDto } from './profile.dto';

export interface ProfileView {
  userId: string;
  displayName: string | null;
  birthYear: number | null;
  sex: string | null;
  heightCm: number | null;
  weightKg: number | null;
  activityLevel: string | null;
  experienceLevel: string | null;
  dietPreference: string | null;
  unitSystem: string;
  timezone: string;
  locale: string;
  currency: 'INR';
  dailyCalorieTargetKcal: number | null;
  dailyWaterTargetMl: number | null;
  dailyProteinTargetG: number | null;
  dailyCarbsTargetG: number | null;
  dailyFatTargetG: number | null;
  onboardingCompleted: boolean;
  updatedAt: string;
}

export function toProfileView(p: UserProfile, displayName: string | null): ProfileView {
  return {
    userId: p.userId,
    displayName,
    birthYear: p.birthYear,
    sex: p.sex,
    heightCm: num(p.heightCm),
    weightKg: num(p.weightKg),
    activityLevel: p.activityLevel,
    experienceLevel: p.experienceLevel,
    dietPreference: p.dietPreference,
    unitSystem: p.unitSystem,
    timezone: p.timezone,
    locale: p.locale,
    currency: 'INR',
    dailyCalorieTargetKcal: p.dailyCalorieTargetKcal,
    dailyWaterTargetMl: p.dailyWaterTargetMl,
    // 0 is normalized to null on write; also mask any legacy 0 on read.
    dailyProteinTargetG: p.dailyProteinTargetG || null,
    dailyCarbsTargetG: p.dailyCarbsTargetG || null,
    dailyFatTargetG: p.dailyFatTargetG || null,
    onboardingCompleted: p.onboardingCompleted,
    updatedAt: p.updatedAt.toISOString(),
  };
}

const round = (v: number | null | undefined, places: number) =>
  v == null ? v : Math.round(v * 10 ** places) / 10 ** places;

const macro = (v: number | null | undefined) => (v === 0 ? null : v);

@Injectable()
export class ProfileService {
  constructor(private readonly prisma: PrismaService) {}

  async get(userId: string): Promise<ProfileView> {
    const user = await this.prisma.user.findUnique({ where: { id: userId }, include: { profile: true } });
    if (!user) throw notFound('Profile');
    // Registration always creates the profile; heal a missing row anyway.
    const profile = user.profile ?? (await this.prisma.userProfile.create({ data: { userId } }));
    return toProfileView(profile, user.displayName);
  }

  async update(userId: string, dto: ProfileUpdateDto): Promise<ProfileView> {
    const { displayName, ...fields } = dto;
    const data = {
      ...fields,
      heightCm: round(fields.heightCm, 1),
      weightKg: round(fields.weightKg, 2),
      // Macro targets: omitted keeps (undefined), null clears, 0 is stored as null.
      dailyProteinTargetG: macro(fields.dailyProteinTargetG),
      dailyCarbsTargetG: macro(fields.dailyCarbsTargetG),
      dailyFatTargetG: macro(fields.dailyFatTargetG),
    };
    return this.prisma.$transaction(async (tx) => {
      const user =
        displayName !== undefined
          ? await tx.user.update({ where: { id: userId }, data: { displayName } })
          : await tx.user.findUniqueOrThrow({ where: { id: userId } });
      const before = await tx.userProfile.findUnique({ where: { userId } });
      const profile = await tx.userProfile.upsert({ where: { userId }, create: { userId, ...data }, update: data });
      // Dated target history: a target change writes/replaces today's row, in the timezone after
      // this PUT, in the same transaction; no change writes nothing (docs/specs/target-history.md).
      await recordTargetChange(tx, userId, snapshotOf(before), snapshotOf(profile), profile.timezone, new Date());
      return toProfileView(profile, user.displayName);
    });
  }
}
