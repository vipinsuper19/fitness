import { Module } from '@nestjs/common';

import { PlansController } from './plans.controller';
import { PlansService } from './plans.service';
import { ProgressionService } from './progression.service';

@Module({ controllers: [PlansController], providers: [PlansService, ProgressionService] })
export class PlansModule {}
