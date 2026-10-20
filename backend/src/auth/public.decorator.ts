import { SetMetadata } from '@nestjs/common';

export const IS_PUBLIC = 'isPublic';
/** Routes that do not need an access token (register, login, refresh, health). */
export const Public = () => SetMetadata(IS_PUBLIC, true);
