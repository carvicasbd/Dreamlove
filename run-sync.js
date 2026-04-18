import { syncDreamloveFeed } from '../src/services/sync-service.js';

try {
  const result = await syncDreamloveFeed();
  console.log(JSON.stringify(result, null, 2));
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
