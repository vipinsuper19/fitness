import { createHash, randomBytes, randomUUID } from 'node:crypto';

import { HttpStatus, Inject, Injectable } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';

import { ApiError } from '../common/api-error';
import { ENV } from '../config/config.module';
import type { Env } from '../config/env';
import type { Prisma } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';

type Db = PrismaService | Prisma.TransactionClient;

export const JWT_ISSUER = 'fitness-api';
export const JWT_AUDIENCE = 'fitness-app';

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
}

export interface AccessClaims {
  sub: string;
  typ: 'access';
}

export function hashRefreshToken(token: string): string {
  return createHash('sha256').update(token).digest('hex');
}

/**
 * Access tokens: HS256 JWTs (15 min by default) signed with
 * JWT_ACCESS_SECRET. Refresh tokens: opaque 256-bit random strings; only
 * their SHA-256 is stored. Each refresh rotates the token inside its family;
 * presenting a revoked token revokes the whole family (reuse detection).
 */
@Injectable()
export class TokenService {
  constructor(
    private readonly jwt: JwtService,
    private readonly prisma: PrismaService,
    @Inject(ENV) private readonly env: Env,
  ) {}

  async issue(userId: string, familyId: string = randomUUID(), db: Db = this.prisma): Promise<TokenPair> {
    const { refreshToken } = await this.createRefresh(db, userId, familyId);
    return { accessToken: await this.signAccess(userId), refreshToken, tokenType: 'Bearer', expiresIn: this.env.JWT_ACCESS_TTL_SECONDS };
  }

  private async createRefresh(db: Db, userId: string, familyId: string): Promise<{ id: string; refreshToken: string }> {
    const refreshToken = randomBytes(32).toString('base64url');
    const row = await db.refreshToken.create({
      data: {
        userId,
        familyId,
        tokenHash: hashRefreshToken(refreshToken),
        expiresAt: new Date(Date.now() + this.env.JWT_REFRESH_TTL_DAYS * 86_400_000),
      },
      select: { id: true },
    });
    return { id: row.id, refreshToken };
  }

  signAccess(userId: string): Promise<string> {
    const claims: AccessClaims = { sub: userId, typ: 'access' };
    return this.jwt.signAsync(claims, {
      secret: this.env.JWT_ACCESS_SECRET,
      algorithm: 'HS256',
      expiresIn: this.env.JWT_ACCESS_TTL_SECONDS,
      issuer: JWT_ISSUER,
      audience: JWT_AUDIENCE,
      jwtid: randomUUID(),
    });
  }

  /** Returns the user id, or throws UNAUTHENTICATED / TOKEN_EXPIRED. */
  async verifyAccess(token: string): Promise<string> {
    try {
      const claims = await this.jwt.verifyAsync<AccessClaims>(token, {
        secret: this.env.JWT_ACCESS_SECRET,
        algorithms: ['HS256'],
        issuer: JWT_ISSUER,
        audience: JWT_AUDIENCE,
      });
      if (claims.typ !== 'access' || typeof claims.sub !== 'string') throw new Error('wrong token type');
      return claims.sub;
    } catch (error) {
      if (error instanceof Error && error.name === 'TokenExpiredError') {
        throw new ApiError(HttpStatus.UNAUTHORIZED, 'TOKEN_EXPIRED', 'Your session expired. Refresh and try again.');
      }
      throw new ApiError(HttpStatus.UNAUTHORIZED, 'UNAUTHENTICATED', 'Sign in to continue.');
    }
  }

  /** Rotates a refresh token. Reuse of a revoked token revokes its family. */
  async rotate(presented: string): Promise<TokenPair> {
    const now = new Date();
    const row = await this.prisma.refreshToken.findUnique({ where: { tokenHash: hashRefreshToken(presented) } });
    if (!row) throw new ApiError(HttpStatus.UNAUTHORIZED, 'REFRESH_TOKEN_INVALID', 'Sign in again.');
    if (row.revokedAt) {
      await this.revokeFamily(row.familyId, now);
      throw new ApiError(HttpStatus.UNAUTHORIZED, 'REFRESH_TOKEN_REUSED', 'Sign in again.');
    }
    if (row.expiresAt <= now) {
      throw new ApiError(HttpStatus.UNAUTHORIZED, 'REFRESH_TOKEN_EXPIRED', 'Sign in again.');
    }
    // Claim, issue and link in one transaction: either the old token is
    // revoked AND its successor exists and is linked, or nothing changed.
    // A concurrent rotation of the same token loses the claim and is treated
    // as reuse. No grace window: clients serialise refreshes, and a window
    // would let a stolen token be replayed undetected inside it.
    const outcome = await this.prisma.$transaction(async (tx) => {
      const claimed = await tx.refreshToken.updateMany({
        where: { id: row.id, revokedAt: null },
        data: { revokedAt: now },
      });
      if (claimed.count !== 1) return null;
      const next = await this.createRefresh(tx, row.userId, row.familyId);
      await tx.refreshToken.update({ where: { id: row.id }, data: { replacedById: next.id } });
      return next;
    });
    if (!outcome) {
      // Outside the transaction so the family revoke is not rolled back.
      await this.revokeFamily(row.familyId, now);
      throw new ApiError(HttpStatus.UNAUTHORIZED, 'REFRESH_TOKEN_REUSED', 'Sign in again.');
    }
    return {
      accessToken: await this.signAccess(row.userId),
      refreshToken: outcome.refreshToken,
      tokenType: 'Bearer',
      expiresIn: this.env.JWT_ACCESS_TTL_SECONDS,
    };
  }

  /** Revokes every live refresh token of a user (account deletion). */
  async revokeAllForUser(userId: string, db: Db = this.prisma): Promise<void> {
    await db.refreshToken.updateMany({ where: { userId, revokedAt: null }, data: { revokedAt: new Date() } });
  }

  /** Revokes one token (logout). Only the owner's token is touched. */
  async revoke(userId: string, presented: string): Promise<void> {
    await this.prisma.refreshToken.updateMany({
      where: { userId, tokenHash: hashRefreshToken(presented), revokedAt: null },
      data: { revokedAt: new Date() },
    });
  }

  async revokeFamily(familyId: string, at = new Date()): Promise<void> {
    await this.prisma.refreshToken.updateMany({ where: { familyId, revokedAt: null }, data: { revokedAt: at } });
  }
}
