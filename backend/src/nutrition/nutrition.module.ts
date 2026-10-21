import { Module } from '@nestjs/common';

import { AchievementsModule } from '../achievements/achievements.module';
import { NutritionController } from './nutrition.controller';
import { NutritionService } from './nutrition.service';

@Module({ imports: [AchievementsModule], controllers: [NutritionController], providers: [NutritionService] })
export class NutritionModule {}
