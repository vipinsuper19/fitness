import { Body, Controller, Get, Patch, Req, Res } from '@nestjs/common';
import { ApiBearerAuth, ApiBody, ApiHeader, ApiTags } from '@nestjs/swagger';
import type { Request, Response } from 'express';

import { RateLimit } from '../common/rate-limit';
import { CurrentUserId } from '../auth/current-user.decorator';
import { IdempotencyKeyHeader } from '../auth/idempotency-key';
import { IdempotentWriteService } from '../idempotency/idempotent-write.service';
import { LOCAL_TIME, NOTIFICATION_TYPES, type PrefsView } from './notification-prefs.rules';

const localTime = { type: 'string', pattern: LOCAL_TIME.source, description: 'Local wall-clock time HH:MM (24 h)' };
import { NotificationPrefsService } from './notification-prefs.service';

/**
 * Notification preferences (docs/specs/notification-preferences.md). Storage and API only:
 * no FCM, device tokens or sending. The body is validated by hand (dynamic `types` keys), so the
 * parameter is typed `unknown` and the global ValidationPipe passes it through untouched.
 */
@ApiTags('me')
@ApiBearerAuth()
@RateLimit('notificationPrefs')
@Controller('me/notification-preferences')
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
  // Documentation only: the body is validated by hand in NotificationPrefsService (contract NotificationPreferencesUpdate).
  @ApiBody({
    schema: {
      type: 'object',
      additionalProperties: false,
      description: 'Partial merge. Unknown properties, unknown type keys and nulls are 400 VALIDATION_FAILED.',
      properties: {
        enabled: { type: 'boolean' },
        types: { type: 'object', description: `Keys: ${NOTIFICATION_TYPES.join(', ')}`, additionalProperties: { type: 'boolean' } },
        quietHours: { type: 'object', additionalProperties: false, properties: { enabled: { type: 'boolean' }, start: localTime, end: localTime } },
      },
    },
  })
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
