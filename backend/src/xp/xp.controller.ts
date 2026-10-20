import { Controller, Get, Query } from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';

import { CurrentUserId } from '../auth/current-user.decorator';
import { PageQuery } from '../common/page.dto';
import type { XpBalance } from './xp.rules';
import { XpEntryView, XpService } from './xp.service';

/** Read-only: XP is written only by the server (session completion and reversals). */
@ApiTags('xp')
@ApiBearerAuth()
@Controller('xp')
export class XpController {
  constructor(private readonly xp: XpService) {}

  @Get('balance')
  balance(@CurrentUserId() userId: string): Promise<XpBalance> {
    return this.xp.balance(userId);
  }

  @Get('ledger')
  ledger(@CurrentUserId() userId: string, @Query() q: PageQuery): Promise<{ items: XpEntryView[]; nextCursor: string | null }> {
    return this.xp.ledger(userId, q.cursor, q.limit);
  }
}
