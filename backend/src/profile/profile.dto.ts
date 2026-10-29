import { ApiPropertyOptional } from '@nestjs/swagger';
import { IsBoolean, IsIn, IsInt, IsNumber, IsOptional, IsString, Max, MaxLength, Min, MinLength } from 'class-validator';

import { IsDefinedWhenPresent, IsIanaTimezone } from '../common/validators';

export const SEX = ['male', 'female', 'other', 'prefer_not_to_say'] as const;
export const ACTIVITY = ['sedentary', 'light', 'moderate', 'active', 'very_active'] as const;
export const EXPERIENCE = ['beginner', 'intermediate', 'advanced'] as const;
export const UNIT_SYSTEM = ['metric', 'imperial'] as const;
export const DIET = ['vegetarian', 'eggetarian', 'non_vegetarian', 'vegan', 'jain'] as const;

const finite = { allowNaN: false, allowInfinity: false } as const;

/**
 * ProfileUpdate (contract). Every field is optional so onboarding can be
 * skipped and resumed: an omitted field is left as it is, and `null` clears
 * a nullable one. All values are metric; `unitSystem` is display-only.
 */
export class ProfileUpdateDto {
  @ApiPropertyOptional({ type: String, nullable: true, maxLength: 60 })
  @IsOptional() @IsString() @MinLength(1) @MaxLength(60)
  displayName?: string | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 1900, maximum: 2100 })
  @IsOptional() @IsInt() @Min(1900) @Max(2100)
  birthYear?: number | null;

  @ApiPropertyOptional({ enum: SEX, nullable: true })
  @IsOptional() @IsIn(SEX)
  sex?: (typeof SEX)[number] | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 50, maximum: 272 })
  @IsOptional() @IsNumber(finite) @Min(50) @Max(272)
  heightCm?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 20, maximum: 400 })
  @IsOptional() @IsNumber(finite) @Min(20) @Max(400)
  weightKg?: number | null;

  @ApiPropertyOptional({ enum: ACTIVITY, nullable: true })
  @IsOptional() @IsIn(ACTIVITY)
  activityLevel?: (typeof ACTIVITY)[number] | null;

  @ApiPropertyOptional({ enum: EXPERIENCE, nullable: true })
  @IsOptional() @IsIn(EXPERIENCE)
  experienceLevel?: (typeof EXPERIENCE)[number] | null;

  @ApiPropertyOptional({ enum: DIET, nullable: true })
  @IsOptional() @IsIn(DIET)
  dietPreference?: (typeof DIET)[number] | null;

  @ApiPropertyOptional({ enum: UNIT_SYSTEM })
  @IsDefinedWhenPresent() @IsIn(UNIT_SYSTEM)
  unitSystem?: (typeof UNIT_SYSTEM)[number];

  @ApiPropertyOptional({ maxLength: 64, example: 'Asia/Kolkata' })
  @IsDefinedWhenPresent() @IsIanaTimezone()
  timezone?: string;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 800, maximum: 6000 })
  @IsOptional() @IsInt() @Min(800) @Max(6000)
  dailyCalorieTargetKcal?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 250, maximum: 10000 })
  @IsOptional() @IsInt() @Min(250) @Max(10000)
  dailyWaterTargetMl?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0, maximum: 1000, description: '0 is stored as null' })
  @IsOptional() @IsInt() @Min(0) @Max(1000)
  dailyProteinTargetG?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0, maximum: 1000, description: '0 is stored as null' })
  @IsOptional() @IsInt() @Min(0) @Max(1000)
  dailyCarbsTargetG?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0, maximum: 1000, description: '0 is stored as null' })
  @IsOptional() @IsInt() @Min(0) @Max(1000)
  dailyFatTargetG?: number | null;

  @ApiPropertyOptional()
  @IsDefinedWhenPresent() @IsBoolean()
  onboardingCompleted?: boolean;
}
