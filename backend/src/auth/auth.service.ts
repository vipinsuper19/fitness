import { HttpStatus, Injectable } from '@nestjs/common';

import { ApiError } from '../common/api-error';
import { Prisma } from '../generated/prisma/client';
import { IdempotencyService, requestHash } from '../idempotency/idempotency.service';
import { PrismaService } from '../prisma/prisma.service';
import type { LoginDto, RegisterDto } from './dto/auth.dto';
import { LoginFailureLimiter } from './login-failure-limiter';
import { PasswordService } from './password.service';
import { TokenPair, TokenService } from './token.service';

export const REGISTER_PATH = '/api/v1/auth/register';

export interface PublicUser {
  id: string;
  email: string;
  displayName: string | null;
  createdAt: string;
}

export type AuthResponse = TokenPair & { user: PublicUser };

export function toPublicUser(user: { id: string; email: string; displayName: string | null; createdAt: Date }): PublicUser {
  return { id: user.id, email: user.email, displayName: user.displayName, createdAt: user.createdAt.toISOString() };
}

/** True only when the unique violation is on users.email (not some other unique index). */
function isEmailConflict(error: Prisma.PrismaClientKnownRequestError): boolean {
  const meta = (error.meta ?? {}) as Record<string, unknown>;
  const target = meta.target;
  if (Array.isArray(target)) return target.includes('email');
  if (typeof target === 'string') return target.includes('email');
  // Driver adapters (adapter-pg) report the constraint under driverAdapterError.
  const cause = (meta.driverAdapterError as { cause?: { constraint?: { fields?: string[]; index?: string } } } | undefined)?.cause;
  const constraint = cause?.constraint;
  if (constraint?.fields) return constraint.fields.some((f) => f.replace(/"/g, '') === 'email');
  if (constraint?.index) return constraint.index.includes('email');
  return false;
}

const invalidCredentials = () =>
  new ApiError(HttpStatus.UNAUTHORIZED, 'INVALID_CREDENTIALS', 'Email or password is incorrect.');

@Injectable()
export class AuthService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly passwords: PasswordService,
    private readonly tokens: TokenService,
    private readonly idempotency: IdempotencyService,
    private readonly loginFailures: LoginFailureLimiter,
  ) {}

  /**
   * Contract: the Idempotency-Key only rejects a concurrent duplicate
   * (409 IDEMPOTENCY_IN_PROGRESS). Tokens are never stored, so a retry after
   * a finished sign-up runs again: 409 EMAIL_TAKEN, or fresh tokens if the
   * first attempt did not create the account. The password is not part of
   * the stored request hash.
   */
  async register(dto: RegisterDto, idempotencyKey?: string): Promise<AuthResponse> {
    let recordId: string | undefined;
    if (idempotencyKey) {
      const start = await this.idempotency.begin({
        scope: 'anon:register',
        key: idempotencyKey,
        method: 'POST',
        path: REGISTER_PATH,
        requestHash: requestHash({ email: dto.email, displayName: dto.displayName ?? null }),
      });
      if (start.kind === 'proceed') recordId = start.recordId;
      // 'rerun' (or an impossible 'replay': register never stores a body) runs the handler again.
    }

    try {
      const passwordHash = await this.passwords.hash(dto.password);
      const user = await this.prisma.$transaction(async (tx) => {
        const created = await tx.user.create({
          data: { email: dto.email, passwordHash, displayName: dto.displayName ?? null },
        });
        await tx.userProfile.create({ data: { userId: created.id } });
        return created;
      });
      const pair = await this.tokens.issue(user.id);
      if (recordId) await this.idempotency.complete(recordId, { status: 201, body: null, userId: user.id });
      return { ...pair, user: toPublicUser(user) };
    } catch (error) {
      if (recordId) await this.idempotency.abandon(recordId).catch(() => undefined);
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002' && isEmailConflict(error)) {
        throw new ApiError(HttpStatus.CONFLICT, 'EMAIL_TAKEN', 'An account with this email already exists. Sign in instead.');
      }
      throw error;
    }
  }

  async login(dto: LoginDto): Promise<AuthResponse> {
    this.loginFailures.assertAllowed(dto.email);
    const user = await this.prisma.user.findUnique({ where: { email: dto.email } });
    if (!user) {
      await this.passwords.verifyAgainstDummy(dto.password);
      this.loginFailures.recordFailure(dto.email);
      throw invalidCredentials();
    }
    if (!(await this.passwords.verify(user.passwordHash, dto.password))) {
      this.loginFailures.recordFailure(dto.email);
      throw invalidCredentials();
    }
    this.loginFailures.reset(dto.email);
    const pair = await this.tokens.issue(user.id);
    return { ...pair, user: toPublicUser(user) };
  }

  refresh(refreshToken: string): Promise<TokenPair> {
    return this.tokens.rotate(refreshToken);
  }

  logout(userId: string, refreshToken: string): Promise<void> {
    return this.tokens.revoke(userId, refreshToken);
  }

  /** DELETE /me: password-confirmed hard delete; every user-owned row cascades. */
  async deleteAccount(userId: string, password: string): Promise<void> {
    const user = await this.prisma.user.findUnique({ where: { id: userId } });
    if (!user) throw new ApiError(HttpStatus.UNAUTHORIZED, 'UNAUTHENTICATED', 'Sign in to continue.');
    if (!(await this.passwords.verify(user.passwordHash, password))) {
      throw new ApiError(HttpStatus.FORBIDDEN, 'INVALID_PASSWORD', 'Password is incorrect.');
    }
    // Revoke explicitly (not only via the cascade) so the intent survives any
    // future soft-delete; the guard's user lookup rejects old access tokens.
    await this.prisma.$transaction(async (tx) => {
      await this.tokens.revokeAllForUser(userId, tx);
      await tx.user.delete({ where: { id: userId } });
    });
  }
}
