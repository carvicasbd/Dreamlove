import express from 'express';
import { env } from '../config/env.js';
import { getRecentLogs, getRecentRuns } from '../services/log-service.js';
import { getSyncState, syncDreamloveFeed } from '../services/sync-service.js';

export const apiRouter = express.Router();

apiRouter.get('/health', (_req, res) => {
  res.json({ ok: true, state: getSyncState() });
});

apiRouter.get('/config', (_req, res) => {
  res.json({
    appName: env.appName,
    storeDomain: env.storeDomain,
    apiVersion: env.apiVersion,
    locationId: env.locationId,
    xmlUrl: env.xmlUrl,
    syncCron: env.syncCron,
    autoStartCron: env.autoStartCron,
    stockZeroForMissing: env.stockZeroForMissing,
    archiveMissingProducts: env.archiveMissingProducts
  });
});

apiRouter.get('/runs', (_req, res) => {
  res.json({ ok: true, items: getRecentRuns(25) });
});

apiRouter.get('/logs', (_req, res) => {
  res.json({ ok: true, items: getRecentLogs(200) });
});

apiRouter.post('/sync', async (_req, res) => {
  try {
    const result = await syncDreamloveFeed();
    res.json({ ok: true, result });
  } catch (error) {
    res.status(500).json({ ok: false, error: error.message });
  }
});
