import { Body, Controller, Delete, Get, Param, Patch, Post, Query, Req, Res } from '@nestjs/common';
import { ApiBearerAuth, ApiHeader, ApiTags } from '@nestjs/swagger';
import type { Request, Response } from 'express';

import { CurrentUserId } from '../auth/current-user.decorator';
import { IdempotencyKeyHeader, RequiredIdempotencyKey } from '../auth/idempotency-key';
import { IdParamPipe } from '../common/id-param.pipe';
import { IdempotentWriteService } from '../idempotency/idempotent-write.service';
import { HistoryQuery, SessionCompleteDto, SessionStartDto, SessionUpdateDto, SetLogUpsertDto } from './sessions.dto';
import { CompleteResult, SessionsService, SessionView, SetLogView } from './sessions.service';

@ApiTags('workout-sessions')
@ApiBearerAuth()
@Controller('workout-sessions')
export class SessionsController {
  constructor(
    private readonly sessions: SessionsService,
    private readonly idem: IdempotentWriteService,
  ) {}

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

  @Delete(':sessionId')
  @ApiHeader({ name: 'Idempotency-Key', required: true })
  async discard(
    @CurrentUserId() userId: string,
    @Param('sessionId', IdParamPipe) id: string,
    @RequiredIdempotencyKey() key: string,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<void> {
    await this.idem.run({ userId, key, req, res, status: 204, handler: () => this.sessions.discard(userId, id) });
  }
}
