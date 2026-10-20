import { ApiProperty, ApiPropertyOptional, ApiPropertyOptional as Opt, PartialType } from '@nestjs/swagger';
import { IsNumber, IsOptional, Max, Min, ValidateIf } from 'class-validator';

import { PageQuery } from '../common/page.dto';
import { IsCalendarDate } from '../common/validators';

const dp = (places: number) => ({ allowNaN: false, allowInfinity: false, maxDecimalPlaces: places }) as const;

export const MEASUREMENT_FIELDS = ['weightKg', 'bodyFatPct', 'waistCm', 'chestCm', 'hipCm', 'armCm', 'thighCm'] as const;
export type MeasurementField = (typeof MEASUREMENT_FIELDS)[number];

/** BodyMeasurementCreate. */
export class BodyMeasurementDto {
  @ApiProperty({ format: 'date' }) @IsCalendarDate()
  date!: string;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 20, maximum: 400 }) @IsOptional() @IsNumber(dp(2)) @Min(20) @Max(400)
  weightKg?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 2, maximum: 70 }) @IsOptional() @IsNumber(dp(1)) @Min(2) @Max(70)
  bodyFatPct?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 30, maximum: 250 }) @IsOptional() @IsNumber(dp(1)) @Min(30) @Max(250)
  waistCm?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 30, maximum: 250 }) @IsOptional() @IsNumber(dp(1)) @Min(30) @Max(250)
  chestCm?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 30, maximum: 250 }) @IsOptional() @IsNumber(dp(1)) @Min(30) @Max(250)
  hipCm?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 10, maximum: 100 }) @IsOptional() @IsNumber(dp(1)) @Min(10) @Max(100)
  armCm?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 20, maximum: 150 }) @IsOptional() @IsNumber(dp(1)) @Min(20) @Max(150)
  thighCm?: number | null;
}

/** BodyMeasurementUpdate: partial; a missing field stays unchanged, null clears a measurement. */
export class BodyMeasurementUpdateDto extends PartialType(BodyMeasurementDto) {
  @Opt({ format: 'date' }) @ValidateIf((o: BodyMeasurementUpdateDto) => o.date !== undefined) @IsCalendarDate()
  override date?: string;
}

export class MeasurementListQuery extends PageQuery {
  @ApiPropertyOptional({ format: 'date' }) @IsOptional() @IsCalendarDate()
  from?: string;

  @ApiPropertyOptional({ format: 'date' }) @IsOptional() @IsCalendarDate()
  to?: string;
}

export class SummaryQuery {
  @ApiProperty({ format: 'date' }) @IsCalendarDate()
  from!: string;

  @ApiProperty({ format: 'date' }) @IsCalendarDate()
  to!: string;
}
