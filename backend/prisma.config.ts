import { defineConfig } from 'prisma/config';

// Migrations use Supabase's direct connection (DIRECT_URL, port 5432) when
// set; the app itself uses the pooled DATABASE_URL. No values in the repo.
export default defineConfig({
  schema: 'prisma/schema.prisma',
  migrations: { path: 'prisma/migrations', seed: 'tsx prisma/seed.ts' },
  datasource: { url: process.env.DIRECT_URL ?? process.env.DATABASE_URL ?? '' },
});
