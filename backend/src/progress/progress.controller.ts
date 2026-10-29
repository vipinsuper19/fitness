import { Body, Controller, Delete, Get, Param, Patch, Post, Query, Req, Res } from '@nestjs/common';
import { ApiBearerAuth, ApiHeader, ApiTags } from '@nestjs/swagger';
import type { Request, Response } from 'express';

import { RateLimit } from '../common/rate-limit';
import { CurrentUserId } from '../auth/current-user.decorator';
import { IdempotencyKeyHeader, RequiredIdempotencyKey } from '../auth/idempotency-key';
import { IdParamPipe } from '../common/id-param.pipe';
import { IdempotentWriteService } from '../idempotency/idempotent-write.service';
import { BodyMeasurementDto, BodyMeasurementUpdateDto, MeasurementListQuery, SummaryQuery } from './progress.dto';
import { MeasurementView, ProgressService, ProgressSummary } from './progress.service';
import { ExerciseProgressListQuery, ExerciseProgressQuery } from './strength.dto';
import { StrengthService } from './strength.service';

@ApiTags('progress')
@ApiBearerAuth()
@Controller()
export class ProgressController {
  constructor(
    private readonly progress: ProgressService,
    private readonly idem: IdempotentWriteService,
    private readonly strength: StrengthService,
  ) {}

  @Get('progress/exercises')
  exercises(@CurrentUserId() userId: string, @Query() q: ExerciseProgressListQuery) {
    return this.strength.list(userId, q);
  }

  @Get('progress/exercises/:exerciseId')
  exercise(@CurrentUserId() userId: string, @Param('exerciseId', IdParamPipe) id: string, @Query() q: ExerciseProgressQuery) {
    return this.strength.detail(userId, id, q);
  }

  @Get('body-measurements')
  list(@CurrentUserId() userId: string, @Query() q: MeasurementListQuery): Promise<{ items: MeasurementView[]; nextCursor: string | null }> {
    return this.progress.list(userId, q);
  }

  @RateLimit('bodyMeasurements')
  @Post('body-measurements')
  @ApiHeader({ name: 'Idempotency-Key', required: true })
  create(
    @CurrentUserId() userId: string,
    @RequiredIdempotencyKey() key: string,
    @Body() dto: BodyMeasurementDto,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<MeasurementView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 201, handler: () => this.progress.create(userId, dto) });
  }

  @Get('body-measurements/:measurementId')
  get(@CurrentUserId() userId: string, @Param('measurementId', IdParamPipe) id: string): Promise<MeasurementView> {
    return this.progress.get(userId, id);
  }

  @RateLimit('bodyMeasurements')
  @Patch('body-measurements/:measurementId')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  update(
    @CurrentUserId() userId: string,
    @Param('measurementId', IdParamPipe) id: string,
    @Body() dto: BodyMeasurementUpdateDto,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<MeasurementView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 200, handler: () => this.progress.update(userId, id, dto) });
  }

  @RateLimit('bodyMeasurements')
  @Delete('body-measurements/:measurementId')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  async remove(
    @CurrentUserId() userId: string,
    @Param('measurementId', IdParamPipe) id: string,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<void> {
    await this.idem.run({ userId, key, req, res, status: 204, handler: () => this.progress.remove(userId, id) });
  }

  @Get('progress/summary')
  summary(@CurrentUserId() userId: string, @Query() q: SummaryQuery): Promise<ProgressSummary> {
    return this.progress.summary(userId, q);
  }
}
