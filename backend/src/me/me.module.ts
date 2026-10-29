import { Module } from '@nestjs/common';

import { ExportController } from './export.controller';
import { ExportService } from './export.service';
import { MacroSuggestionController } from './macro-suggestion.controller';
import { MacroSuggestionService } from './macro-suggestion.service';

@Module({ controllers: [ExportController, MacroSuggestionController], providers: [ExportService, MacroSuggestionService] })
export class MeModule {}
