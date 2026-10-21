import { Module } from '@nestjs/common';

import { AchievementsModule } from '../achievements/achievements.module';
import { XpModule } from '../xp/xp.module';
import { SessionsController } from './sessions.controller';
import { SessionsService } from './sessions.service';

@Module({ imports: [XpModule, AchievementsModule], controllers: [SessionsController], providers: [SessionsService] })
export class SessionsModule {}
