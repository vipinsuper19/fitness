import { Controller, Get, Param, Query } from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';

import { IdParamPipe } from '../common/id-param.pipe';
import { ExerciseListQuery } from './exercises.dto';
import { ExercisesService, ExerciseView } from './exercises.service';

@ApiTags('exercises')
@ApiBearerAuth()
@Controller('exercises')
export class ExercisesController {
  constructor(private readonly exercises: ExercisesService) {}

  @Get()
  list(@Query() q: ExerciseListQuery): Promise<{ items: ExerciseView[]; nextCursor: string | null }> {
    return this.exercises.list(q);
  }

  @Get(':exerciseId')
  get(@Param('exerciseId', IdParamPipe) id: string): Promise<ExerciseView> {
    return this.exercises.get(id);
  }
}
