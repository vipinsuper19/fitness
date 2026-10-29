import { Injectable } from '@nestjs/common';

import { num } from '../common/decimal';
import { PrismaService } from '../prisma/prisma.service';
import {
  FAT_PCT, FORMULA_VERSION, PROTEIN_PER_KG_X10, hundredths, macroSplit, missingInputs, tenthsFromHundredths,
  type MacroSplit, type MissingInput, type SuggestionGoal,
} from './macro-suggestion.rules';

export interface MacroSuggestionView {
  suggestion: MacroSplit | null;
  missingInputs: MissingInput[];
  basis: {
    calorieTargetKcal: number | null;
    weightKg: number | null;
    goal: SuggestionGoal;
    goalSource: 'active_goal' | 'default';
    dietPreference: string | null;
    proteinGPerKg: number;
    fatPct: number;
  };
  formulaVersion: number;
}

const GOAL_TYPES: SuggestionGoal[] = ['lose_weight', 'gain_muscle', 'maintain', 'improve_endurance'];

@Injectable()
export class MacroSuggestionService {
  constructor(private readonly prisma: PrismaService) {}

  async get(userId: string): Promise<MacroSuggestionView> {
    const [profile, goal] = await Promise.all([
      this.prisma.userProfile.findUnique({ where: { userId } }),
      this.prisma.fitnessGoal.findFirst({
        where: { userId, status: 'active', type: { in: GOAL_TYPES } },
        orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
        select: { type: true },
      }),
    ]);
    const K = profile?.dailyCalorieTargetKcal ?? null;
    const weight = profile?.weightKg ?? null;
    const goalType = (goal?.type as SuggestionGoal | undefined) ?? 'maintain';
    const missing = missingInputs(K, weight);
    const suggestion =
      missing.length === 0
        ? macroSplit(K!, tenthsFromHundredths(hundredths(weight!.toFixed(2))), goalType)
        : null;
    return {
      suggestion,
      missingInputs: missing,
      basis: {
        calorieTargetKcal: K,
        weightKg: num(weight),
        goal: goalType,
        goalSource: goal ? 'active_goal' : 'default',
        dietPreference: profile?.dietPreference ?? null,
        proteinGPerKg: PROTEIN_PER_KG_X10[goalType] / 10,
        fatPct: FAT_PCT,
      },
      formulaVersion: FORMULA_VERSION,
    };
  }
}
