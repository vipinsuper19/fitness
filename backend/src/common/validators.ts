import { registerDecorator, ValidateIf, ValidationOptions } from 'class-validator';

/** Field may be omitted but, when present, must not be null. */
export const IsDefinedWhenPresent = () => ValidateIf((_o: object, v: unknown) => v !== undefined);

/** Calendar dates accepted by the API: years 1900 to 2100 inclusive. */
export const MIN_YEAR = 1900;
export const MAX_YEAR = 2100;

/** A real calendar date in YYYY-MM-DD form, years 1900–2100 (rejects 2026-02-30). */
export function IsCalendarDate(options?: ValidationOptions) {
  return (target: object, propertyName: string) =>
    registerDecorator({
      name: 'isCalendarDate',
      target: target.constructor,
      propertyName,
      options: { message: `${propertyName} must be a date (YYYY-MM-DD) between ${MIN_YEAR} and ${MAX_YEAR}`, ...options },
      validator: {
        validate(value: unknown) {
          if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
          const year = Number(value.slice(0, 4));
          if (year < MIN_YEAR || year > MAX_YEAR) return false;
          const d = new Date(`${value}T00:00:00.000Z`);
          return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === value;
        },
      },
    });
}

/** An IANA timezone the runtime knows (e.g. Asia/Kolkata). */
export function IsIanaTimezone(options?: ValidationOptions) {
  return (target: object, propertyName: string) =>
    registerDecorator({
      name: 'isIanaTimezone',
      target: target.constructor,
      propertyName,
      options: { message: `${propertyName} must be an IANA timezone such as Asia/Kolkata`, ...options },
      validator: {
        validate(value: unknown) {
          if (typeof value !== 'string' || value.length > 64) return false;
          try {
            new Intl.DateTimeFormat('en-IN', { timeZone: value });
            return true;
          } catch {
            return false;
          }
        },
      },
    });
}
