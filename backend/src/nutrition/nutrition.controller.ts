import { Body, Controller, Delete, Get, Param, Patch, Post, Query, Req, Res } from '@nestjs/common';
import { ApiBearerAuth, ApiHeader, ApiTags } from '@nestjs/swagger';
import type { Request, Response } from 'express';

import { CurrentUserId } from '../auth/current-user.decorator';
import { IdempotencyKeyHeader, RequiredIdempotencyKey } from '../auth/idempotency-key';
import { IdParamPipe } from '../common/id-param.pipe';
import { IdempotentWriteService } from '../idempotency/idempotent-write.service';
import { DateQuery, FoodCreateDto, FoodLogCreateDto, FoodLogUpdateDto, FoodSearchQuery, WaterLogDto } from './nutrition.dto';
import { DailyNutrition, FoodLogView, FoodView, NutritionService, WaterLogView } from './nutrition.service';

interface Ctx {
  req: Request;
  res: Response;
}

@ApiTags('nutrition')
@ApiBearerAuth()
@Controller()
export class NutritionController {
  constructor(
    private readonly nutrition: NutritionService,
    private readonly idem: IdempotentWriteService,
  ) {}

  private run<T>(userId: string, key: string | undefined, { req, res }: Ctx, status: number, handler: () => Promise<T>) {
    return this.idem.run({ userId, key, req, res, status, handler });
  }

  @Get('foods/search')
  search(@CurrentUserId() userId: string, @Query() q: FoodSearchQuery): Promise<{ items: FoodView[]; nextCursor: string | null }> {
    return this.nutrition.searchFoods(userId, q);
  }

  @Post('foods')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  createFood(
    @CurrentUserId() userId: string,
    @Body() dto: FoodCreateDto,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<FoodView | undefined> {
    return this.run(userId, key, { req, res }, 201, () => this.nutrition.createFood(userId, dto));
  }

  @Get('food-logs')
  listFoodLogs(@CurrentUserId() userId: string, @Query() q: DateQuery): Promise<{ items: FoodLogView[] }> {
    return this.nutrition.listFoodLogs(userId, q.date);
  }

  @Post('food-logs')
  @ApiHeader({ name: 'Idempotency-Key', required: true })
  createFoodLog(
    @CurrentUserId() userId: string,
    @RequiredIdempotencyKey() key: string,
    @Body() dto: FoodLogCreateDto,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<FoodLogView | undefined> {
    return this.run(userId, key, { req, res }, 201, () => this.nutrition.createFoodLog(userId, dto));
  }

  @Patch('food-logs/:foodLogId')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  updateFoodLog(
    @CurrentUserId() userId: string,
    @Param('foodLogId', IdParamPipe) id: string,
    @Body() dto: FoodLogUpdateDto,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<FoodLogView | undefined> {
    return this.run(userId, key, { req, res }, 200, () => this.nutrition.updateFoodLog(userId, id, dto));
  }

  @Delete('food-logs/:foodLogId')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  async deleteFoodLog(
    @CurrentUserId() userId: string,
    @Param('foodLogId', IdParamPipe) id: string,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<void> {
    await this.run(userId, key, { req, res }, 204, () => this.nutrition.deleteFoodLog(userId, id));
  }

  @Get('water-logs')
  listWater(@CurrentUserId() userId: string, @Query() q: DateQuery): Promise<{ items: WaterLogView[] }> {
    return this.nutrition.listWater(userId, q.date);
  }

  @Post('water-logs')
  @ApiHeader({ name: 'Idempotency-Key', required: true })
  createWater(
    @CurrentUserId() userId: string,
    @RequiredIdempotencyKey() key: string,
    @Body() dto: WaterLogDto,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<WaterLogView | undefined> {
    return this.run(userId, key, { req, res }, 201, () => this.nutrition.createWater(userId, dto));
  }

  @Patch('water-logs/:waterLogId')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  updateWater(
    @CurrentUserId() userId: string,
    @Param('waterLogId', IdParamPipe) id: string,
    @Body() dto: WaterLogDto,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<WaterLogView | undefined> {
    return this.run(userId, key, { req, res }, 200, () => this.nutrition.updateWater(userId, id, dto));
  }

  @Delete('water-logs/:waterLogId')
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  async deleteWater(
    @CurrentUserId() userId: string,
    @Param('waterLogId', IdParamPipe) id: string,
    @IdempotencyKeyHeader() key: string | undefined,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<void> {
    await this.run(userId, key, { req, res }, 204, () => this.nutrition.deleteWater(userId, id));
  }

  @Get('nutrition/daily')
  daily(@CurrentUserId() userId: string, @Query() q: DateQuery): Promise<DailyNutrition> {
    return this.nutrition.daily(userId, q.date);
  }
}
