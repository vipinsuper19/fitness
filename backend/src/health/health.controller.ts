import { Controller, Get, HttpCode, Query } from '@nestjs/common';
import { ApiOkResponse, ApiQuery, ApiTags } from '@nestjs/swagger';

import { Public } from '../auth/public.decorator';
import { PrismaService } from '../prisma/prisma.service';

export interface HealthResponse {
  status: 'ok';
  uptimeSeconds: number;
  db?: 'up' | 'down';
}

/**
 * Liveness for Render's health check. It never fails on the database, so a
 * paused Supabase project does not restart-loop the service. Pass
 * `?db=true` to also probe the database.
 */
@ApiTags('health')
@Public()
@Controller('health')
export class HealthController {
  constructor(private readonly prisma: PrismaService) {}

  @Get()
  @HttpCode(200)
  @ApiQuery({ name: 'db', required: false, type: Boolean })
  @ApiOkResponse({ description: 'Service is up' })
  async health(@Query('db') db?: string): Promise<HealthResponse> {
    const body: HealthResponse = { status: 'ok', uptimeSeconds: Math.round(process.uptime()) };
    if (db === 'true') body.db = await this.probeDb();
    return body;
  }

  private async probeDb(): Promise<'up' | 'down'> {
    try {
      await this.prisma.$queryRaw`SELECT 1`;
      return 'up';
    } catch {
      return 'down';
    }
  }
}
