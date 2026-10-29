import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Patch, Post, Put, Query, Req, Res, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiHeader, ApiTags } from '@nestjs/swagger';
import { SkipThrottle, ThrottlerGuard } from '@nestjs/throttler';
import type { Request, Response } from 'express';

import { CurrentUserId } from '../auth/current-user.decorator';
import { IdempotencyKeyHeader } from '../auth/idempotency-key';
import { IdParamPipe } from '../common/id-param.pipe';
import { IdempotentWriteService } from '../idempotency/idempotent-write.service';
import type { WorkoutPlanDraft } from './generator';
import { PlanActivateDto, PlanGenerateDto, PlanListQuery, PlanRenameDto, PlanVersionQuery, WorkoutPlanDraftDto, WorkoutPlanUpdateDto } from './plans.dto';
import { PlansService, PlanSummary, PlanView } from './plans.service';
import { ProgressionService, ProgressionSuggestions } from './progression.service';

@ApiTags('workout-plans')
@ApiBearerAuth()
@Controller('workout-plans')
export class PlansController {
  constructor(
    private readonly plans: PlansService,
    private readonly idem: IdempotentWriteService,
    private readonly progression: ProgressionService,
  ) {}

  /** Read-only (never persists), so no Idempotency-Key; throttled per IP like auth. */
  @Post('generate')
  @HttpCode(HttpStatus.OK)
  @UseGuards(ThrottlerGuard)
  @SkipThrottle({ refresh: true, export: true, targetHistory: true, notificationPrefs: true })
  generate(@Body() dto: PlanGenerateDto): Promise<WorkoutPlanDraft> {
    return this.plans.generate(dto);
  }

  @Post()
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  create(
    @CurrentUserId() userId: string,
    @Body() dto: WorkoutPlanDraftDto,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<PlanView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 201, handler: () => this.plans.create(userId, dto) });
  }

  @Get()
  list(@CurrentUserId() userId: string, @Query() q: PlanListQuery): Promise<{ items: PlanSummary[]; nextCursor: string | null }> {
    return this.plans.list(userId, q);
  }

  @Get('active')
  async active(@CurrentUserId() userId: string, @Res({ passthrough: true }) res: Response): Promise<PlanView | undefined> {
    const plan = await this.plans.active(userId);
    if (!plan) {
      res.status(HttpStatus.NO_CONTENT);
      return undefined;
    }
    return plan;
  }

  @Get(':planId')
  get(@CurrentUserId() userId: string, @Param('planId', IdParamPipe) id: string, @Query() q: PlanVersionQuery): Promise<PlanView> {
    return this.plans.get(userId, id, q.version);
  }

  /** Read-only, rule-based progression suggestions (docs/specs/adaptive-progression.md). */
  @Get(':planId/progression-suggestions')
  progressionSuggestions(@CurrentUserId() userId: string, @Param('planId', IdParamPipe) id: string): Promise<ProgressionSuggestions> {
    return this.progression.suggestions(userId, id);
  }

  @Patch(':planId')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  update(
    @CurrentUserId() userId: string,
    @Param('planId', IdParamPipe) id: string,
    @Body() dto: WorkoutPlanUpdateDto,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<PlanView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 200, handler: () => this.plans.update(userId, id, dto) });
  }

  @Put(':planId/name')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  rename(
    @CurrentUserId() userId: string,
    @Param('planId', IdParamPipe) id: string,
    @Body() dto: PlanRenameDto,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<PlanView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 200, handler: () => this.plans.rename(userId, id, dto.name) });
  }

  @Post(':planId/unarchive')
  @HttpCode(HttpStatus.OK)
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  unarchive(
    @CurrentUserId() userId: string,
    @Param('planId', IdParamPipe) id: string,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<PlanView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 200, handler: () => this.plans.unarchive(userId, id) });
  }

  @Post(':planId/activate')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  activate(
    @CurrentUserId() userId: string,
    @Param('planId', IdParamPipe) id: string,
    @Body() dto: PlanActivateDto,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<PlanView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 200, handler: () => this.plans.activate(userId, id, dto?.resume === true) });
  }

  @Post(':planId/deactivate')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  async deactivate(
    @CurrentUserId() userId: string,
    @Param('planId', IdParamPipe) id: string,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<void> {
    await this.idem.run({ userId, key, req, res, status: 204, handler: () => this.plans.deactivate(userId, id) });
  }

  @Delete(':planId')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  async archive(
    @CurrentUserId() userId: string,
    @Param('planId', IdParamPipe) id: string,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<void> {
    await this.idem.run({ userId, key, req, res, status: 204, handler: () => this.plans.archive(userId, id) });
  }
}
