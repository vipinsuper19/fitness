import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { IsIn, IsInt, IsOptional, IsString, MaxLength, Min } from 'class-validator';

import { PageQuery } from '../common/page.dto';

export const CHALLENGE_STATUSES = ['active', 'completed', 'expired', 'left'] as const;
export type ChallengeStatusValue = (typeof CHALLENGE_STATUSES)[number];

export class ChallengeJoinDto {
  @ApiProperty({ maxLength: 40 })
  @IsString() @MaxLength(40)
  templateCode!: string;

  @ApiProperty({ minimum: 1 })
  @IsInt() @Min(1)
  target!: number;
}

export class ChallengeListQuery extends PageQuery {
  @ApiPropertyOptional({ enum: CHALLENGE_STATUSES })
  @IsOptional() @IsIn(CHALLENGE_STATUSES)
  status?: ChallengeStatusValue;
}
