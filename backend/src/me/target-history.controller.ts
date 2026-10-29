import { Controller, Get, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';
import { SkipThrottle } from '@nestjs/throttler';

import { CurrentUserId } from '../auth/current-user.decorator';
import { PrismaService } from '../prisma/prisma.service';
import { listHistory, type TargetHistoryEntry } from '../targets/target-history';
import { UserThrottlerGuard } from './export.controller';

/** Read-only dated target history (docs/specs/target-history.md), newest first. No edits in v1. */
@ApiTags('me')
@ApiBearerAuth()
@Controller('me/target-history')
@UseGuards(UserThrottlerGuard)
@SkipThrottle({ auth: true, refresh: true, export: true, targetHistory: false })
export class TargetHistoryController {
  constructor(private readonly prisma: PrismaService) {}

  @Get()
  async get(@CurrentUserId() userId: string): Promise<{ items: TargetHistoryEntry[] }> {
    return { items: await listHistory(this.prisma, userId) };
  }
}
