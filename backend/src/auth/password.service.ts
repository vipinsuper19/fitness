import { Injectable, OnModuleInit } from '@nestjs/common';
import * as argon2 from 'argon2';

/** argon2id with OWASP-recommended parameters (19 MiB, t=2, p=1). */
const OPTIONS = { type: argon2.argon2id, memoryCost: 19_456, timeCost: 2, parallelism: 1 } as const;

@Injectable()
export class PasswordService implements OnModuleInit {
  private dummyHash: Promise<string> | undefined;

  /** Built at startup so the first unknown-email login is not slower than later ones. */
  async onModuleInit(): Promise<void> {
    this.dummyHash = this.hash('dummy-password-for-timing-only');
    await this.dummyHash;
  }

  hash(password: string): Promise<string> {
    return argon2.hash(password, OPTIONS);
  }

  async verify(hash: string, password: string): Promise<boolean> {
    try {
      return await argon2.verify(hash, password);
    } catch {
      return false;
    }
  }

  /** Same work as a real check, so an unknown email takes as long as a wrong password. */
  async verifyAgainstDummy(password: string): Promise<false> {
    this.dummyHash ??= this.hash('dummy-password-for-timing-only');
    await this.verify(await this.dummyHash, password);
    return false;
  }
}
