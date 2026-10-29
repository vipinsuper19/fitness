import { Body, Controller, Delete, HttpCode, HttpStatus, Post } from '@nestjs/common';
import { ApiBearerAuth, ApiHeader, ApiTags } from '@nestjs/swagger';

import { AuthResponse, AuthService } from './auth.service';
import { RateLimit } from '../common/rate-limit';
import { CurrentUserId } from './current-user.decorator';
import { DeleteAccountDto, LoginDto, RefreshDto, RegisterDto } from './dto/auth.dto';
import { IdempotencyKeyHeader } from './idempotency-key';
import { Public } from './public.decorator';
import type { TokenPair } from './token.service';

@ApiTags('auth')
@Controller('auth')
export class AuthController {
  constructor(private readonly auth: AuthService) {}

  @Public()
  @RateLimit('auth')
  @Post('register')
  @HttpCode(HttpStatus.CREATED)
  @ApiHeader({ name: 'Idempotency-Key', required: false })
  register(@Body() dto: RegisterDto, @IdempotencyKeyHeader() key?: string): Promise<AuthResponse> {
    return this.auth.register(dto, key);
  }

  @Public()
  @RateLimit('auth')
  @Post('login')
  @HttpCode(HttpStatus.OK)
  login(@Body() dto: LoginDto): Promise<AuthResponse> {
    return this.auth.login(dto);
  }

  @Public()
  // Looser 'refresh' bucket instead of the strict 'auth' one.
  @RateLimit('refresh')
  @Post('refresh')
  @HttpCode(HttpStatus.OK)
  refresh(@Body() dto: RefreshDto): Promise<TokenPair> {
    return this.auth.refresh(dto.refreshToken);
  }

  @RateLimit('auth')
  @Post('logout')
  @HttpCode(HttpStatus.NO_CONTENT)
  @ApiBearerAuth()
  async logout(@CurrentUserId() userId: string, @Body() dto: RefreshDto): Promise<void> {
    await this.auth.logout(userId, dto.refreshToken);
  }
}

@ApiTags('me')
@Controller('me')
export class MeController {
  constructor(private readonly auth: AuthService) {}

  @RateLimit('auth')
  @Delete()
  @HttpCode(HttpStatus.NO_CONTENT)
  @ApiBearerAuth()
  async deleteAccount(@CurrentUserId() userId: string, @Body() dto: DeleteAccountDto): Promise<void> {
    await this.auth.deleteAccount(userId, dto.password);
  }
}
