import { Body, Controller, Delete, Get, Param, Patch, Post, Put, Query, Req, Res } from '@nestjs/common';
import { ApiBearerAuth, ApiHeader, ApiTags } from '@nestjs/swagger';
import type { Request, Response } from 'express';

import { RateLimit } from '../common/rate-limit';
import { CurrentUserId } from '../auth/current-user.decorator';
import { IdempotencyKeyHeader, RequiredIdempotencyKey } from '../auth/idempotency-key';
import { IdParamPipe } from '../common/id-param.pipe';
import { IdempotentWriteService } from '../idempotency/idempotent-write.service';
import { HistoryQuery, SessionCompleteDto, SessionCompletionUpdateDto, SessionStartDto, SessionUpdateDto, SetLogUpsertDto } from './sessions.dto';
import { CompleteResult, CorrectionResult, SessionsService, SessionView, SetLogView } from './sessions.service';

@ApiTags('workout-sessions')
@ApiBearerAuth()
@Controller('workout-sessions')
export class SessionsController {
  constructor(
    private readonly sessions: SessionsService,
    private readonly idem: IdempotentWriteService,
  ) {}

  @RateLimit('sessions')
  @Post()
  @ApiHeader({ name: 'Idempotency-Key', required: true })
  start(
    @CurrentUserId() userId: string,
    @RequiredIdempotencyKey() key: string,
    @Body() dto: SessionStartDto,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<SessionView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 201, handler: () => this.sessions.start(userId, dto) });
  }

  @Get('history')
  history(@CurrentUserId() userId: string, @Query() q: HistoryQuery): Promise<{ items: SessionView[]; nextCursor: string | null }> {
    return this.sessions.history(userId, q);
  }

  @Get(':sessionId')
  get(@CurrentUserId() userId: string, @Param('sessionId', IdParamPipe) id: string): Promise<SessionView> {
    return this.sessions.get(userId, id);
  }

  @RateLimit('sessions')
  @Patch(':sessionId')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  update(
    @CurrentUserId() userId: string,
    @Param('sessionId', IdParamPipe) id: string,
    @Body() dto: SessionUpdateDto,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<SessionView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 200, handler: () => this.sessions.update(userId, id, dto) });
  }

  @RateLimit('sessions')
  @Post(':sessionId/sets')
  @ApiHeader({ name: 'Idempotency-Key', required: true })
  upsertSet(
    @CurrentUserId() userId: string,
    @Param('sessionId', IdParamPipe) id: string,
    @RequiredIdempotencyKey() key: string,
    @Body() dto: SetLogUpsertDto,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<SetLogView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 200, handler: () => this.sessions.upsertSet(userId, id, dto) });
  }

  @RateLimit('sessions')
  @Post(':sessionId/complete')
  @ApiHeader({ name: 'Idempotency-Key', required: true })
  complete(
    @CurrentUserId() userId: string,
    @Param('sessionId', IdParamPipe) id: string,
    @RequiredIdempotencyKey() key: string,
    @Body() dto: SessionCompleteDto,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<CompleteResult | undefined> {
    return this.idem.run({ userId, key, req, res, status: 200, handler: () => this.sessions.complete(userId, id, dto) });
  }

  @RateLimit('sessions')
  @Delete(':sessionId')
  @ApiHeader({ name: 'Idempotency-Key', required: true })
  async discard(
    @CurrentUserId() userId: string,
    @Param('sessionId', IdParamPipe) id: string,
    @RequiredIdempotencyKey() key: string,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<void> {
    await this.idem.run({ userId, key, req, res, status: 204, handler: () => this.sessions.discard(userId, id, key) });
  }

  // Corrections of a completed session (PRD 5.3): always 200 SessionCorrectionResult.
  @RateLimit('sessions')
  @Put(':sessionId/corrections/sets/:clientSetId')
  @ApiHeader({ name: 'Idempotency-Key', required: true })
  correctSet(
    @CurrentUserId() userId: string,
    @Param('sessionId', IdParamPipe) id: string,
    @Param('clientSetId', IdParamPipe) clientSetId: string,
    @RequiredIdempotencyKey() key: string,
    @Body() dto: SetLogUpsertDto,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<CorrectionResult | undefined> {
    return this.idem.run({ userId, key, req, res, status: 200, handler: () => this.sessions.correctSet(userId, id, clientSetId, dto, key) });
  }

  @RateLimit('sessions')
  @Delete(':sessionId/corrections/sets/:clientSetId')
  @ApiHeader({ name: 'Idempotency-Key', required: true })
  deleteCorrectedSet(
    @CurrentUserId() userId: string,
    @Param('sessionId', IdParamPipe) id: string,
    @Param('clientSetId', IdParamPipe) clientSetId: string,
    @RequiredIdempotencyKey() key: string,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<CorrectionResult | undefined> {
    return this.idem.run({ userId, key, req, res, status: 200, handler: () => this.sessions.deleteCorrectedSet(userId, id, clientSetId, key) });
  }

  @RateLimit('sessions')
  @Patch(':sessionId/completion')
  @ApiHeader({ name: 'Idempotency-Key', required: true })
  correctCompletion(
    @CurrentUserId() userId: string,
    @Param('sessionId', IdParamPipe) id: string,
    @RequiredIdempotencyKey() key: string,
    @Body() dto: SessionCompletionUpdateDto,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<CorrectionResult | undefined> {
    return this.idem.run({ userId, key, req, res, status: 200, handler: () => this.sessions.correctCompletion(userId, id, dto, key) });
  }
}
