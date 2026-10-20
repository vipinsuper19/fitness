import { ApiPropertyOptional } from '@nestjs/swagger';
import { Transform, Type } from 'class-transformer';
import { IsIn, IsInt, IsOptional, IsString, Max, MaxLength, Min } from 'class-validator';

export const MUSCLES = ['chest', 'back', 'shoulders', 'biceps', 'triceps', 'forearms', 'core', 'quads', 'hamstrings', 'glutes', 'calves', 'full_body', 'cardio'] as const;
export const EQUIPMENT = ['none', 'dumbbell', 'barbell', 'kettlebell', 'machine', 'cable', 'resistance_band', 'bench', 'pull_up_bar', 'other'] as const;
export type MuscleValue = (typeof MUSCLES)[number];
export type EquipmentValue = (typeof EQUIPMENT)[number];

export class ExerciseListQuery {
  @ApiPropertyOptional({ maxLength: 100 })
  @IsOptional() @Transform(({ value }: { value: unknown }) => (typeof value === 'string' ? value.trim() : value)) @IsString() @MaxLength(100)
  query?: string;

  @ApiPropertyOptional({ enum: MUSCLES })
  @IsOptional() @IsIn(MUSCLES)
  muscle?: MuscleValue;

  @ApiPropertyOptional({ enum: EQUIPMENT })
  @IsOptional() @IsIn(EQUIPMENT)
  equipment?: EquipmentValue;

  @ApiPropertyOptional({ maxLength: 200 })
  @IsOptional() @IsString() @MaxLength(200)
  cursor?: string;

  @ApiPropertyOptional({ minimum: 1, maximum: 100, default: 20 })
  @IsOptional() @Type(() => Number) @IsInt() @Min(1) @Max(100)
  limit?: number;
}
