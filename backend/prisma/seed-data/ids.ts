import { createHash } from 'node:crypto';

/** Fixed namespace so seed ids are stable across runs and environments. */
const NAMESPACE = '6f1c1d3e-5b7a-4c8e-9a2f-3d4b5c6e7f80';

/** RFC 4122 v5 UUID from a slug, so re-running the seed upserts the same rows. */
export function seedId(kind: 'exercise' | 'food', slug: string): string {
  const ns = Buffer.from(NAMESPACE.replace(/-/g, ''), 'hex');
  const hash = createHash('sha1').update(ns).update(`${kind}:${slug}`).digest();
  const bytes = hash.subarray(0, 16);
  bytes[6] = (bytes[6]! & 0x0f) | 0x50;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
