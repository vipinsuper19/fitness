import { Body, Controller, Get, Param, Post, Query, Req, Res } from '@nestjs/common';
import { ApiBearerAuth, ApiHeader, ApiTags } from '@nestjs/swagger';
import type { Request, Response } from 'express';

import { CurrentUserId } from '../auth/current-user.decorator';
import { IdempotencyKeyHeader, RequiredIdempotencyKey } from '../auth/idempotency-key';
import { IdParamPipe } from '../common/id-param.pipe';
import { IdempotentWriteService } from '../idempotency/idempotent-write.service';
import { AchievementsApiService, BadgeView, ChallengeView } from './achievements-api.service';
import { ChallengeJoinDto, ChallengeListQuery } from './achievements.dto';

@ApiBearerAuth()
@Controller()
export class AchievementsController {
  constructor(
    private readonly api: AchievementsApiService,
    private readonly idem: IdempotentWriteService,
  ) {}

  @ApiTags('badges')
  @Get('badges')
  badges(@CurrentUserId() userId: string): Promise<{ items: BadgeView[]; earnedCount: number }> {
    return this.api.listBadges(userId);
  }

  @ApiTags('challenges')
  @Get('challenges/templates')
  templates() {
    return this.api.templates();
  }

  @ApiTags('challenges')
  @Get('challenges')
  list(@CurrentUserId() userId: string, @Query() q: ChallengeListQuery): Promise<{ items: ChallengeView[]; nextCursor: string | null }> {
    return this.api.listChallenges(userId, q);
  }

  @ApiTags('challenges')
  @Post('challenges')
  @ApiHeader({ name: 'Idempotency-Key', required: true })
  join(
    @CurrentUserId() userId: string,
    @RequiredIdempotencyKey() key: string,
    @Body() dto: ChallengeJoinDto,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<ChallengeView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 201, handler: () => this.api.join(userId, dto) });
  }

  @ApiTags('challenges')
  @Get('challenges/:challengeId')
  get(@CurrentUserId() userId: string, @Param('challengeId', IdParamPipe) id: string): Promise<ChallengeView> {
    return this.api.getChallenge(userId, id);
  }

  @ApiTags('challenges')
  @Post('challenges/:challengeId/leave')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  leave(
    @CurrentUserId() userId: string,
    @Param('challengeId', IdParamPipe) id: string,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<ChallengeView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 200, handler: () => this.api.leave(userId, id) });
  }
}
