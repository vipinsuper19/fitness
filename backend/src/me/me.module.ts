import { Module } from '@nestjs/common';

import { ExportController } from './export.controller';
import { ExportService } from './export.service';
import { MacroSuggestionController } from './macro-suggestion.controller';
import { MacroSuggestionService } from './macro-suggestion.service';
import { TargetHistoryController } from './target-history.controller';

@Module({ controllers: [ExportController, MacroSuggestionController, TargetHistoryController], providers: [ExportService, MacroSuggestionService] })
export class MeModule {}
