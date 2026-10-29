import { Controller, Get } from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';

import { RateLimit } from '../common/rate-limit';
import { CurrentUserId } from '../auth/current-user.decorator';
import { PrismaService } from '../prisma/prisma.service';
import { listHistory, type TargetHistoryEntry } from '../targets/target-history';

/** Read-only dated target history (docs/specs/target-history.md), newest first. No edits in v1. */
@ApiTags('me')
@ApiBearerAuth()
@RateLimit('targetHistory')
@Controller('me/target-history')
export class TargetHistoryController {
  constructor(private readonly prisma: PrismaService) {}

  @Get()
  async get(@CurrentUserId() userId: string): Promise<{ items: TargetHistoryEntry[] }> {
    return { items: await listHistory(this.prisma, userId) };
  }
}
