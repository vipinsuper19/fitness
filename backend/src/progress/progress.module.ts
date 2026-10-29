import { Module } from '@nestjs/common';

import { AchievementsModule } from '../achievements/achievements.module';
import { ProgressController } from './progress.controller';
import { ProgressService } from './progress.service';
import { StrengthService } from './strength.service';

@Module({ imports: [AchievementsModule], controllers: [ProgressController], providers: [ProgressService, StrengthService] })
export class ProgressModule {}
