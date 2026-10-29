import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { Transform, Type } from 'class-transformer';
import {
  IsBoolean, IsIn, IsInt, IsNotEmptyObject, IsNumber, IsObject, IsOptional, IsString, IsUUID, Max, MaxLength, Min, MinLength, ValidateIf, ValidateNested,
} from 'class-validator';

import { PageQuery } from '../common/page.dto';
import { IsCalendarDate, IsDefinedWhenPresent } from '../common/validators';

export const MEAL_TYPES = ['breakfast', 'lunch', 'dinner', 'snack'] as const;
export type MealTypeValue = (typeof MEAL_TYPES)[number];
export const SERVING_UNITS = ['g', 'ml', 'piece', 'katori', 'cup', 'tbsp', 'tsp', 'plate', 'glass', 'slice'] as const;

const dp = (places: number) => ({ allowNaN: false, allowInfinity: false, maxDecimalPlaces: places }) as const;
/** Positive (exclusiveMinimum 0 in the contract). */
const Positive = () => Min(0.01, { message: '$property must be greater than 0' });

/**
 * Nutrients (contract). Upper bounds keep 100 servings of one entry inside
 * the diary's numeric columns: 5000 kcal, 900 g of a macro and 9000 mg of
 * sodium per serving (or per overridden entry).
 */
export class NutrientsDto {
  @ApiProperty({ minimum: 0, maximum: 5000 }) @IsNumber(dp(1)) @Min(0) @Max(5000)
  energyKcal!: number;

  @ApiProperty({ minimum: 0, maximum: 900 }) @IsNumber(dp(1)) @Min(0) @Max(900)
  proteinG!: number;

  @ApiProperty({ minimum: 0, maximum: 900 }) @IsNumber(dp(1)) @Min(0) @Max(900)
  carbsG!: number;

  @ApiProperty({ minimum: 0, maximum: 900 }) @IsNumber(dp(1)) @Min(0) @Max(900)
  fatG!: number;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0, maximum: 900 }) @IsOptional() @IsNumber(dp(1)) @Min(0) @Max(900)
  fiberG?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0, maximum: 900 }) @IsOptional() @IsNumber(dp(1)) @Min(0) @Max(900)
  sugarG?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0, maximum: 9000 }) @IsOptional() @IsNumber(dp(1)) @Min(0) @Max(9000)
  sodiumMg?: number | null;
}

export class FoodSearchQuery extends PageQuery {
  @ApiProperty({ minLength: 1, maxLength: 100 })
  @Transform(({ value }: { value: unknown }) => (typeof value === 'string' ? value.trim() : value))
  @IsString() @MinLength(1) @MaxLength(100)
  query!: string;
}

export class FoodCreateDto {
  @ApiProperty({ minLength: 1, maxLength: 120 })
  @Transform(({ value }: { value: unknown }) => (typeof value === 'string' ? value.trim() : value))
  @IsString() @MinLength(1) @MaxLength(120)
  name!: string;

  @Transform(({ value }: { value: unknown }) => (typeof value === 'string' ? value.trim() || null : value))
  @ApiPropertyOptional({ type: String, nullable: true, maxLength: 80, description: 'Trimmed; blank becomes null' }) @IsOptional() @IsString() @MaxLength(80)
  brand?: string | null;

  @ApiProperty({ minimum: 0, exclusiveMinimum: true, maximum: 10000 }) @IsNumber(dp(2)) @Positive() @Max(10000)
  servingSize!: number;

  @ApiProperty({ enum: SERVING_UNITS }) @IsIn(SERVING_UNITS)
  servingUnit!: (typeof SERVING_UNITS)[number];

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0, exclusiveMinimum: true, maximum: 10000 })
  @IsOptional() @IsNumber(dp(2)) @Positive() @Max(10000)
  gramsPerServing?: number | null;

  @ApiProperty({ type: NutrientsDto }) @IsObject() @ValidateNested() @Type(() => NutrientsDto)
  nutrients!: NutrientsDto;

  @ApiPropertyOptional({ type: Boolean, nullable: true }) @IsOptional() @IsBoolean()
  isVegetarian?: boolean | null;
}

export const SUMMARY_BUCKETS = ['day', 'week', 'month'] as const;
export type SummaryBucket = (typeof SUMMARY_BUCKETS)[number];

export class NutritionSummaryQuery {
  @ApiProperty({ format: 'date' }) @IsCalendarDate()
  from!: string;

  @ApiProperty({ format: 'date' }) @IsCalendarDate()
  to!: string;

  @ApiPropertyOptional({ enum: SUMMARY_BUCKETS, default: 'day' }) @IsOptional() @IsIn(SUMMARY_BUCKETS)
  bucket?: SummaryBucket;
}

export class DateQuery {
  @ApiProperty({ format: 'date', description: "Local date in the user's timezone" }) @IsCalendarDate()
  date!: string;
}

export class FoodLogCreateDto {
  @ApiProperty({ format: 'uuid' }) @IsUUID()
  foodId!: string;

  @ApiProperty({ format: 'date' }) @IsCalendarDate()
  date!: string;

  @ApiProperty({ enum: MEAL_TYPES }) @IsIn(MEAL_TYPES)
  mealType!: MealTypeValue;

  @ApiProperty({ minimum: 0, exclusiveMinimum: true, maximum: 100 }) @IsNumber(dp(2)) @Positive() @Max(100)
  servings!: number;

  @ApiPropertyOptional({ type: NutrientsDto }) @IsOptional() @IsObject() @IsNotEmptyObject() @ValidateNested() @Type(() => NutrientsDto)
  nutrientsOverride?: NutrientsDto;
}

export class FoodLogUpdateDto {
  @ApiPropertyOptional({ enum: MEAL_TYPES }) @IsDefinedWhenPresent() @IsIn(MEAL_TYPES)
  mealType?: MealTypeValue;

  @ApiPropertyOptional({ minimum: 0, exclusiveMinimum: true, maximum: 100 }) @IsDefinedWhenPresent() @IsNumber(dp(2)) @Positive() @Max(100)
  servings?: number;

  @ApiPropertyOptional({ type: NutrientsDto, nullable: true, description: 'null restores the computed values' })
  @ValidateIf((_o: object, v: unknown) => v !== undefined && v !== null) @IsObject() @ValidateNested() @Type(() => NutrientsDto)
  nutrientsOverride?: NutrientsDto | null;
}

export class WaterLogDto {
  @ApiProperty({ format: 'date' }) @IsCalendarDate()
  date!: string;

  @ApiProperty({ minimum: 1, maximum: 5000 }) @IsInt() @Min(1) @Max(5000)
  amountMl!: number;
}

/** WaterLogUpdate: partial; a missing field stays unchanged. Neither field can be null; an empty body is 400. */
export class WaterLogUpdateDto {
  @ApiPropertyOptional({ format: 'date' }) @IsDefinedWhenPresent() @IsCalendarDate()
  date?: string;

  @ApiPropertyOptional({ minimum: 1, maximum: 5000 }) @IsDefinedWhenPresent() @IsInt() @Min(1) @Max(5000)
  amountMl?: number;
}

/**
 * FoodUpdate: partial edit of the caller's own custom food. A missing field stays
 * unchanged; brand, gramsPerServing and isVegetarian may be null to clear them;
 * nutrients, when sent, replace all values. An empty body is 400.
 */
export class FoodUpdateDto {
  @ApiPropertyOptional({ minLength: 1, maxLength: 120 })
  @Transform(({ value }: { value: unknown }) => (typeof value === 'string' ? value.trim() : value))
  @IsDefinedWhenPresent() @IsString() @MinLength(1) @MaxLength(120)
  name?: string;

  @Transform(({ value }: { value: unknown }) => (typeof value === 'string' ? value.trim() || null : value))
  @ApiPropertyOptional({ type: String, nullable: true, maxLength: 80, description: 'Trimmed; blank becomes null' }) @IsOptional() @IsString() @MaxLength(80)
  brand?: string | null;

  @ApiPropertyOptional({ minimum: 0, exclusiveMinimum: true, maximum: 10000 }) @IsDefinedWhenPresent() @IsNumber(dp(2)) @Positive() @Max(10000)
  servingSize?: number;

  @ApiPropertyOptional({ enum: SERVING_UNITS }) @IsDefinedWhenPresent() @IsIn(SERVING_UNITS)
  servingUnit?: (typeof SERVING_UNITS)[number];

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0, exclusiveMinimum: true, maximum: 10000 })
  @IsOptional() @IsNumber(dp(2)) @Positive() @Max(10000)
  gramsPerServing?: number | null;

  @ApiPropertyOptional({ type: NutrientsDto }) @IsDefinedWhenPresent() @IsObject() @ValidateNested() @Type(() => NutrientsDto)
  nutrients?: NutrientsDto;

  @ApiPropertyOptional({ type: Boolean, nullable: true }) @IsOptional() @IsBoolean()
  isVegetarian?: boolean | null;
}
