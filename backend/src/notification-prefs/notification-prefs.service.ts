import { HttpStatus, Injectable } from '@nestjs/common';

import { ApiError } from '../common/api-error';
import { DEFAULT_TIMEZONE } from '../achievements/achievements.rules';
import type { Prisma } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { DEFAULTS, effectiveTypes, merge, parsePatch, Prefs, PrefsView, samePrefs, storedTypes } from './notification-prefs.rules';

type Db = PrismaService | Prisma.TransactionClient;
type Row = { enabled: boolean; types: unknown; quietEnabled: boolean; quietStart: Date; quietEnd: Date; updatedAt: Date };

const toTime = (hhmm: string) => new Date(`1970-01-01T${hhmm}:00Z`);
const fromTime = (d: Date) => d.toISOString().slice(11, 16);

function fromRow(row: Row | null): Prefs {
  if (!row) return { ...DEFAULTS, types: { ...DEFAULTS.types }, quietHours: { ...DEFAULTS.quietHours } };
  return { enabled: row.enabled, types: effectiveTypes(row.types), quietHours: { enabled: row.quietEnabled, start: fromTime(row.quietStart), end: fromTime(row.quietEnd) } };
}

/** The user's preferences with defaults merged in (no row = defaults). Never writes. */
export async function loadPreferences(db: Db, userId: string): Promise<PrefsView> {
  const [row, profile] = await Promise.all([
    db.notificationPreference.findUnique({ where: { userId } }),
    db.userProfile.findUnique({ where: { userId }, select: { timezone: true } }),
  ]);
  return { ...fromRow(row), timezone: profile?.timezone ?? DEFAULT_TIMEZONE, updatedAt: row ? row.updatedAt.toISOString() : null };
}

const invalid = (fields: Record<string, string>) => new ApiError(HttpStatus.BAD_REQUEST, 'VALIDATION_FAILED', 'Some fields are invalid.', { fields });

@Injectable()
export class NotificationPrefsService {
  constructor(private readonly prisma: PrismaService) {}

  get(userId: string): Promise<PrefsView> {
    return loadPreferences(this.prisma, userId);
  }

  /** Partial merge under a per-user lock. A no-op (effective values unchanged) writes nothing. */
  async patch(userId: string, body: unknown): Promise<PrefsView> {
    const parsed = parsePatch(body);
    if ('errors' in parsed) throw invalid(parsed.errors);
    return this.prisma.$transaction(async (tx) => {
      await tx.$queryRaw`SELECT pg_advisory_xact_lock(hashtextextended(${`notification-prefs:${userId}`}, 0))::text`;
      const row = await tx.notificationPreference.findUnique({ where: { userId } });
      const cur = fromRow(row);
      const merged = merge(cur, parsed.patch);
      if ('errors' in merged) throw invalid(merged.errors);
      if (!samePrefs(cur, merged.prefs)) {
        const data = {
          enabled: merged.prefs.enabled,
          // Only the types the user has set; flags for removed types are dropped here.
          types: { ...storedTypes(row?.types), ...parsed.patch.types },
          quietEnabled: merged.prefs.quietHours.enabled,
          quietStart: toTime(merged.prefs.quietHours.start),
          quietEnd: toTime(merged.prefs.quietHours.end),
        };
        await tx.notificationPreference.upsert({ where: { userId }, create: { userId, ...data }, update: data });
      }
      return loadPreferences(tx, userId);
    });
  }
}
