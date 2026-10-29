import { Body, Controller, Get, Patch, Req, Res, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiHeader, ApiTags } from '@nestjs/swagger';
import { SkipThrottle } from '@nestjs/throttler';
import type { Request, Response } from 'express';

import { CurrentUserId } from '../auth/current-user.decorator';
import { IdempotencyKeyHeader } from '../auth/idempotency-key';
import { IdempotentWriteService } from '../idempotency/idempotent-write.service';
import { UserThrottlerGuard } from '../me/export.controller';
import type { PrefsView } from './notification-prefs.rules';
import { NotificationPrefsService } from './notification-prefs.service';

/**
 * Notification preferences (docs/specs/notification-preferences.md). Storage and API only:
 * no FCM, device tokens or sending. The body is validated by hand (dynamic `types` keys), so the
 * parameter is typed `unknown` and the global ValidationPipe passes it through untouched.
 */
@ApiTags('me')
@ApiBearerAuth()
@Controller('me/notification-preferences')
@UseGuards(UserThrottlerGuard)
@SkipThrottle({ auth: true, refresh: true, export: true, targetHistory: true, notificationPrefs: false })
export class NotificationPrefsController {
  constructor(
    private readonly prefs: NotificationPrefsService,
    private readonly idem: IdempotentWriteService,
  ) {}

  @Get()
  get(@CurrentUserId() userId: string): Promise<PrefsView> {
    return this.prefs.get(userId);
  }

  @Patch()
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  patch(
    @CurrentUserId() userId: string,
    @Body() body: unknown,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<PrefsView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 200, handler: () => this.prefs.patch(userId, body) });
  }
}
