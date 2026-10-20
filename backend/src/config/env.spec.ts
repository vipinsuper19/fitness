import { EnvValidationError, loadEnv } from './env';

const base = {
  DATABASE_URL: 'postgresql://user:pass@localhost:5432/fitness',
  JWT_ACCESS_SECRET: 'a'.repeat(32),
  JWT_REFRESH_SECRET: 'b'.repeat(32),
};

describe('loadEnv', () => {
  it('applies defaults', () => {
    const env = loadEnv(base);
    expect(env.PORT).toBe(3000);
    expect(env.JWT_ACCESS_TTL_SECONDS).toBe(900);
    expect(env.NODE_ENV).toBe('development');
  });

  it('rejects a missing DATABASE_URL and short JWT secrets', () => {
    expect(() => loadEnv({ JWT_ACCESS_SECRET: 'short', JWT_REFRESH_SECRET: 'short' })).toThrow(
      EnvValidationError,
    );
  });

  it('never echoes secret values in the error', () => {
    const secret = 'super-secret-but-too-short';
    try {
      loadEnv({ ...base, JWT_ACCESS_SECRET: secret });
      throw new Error('expected failure');
    } catch (error) {
      expect(error).toBeInstanceOf(EnvValidationError);
      expect(String((error as Error).message)).not.toContain(secret);
    }
  });

  it('rejects a non-postgres database URL', () => {
    expect(() => loadEnv({ ...base, DATABASE_URL: 'mysql://x@y/z' })).toThrow(EnvValidationError);
  });
});
