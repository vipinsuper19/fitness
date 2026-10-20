import { Module } from '@nestjs/common';

import { XpModule } from '../xp/xp.module';
import { SessionsController } from './sessions.controller';
import { SessionsService } from './sessions.service';

@Module({ imports: [XpModule], controllers: [SessionsController], providers: [SessionsService] })
export class SessionsModule {}
