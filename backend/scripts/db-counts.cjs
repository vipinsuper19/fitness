// Prints catalog row counts. Reads DATABASE_URL; never prints it.
const { Client } = require('pg');
(async () => {
  const c = new Client({ connectionString: process.env.DATABASE_URL });
  await c.connect();
  const r = await c.query('select (select count(*) from exercises)::int as exercises, (select count(*) from food_items)::int as foods');
  console.log(`${process.argv[2] || 'counts'}: exercises=${r.rows[0].exercises} foods=${r.rows[0].foods}`);
  await c.end();
})().catch((e) => { console.error(String(e.message).replace(/postgres(ql)?:\/\/\S+/g, '[REDACTED]')); process.exit(1); });
