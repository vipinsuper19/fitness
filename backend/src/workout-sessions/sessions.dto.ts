import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { IsBoolean, IsIn, IsInt, IsISO8601, IsNumber, IsOptional, IsString, IsUUID, Max, MaxLength, Min } from 'class-validator';

import { PageQuery } from '../common/page.dto';
import { IsCalendarDate, IsDefinedWhenPresent } from '../common/validators';

const iso = { strict: true, strictSeparator: true } as const;
const finite = (places: number) => ({ allowNaN: false, allowInfinity: false, maxDecimalPlaces: places }) as const;

export class SessionStartDto {
  @ApiPropertyOptional({ format: 'uuid' }) @IsOptional() @IsUUID()
  plannedWorkoutId?: string;

  @ApiPropertyOptional({ minimum: 1 }) @IsOptional() @IsInt() @Min(1) @Max(1_000_000)
  planVersion?: number;

  @ApiPropertyOptional({ format: 'date-time', description: 'Client time for offline starts; defaults to server time' })
  @IsOptional() @IsISO8601(iso)
  startedAt?: string;
}

export class SessionUpdateDto {
  @ApiPropertyOptional({ enum: ['in_progress', 'paused'] }) @IsDefinedWhenPresent() @IsIn(['in_progress', 'paused'])
  status?: 'in_progress' | 'paused';

  @ApiPropertyOptional({ type: String, nullable: true, maxLength: 1000 }) @IsOptional() @IsString() @MaxLength(1000)
  notes?: string | null;
}

export class SessionCompleteDto {
  @ApiPropertyOptional({ format: 'date-time' }) @IsOptional() @IsISO8601(iso)
  completedAt?: string;

  @ApiPropertyOptional({ minimum: 1, maximum: 5 }) @IsOptional() @IsInt() @Min(1) @Max(5)
  perceivedDifficulty?: number;

  @ApiPropertyOptional({ type: String, nullable: true, maxLength: 1000 }) @IsOptional() @IsString() @MaxLength(1000)
  notes?: string | null;
}

/** PATCH /workout-sessions/{id}/completion (PRD 5.3); at least one field. */
export class SessionCompletionUpdateDto {
  @ApiPropertyOptional({ format: 'date-time', description: 'Today or yesterday (profile timezone), not in the future, after startedAt' })
  @IsOptional() @IsISO8601(iso)
  completedAt?: string;

  @ApiPropertyOptional({ minimum: 1, maximum: 5 }) @IsOptional() @IsInt() @Min(1) @Max(5)
  perceivedDifficulty?: number;

  @ApiPropertyOptional({ type: String, nullable: true, maxLength: 1000 }) @IsOptional() @IsString() @MaxLength(1000)
  notes?: string | null;
}

export class SetLogUpsertDto {
  @ApiProperty({ format: 'uuid', description: 'Generated on the device; the upsert key' }) @IsUUID()
  clientSetId!: string;

  @ApiProperty({ format: 'uuid' }) @IsUUID()
  exerciseId!: string;

  @ApiProperty({ minimum: 0, maximum: 50 }) @IsInt() @Min(0) @Max(50)
  setIndex!: number;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0, maximum: 1000 }) @IsOptional() @IsInt() @Min(0) @Max(1000)
  reps?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0, maximum: 1000, description: 'kg' })
  @IsOptional() @IsNumber(finite(2)) @Min(0) @Max(1000)
  weightKg?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0, maximum: 86400 }) @IsOptional() @IsInt() @Min(0) @Max(86400)
  durationSeconds?: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true, minimum: 0, maximum: 1000000, description: 'metres' })
  @IsOptional() @IsNumber(finite(1)) @Min(0) @Max(1_000_000)
  distanceM?: number | null;

  @ApiProperty() @IsBoolean()
  completed!: boolean;

  @ApiPropertyOptional({ format: 'date-time' }) @IsOptional() @IsISO8601(iso)
  loggedAt?: string;
}

export class HistoryQuery extends PageQuery {
  @ApiPropertyOptional({ format: 'date', description: 'Local date (profile timezone), inclusive' })
  @IsOptional() @IsCalendarDate()
  from?: string;

  @ApiPropertyOptional({ format: 'date', description: 'Local date (profile timezone), inclusive' })
  @IsOptional() @IsCalendarDate()
  to?: string;
}

