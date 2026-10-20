import { Global, Module } from '@nestjs/common';

import { IdempotencyService } from './idempotency.service';
import { IdempotentWriteService } from './idempotent-write.service';

@Global()
@Module({ providers: [IdempotencyService, IdempotentWriteService], exports: [IdempotencyService, IdempotentWriteService] })
export class IdempotencyModule {}
