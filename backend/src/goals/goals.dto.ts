import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { IsIn, IsNumber, IsOptional, Max, Min } from 'class-validator';

import { IsCalendarDate, IsDefinedWhenPresent } from '../common/validators';

export const GOAL_TYPES = ['lose_weight', 'gain_muscle', 'maintain', 'improve_endurance', 'workouts_per_week'] as const;
export const GOAL_STATUSES = ['active', 'achieved', 'abandoned'] as const;
export type GoalTypeValue = (typeof GOAL_TYPES)[number];

const value = { allowNaN: false, allowInfinity: false, maxDecimalPlaces: 2 } as const;

export class GoalCreateDto {
  @ApiProperty({ enum: GOAL_TYPES })
  @IsIn(GOAL_TYPES)
  type!: GoalTypeValue;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0, description: 'kg for weight goals, count for workouts_per_week' })
  @IsOptional() @IsNumber(value) @Min(0) @Max(999_999)
  targetValue?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0 })
  @IsOptional() @IsNumber(value) @Min(0) @Max(999_999)
  startValue?: number | null;

  @ApiPropertyOptional({ type: String, format: 'date', nullable: true })
  @IsOptional() @IsCalendarDate()
  targetDate?: string | null;
}

export class GoalUpdateDto {
  @ApiPropertyOptional({ enum: GOAL_STATUSES })
  @IsDefinedWhenPresent() @IsIn(GOAL_STATUSES)
  status?: (typeof GOAL_STATUSES)[number];

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0 })
  @IsOptional() @IsNumber(value) @Min(0) @Max(999_999)
  targetValue?: number | null;

  @ApiPropertyOptional({ type: String, format: 'date', nullable: true })
  @IsOptional() @IsCalendarDate()
  targetDate?: string | null;
}

export class GoalListQuery {
  @ApiPropertyOptional({ enum: GOAL_STATUSES })
  @IsOptional() @IsIn(GOAL_STATUSES)
  status?: (typeof GOAL_STATUSES)[number];
}
