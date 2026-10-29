import { createParamDecorator, ExecutionContext } from '@nestjs/common';

import type { AuthedRequest } from './jwt-auth.guard';

/** The signed-in user's id, set by JwtAuthGuard. */
export const CurrentUserId = createParamDecorator((_: unknown, ctx: ExecutionContext): string => {
  const id = ctx.switchToHttp().getRequest<AuthedRequest>().userId;
  if (!id) throw new Error('CurrentUserId used on a public route');
  return id;
});

/**
 * The signed-in session's id: the access token's `sid` claim (its refresh family), or undefined for a token issued
 * before `sid` shipped. Never read from the request body, query or headers.
 */
export const CurrentSessionId = createParamDecorator((_: unknown, ctx: ExecutionContext): string | undefined => {
  const req = ctx.switchToHttp().getRequest<AuthedRequest>();
  if (!req.userId) throw new Error('CurrentSessionId used on a public route');
  return req.sessionId;
});
