import express from 'express';
import cron from 'node-cron';
import { env } from './config/env.js';
import { apiRouter } from './routes/api.js';
import { getRecentLogs, getRecentRuns } from './services/log-service.js';
import { getSyncState, syncDreamloveFeed } from './services/sync-service.js';

const app = express();
app.set('view engine', 'ejs');
app.set('views', new URL('./views', import.meta.url).pathname);
app.use(express.urlencoded({ extended: true }));
app.use(express.json());

app.get('/', (_req, res) => {
  res.render('index', {
    appName: env.appName,
    state: getSyncState(),
    runs: getRecentRuns(15),
    logs: getRecentLogs(100),
    config: {
      storeDomain: env.storeDomain,
      apiVersion: env.apiVersion,
      locationId: env.locationId,
      xmlUrl: env.xmlUrl,
      syncCron: env.syncCron,
      autoStartCron: env.autoStartCron,
      stockZeroForMissing: env.stockZeroForMissing,
      archiveMissingProducts: env.archiveMissingProducts
    }
  });
});

app.post('/sync', async (_req, res) => {
  try {
    await syncDreamloveFeed();
  } catch (error) {
    console.error('[SYNC]', error.message);
  }
  res.redirect('/');
});

app.use('/api', apiRouter);
app.get('/health', (_req, res) => res.json({ ok: true, state: getSyncState() }));

if (env.autoStartCron) {
  cron.schedule(env.syncCron, async () => {
    try {
      await syncDreamloveFeed();
      console.log('[CRON] Sync finalizada');
    } catch (error) {
      console.error('[CRON] Error', error.message);
    }
  });
}

app.listen(env.port, () => {
  console.log(`${env.appName} activo en ${env.appBaseUrl}`);
});
