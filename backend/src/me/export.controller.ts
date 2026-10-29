import { Controller, Get, Header, Injectable, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';
import { SkipThrottle, ThrottlerGuard } from '@nestjs/throttler';

import { CurrentUserId } from '../auth/current-user.decorator';
import type { AuthedRequest } from '../auth/jwt-auth.guard';
import { ExportService } from './export.service';

/** Counts exports per signed-in user rather than per IP. */
@Injectable()
export class UserThrottlerGuard extends ThrottlerGuard {
  protected override async getTracker(req: Record<string, unknown>): Promise<string> {
    return (req as unknown as AuthedRequest).userId ?? String(req.ip);
  }
}

@ApiTags('me')
@ApiBearerAuth()
@Controller('me')
@UseGuards(UserThrottlerGuard)
@SkipThrottle({ auth: true, refresh: true, export: false, targetHistory: true })
export class ExportController {
  constructor(private readonly exporter: ExportService) {}

  @Get('export')
  @Header('Cache-Control', 'no-store')
  export(@CurrentUserId() userId: string) {
    return this.exporter.export(userId);
  }
}
