import { Module } from '@nestjs/common';

import { AchievementsApiService } from './achievements-api.service';
import { AchievementsController } from './achievements.controller';
import { AchievementsService } from './achievements.service';

@Module({ controllers: [AchievementsController], providers: [AchievementsService, AchievementsApiService], exports: [AchievementsService] })
export class AchievementsModule {}
