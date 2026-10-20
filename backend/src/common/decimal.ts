/** Prisma Decimal (or null) to a JSON number (or null). */
export function num(value: { toNumber(): number } | null | undefined): number | null {
  return value == null ? null : value.toNumber();
}

/** YYYY-MM-DD for a `@db.Date` column (stored at UTC midnight). */
export function isoDate(value: Date | null | undefined): string | null {
  return value == null ? null : value.toISOString().slice(0, 10);
}

/** Parses YYYY-MM-DD into the UTC-midnight Date Prisma expects for `@db.Date`. */
export function parseIsoDate(value: string | null | undefined): Date | null | undefined {
  if (value === undefined) return undefined;
  if (value === null) return null;
  return new Date(`${value}T00:00:00.000Z`);
}
