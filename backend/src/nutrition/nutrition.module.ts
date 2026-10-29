import { Module } from '@nestjs/common';

import { AchievementsModule } from '../achievements/achievements.module';
import { NutritionSummaryService } from './nutrition-summary.service';
import { NutritionController } from './nutrition.controller';
import { NutritionService } from './nutrition.service';

@Module({ imports: [AchievementsModule], controllers: [NutritionController], providers: [NutritionService, NutritionSummaryService] })
export class NutritionModule {}
