import { violations } from './openapi';

/** Fails the test whose requests got a response that doesn't match contracts/openapi.yaml. */
afterEach(() => {
  const seen = violations.splice(0);
  if (seen.length) throw new Error(`Responses that don't match contracts/openapi.yaml:\n${seen.join('\n')}`);
});
