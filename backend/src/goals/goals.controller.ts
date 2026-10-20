import { Body, Controller, Delete, Get, Param, Patch, Post, Query, Req, Res } from '@nestjs/common';
import { ApiBearerAuth, ApiHeader, ApiTags } from '@nestjs/swagger';
import type { Request, Response } from 'express';

import { CurrentUserId } from '../auth/current-user.decorator';
import { IdempotencyKeyHeader } from '../auth/idempotency-key';
import { IdParamPipe } from '../common/id-param.pipe';
import { IdempotentWriteService } from '../idempotency/idempotent-write.service';
import { GoalCreateDto, GoalListQuery, GoalUpdateDto } from './goals.dto';
import { GoalsService, GoalView } from './goals.service';

@ApiTags('goals')
@ApiBearerAuth()
@Controller('goals')
export class GoalsController {
  constructor(
    private readonly goals: GoalsService,
    private readonly idem: IdempotentWriteService,
  ) {}

  @Get()
  list(@CurrentUserId() userId: string, @Query() q: GoalListQuery): Promise<{ items: GoalView[] }> {
    return this.goals.list(userId, q);
  }

  @Post()
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  create(
    @CurrentUserId() userId: string,
    @Body() dto: GoalCreateDto,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<GoalView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 201, handler: () => this.goals.create(userId, dto) });
  }

  @Get(':goalId')
  get(@CurrentUserId() userId: string, @Param('goalId', IdParamPipe) id: string): Promise<GoalView> {
    return this.goals.get(userId, id);
  }

  @Patch(':goalId')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  update(
    @CurrentUserId() userId: string,
    @Param('goalId', IdParamPipe) id: string,
    @Body() dto: GoalUpdateDto,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<GoalView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 200, handler: () => this.goals.update(userId, id, dto) });
  }

  @Delete(':goalId')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  async remove(
    @CurrentUserId() userId: string,
    @Param('goalId', IdParamPipe) id: string,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<void> {
    await this.idem.run({ userId, key, req, res, status: 204, handler: () => this.goals.remove(userId, id) });
  }
}
