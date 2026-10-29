import { Controller, Get } from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';

import { CurrentUserId } from '../auth/current-user.decorator';
import { MacroSuggestionService, type MacroSuggestionView } from './macro-suggestion.service';

/** Read-only, rule-based macro split (docs/specs/macro-split-suggestion.md). Never writes. */
@ApiTags('me')
@ApiBearerAuth()
@Controller('me/macro-suggestion')
export class MacroSuggestionController {
  constructor(private readonly suggestions: MacroSuggestionService) {}

  @Get()
  get(@CurrentUserId() userId: string): Promise<MacroSuggestionView> {
    return this.suggestions.get(userId);
  }
}
