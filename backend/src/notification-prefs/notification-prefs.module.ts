import { Module } from '@nestjs/common';

import { NotificationPrefsController } from './notification-prefs.controller';
import { NotificationPrefsService } from './notification-prefs.service';

@Module({ controllers: [NotificationPrefsController], providers: [NotificationPrefsService] })
export class NotificationPrefsModule {}
