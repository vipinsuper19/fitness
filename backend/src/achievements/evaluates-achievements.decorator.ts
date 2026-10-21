import type { AchievementsService } from './achievements.service';

/**
 * After the decorated write resolves (i.e. committed), schedules a background
 * badge/challenge evaluation for the user in the first argument. Failed writes
 * schedule nothing; the evaluation itself can never fail or delay the write.
 * The class must have an `achievements: AchievementsService` field.
 */
export function EvaluatesAchievements(): MethodDecorator {
  return (_target, _key, descriptor: PropertyDescriptor) => {
    const original = descriptor.value as (...args: unknown[]) => Promise<unknown>;
    descriptor.value = async function (this: { achievements: AchievementsService }, ...args: unknown[]) {
      const result = await original.apply(this, args);
      this.achievements.afterWrite(args[0] as string);
      return result;
    };
    return descriptor;
  };
}
