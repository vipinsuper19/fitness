import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { Type } from 'class-transformer';
import {
  ArrayMaxSize, ArrayMinSize, IsArray, IsIn, IsInt, IsNumber, IsOptional, IsString, IsUUID,
  Max, MaxLength, Min, MinLength, ValidateNested,
} from 'class-validator';

import { GOAL_TYPES } from '../goals/goals.dto';
import { EQUIPMENT } from '../exercises/exercises.dto';
import { EXPERIENCE } from '../profile/profile.dto';
import type { Equip, Goal, Level } from './generator';

/** PlanGenerateRequest (contract). */
export class PlanGenerateDto {
  @ApiProperty({ enum: GOAL_TYPES }) @IsIn(GOAL_TYPES)
  goal!: Goal;

  @ApiProperty({ enum: EXPERIENCE }) @IsIn(EXPERIENCE)
  experienceLevel!: Level;

  @ApiProperty({ minimum: 1, maximum: 7 }) @IsInt() @Min(1) @Max(7)
  daysPerWeek!: number;

  @ApiProperty({ minimum: 15, maximum: 180 }) @IsInt() @Min(15) @Max(180)
  sessionMinutes!: number;

  @ApiPropertyOptional({ enum: EQUIPMENT, isArray: true, description: 'Empty or omitted means bodyweight only' })
  @IsOptional() @IsArray() @ArrayMaxSize(EQUIPMENT.length) @IsIn(EQUIPMENT, { each: true })
  equipment?: Equip[];

  @ApiPropertyOptional({ minimum: 1, maximum: 16, default: 4 }) @IsOptional() @IsInt() @Min(1) @Max(16)
  weeks?: number;
}

export class PlannedExerciseDto {
  @ApiProperty({ format: 'uuid' }) @IsUUID()
  exerciseId!: string;

  @ApiProperty({ minimum: 0 }) @IsInt() @Min(0) @Max(1000)
  order!: number;

  @ApiProperty({ minimum: 1, maximum: 20 }) @IsInt() @Min(1) @Max(20)
  sets!: number;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 1, maximum: 100 }) @IsOptional() @IsInt() @Min(1) @Max(100)
  repsMin?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 1, maximum: 100 }) @IsOptional() @IsInt() @Min(1) @Max(100)
  repsMax?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 1, maximum: 7200 }) @IsOptional() @IsInt() @Min(1) @Max(7200)
  durationSeconds?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0, maximum: 1000, description: 'kg' })
  @IsOptional() @IsNumber({ allowNaN: false, allowInfinity: false, maxDecimalPlaces: 2 }) @Min(0) @Max(1000)
  targetWeightKg?: number | null;

  @ApiPropertyOptional({ minimum: 0, maximum: 600, default: 90 }) @IsOptional() @IsInt() @Min(0) @Max(600)
  restSeconds?: number;

  @ApiPropertyOptional({ type: String, nullable: true, maxLength: 500 }) @IsOptional() @IsString() @MaxLength(500)
  notes?: string | null;
}

export class PlannedWorkoutDto {
  @ApiProperty({ minimum: 1, maximum: 7, description: 'ISO weekday, 1 = Monday' }) @IsInt() @Min(1) @Max(7)
  dayOfWeek!: number;

  @ApiProperty({ minLength: 1, maxLength: 80 }) @IsString() @MinLength(1) @MaxLength(80)
  name!: string;

  @ApiProperty({ type: [PlannedExerciseDto] })
  @IsArray() @ArrayMinSize(1) @ArrayMaxSize(30) @ValidateNested({ each: true }) @Type(() => PlannedExerciseDto)
  exercises!: PlannedExerciseDto[];
}

/** WorkoutPlanDraft (contracts/schemas/workout-plan-draft.schema.json). */
export class WorkoutPlanDraftDto {
  @ApiProperty({ minLength: 1, maxLength: 80 }) @IsString() @MinLength(1) @MaxLength(80)
  name!: string;

  @ApiProperty({ minimum: 1, maximum: 16 }) @IsInt() @Min(1) @Max(16)
  weeks!: number;

  @ApiProperty({ enum: ['rule_based', 'custom'] }) @IsIn(['rule_based', 'custom'])
  generator!: 'rule_based' | 'custom';

  @ApiPropertyOptional({ type: String, nullable: true, maxLength: 1000 }) @IsOptional() @IsString() @MaxLength(1000)
  rationale?: string | null;

  @ApiProperty({ type: [PlannedWorkoutDto] })
  @IsArray() @ArrayMinSize(1) @ArrayMaxSize(7) @ValidateNested({ each: true }) @Type(() => PlannedWorkoutDto)
  workouts!: PlannedWorkoutDto[];
}

/** WorkoutPlanUpdate: a full draft plus the version it was edited from. */
export class WorkoutPlanUpdateDto extends WorkoutPlanDraftDto {
  @ApiProperty({ minimum: 1 }) @IsInt() @Min(1)
  baseVersion!: number;
}

export class PlanVersionQuery {
  @ApiPropertyOptional({ minimum: 1 }) @IsOptional() @Type(() => Number) @IsInt() @Min(1) @Max(1_000_000)
  version?: number;
}
