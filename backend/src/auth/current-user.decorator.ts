import { createParamDecorator, ExecutionContext } from '@nestjs/common';

import type { AuthedRequest } from './jwt-auth.guard';

/** The signed-in user's id, set by JwtAuthGuard. */
export const CurrentUserId = createParamDecorator((_: unknown, ctx: ExecutionContext): string => {
  const id = ctx.switchToHttp().getRequest<AuthedRequest>().userId;
  if (!id) throw new Error('CurrentUserId used on a public route');
  return id;
});
