import { ApiPropertyOptional } from '@nestjs/swagger';
import { Type } from 'class-transformer';
import { IsIn, IsInt, IsOptional, IsString, Max, MaxLength, Min } from 'class-validator';

import { SummaryQuery } from './progress.dto';

export const BUCKETS = ['day', 'week', 'month'] as const;

/** `GET /progress/exercises?from&to&cursor&limit`. */
export class ExerciseProgressListQuery extends SummaryQuery {
  @ApiPropertyOptional({ maxLength: 200 }) @IsOptional() @IsString() @MaxLength(200)
  cursor?: string;

  @ApiPropertyOptional({ minimum: 1, maximum: 100, default: 20 }) @IsOptional() @Type(() => Number) @IsInt() @Min(1) @Max(100)
  limit?: number;
}

/** `GET /progress/exercises/{exerciseId}?from&to&bucket`. */
export class ExerciseProgressQuery extends SummaryQuery {
  @ApiPropertyOptional({ enum: BUCKETS, default: 'day' }) @IsOptional() @IsIn(BUCKETS)
  bucket?: (typeof BUCKETS)[number];
}
