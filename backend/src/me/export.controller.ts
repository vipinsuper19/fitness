import { Controller, Get, Header } from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';

import { RateLimit } from '../common/rate-limit';
import { CurrentUserId } from '../auth/current-user.decorator';
import { ExportService } from './export.service';

@ApiTags('me')
@ApiBearerAuth()
@RateLimit('export')
@Controller('me')
export class ExportController {
  constructor(private readonly exporter: ExportService) {}

  @Get('export')
  @Header('Cache-Control', 'no-store')
  export(@CurrentUserId() userId: string) {
    return this.exporter.export(userId);
  }
}
