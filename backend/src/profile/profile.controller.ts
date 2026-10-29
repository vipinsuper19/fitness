import { Body, Controller, Get, Put, Req, Res } from '@nestjs/common';
import { ApiBearerAuth, ApiHeader, ApiTags } from '@nestjs/swagger';
import type { Request, Response } from 'express';

import { RateLimit } from '../common/rate-limit';
import { CurrentUserId } from '../auth/current-user.decorator';
import { IdempotencyKeyHeader } from '../auth/idempotency-key';
import { IdempotentWriteService } from '../idempotency/idempotent-write.service';
import { ProfileUpdateDto } from './profile.dto';
import { ProfileService, ProfileView } from './profile.service';

@ApiTags('me')
@ApiBearerAuth()
@Controller('me/profile')
export class ProfileController {
  constructor(
    private readonly profiles: ProfileService,
    private readonly idem: IdempotentWriteService,
  ) {}

  @Get()
  get(@CurrentUserId() userId: string): Promise<ProfileView> {
    return this.profiles.get(userId);
  }

  @RateLimit('profile')
  @Put()
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  update(
    @CurrentUserId() userId: string,
    @Body() dto: ProfileUpdateDto,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<ProfileView | undefined> {
    return this.idem.run({ userId, key, req, res, status: 200, handler: () => this.profiles.update(userId, dto) });
  }
}
