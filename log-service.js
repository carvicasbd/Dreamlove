import { insert, select, update } from '../db/database.js';

export function createRun() {
  const row = insert('sync_runs', {
    started_at: new Date().toISOString(),
    finished_at: null,
    status: 'running',
    summary: null,
    processed_products: 0,
    processed_variants: 0,
    inventory_updated: 0,
    missing_skus: 0,
    errors_count: 0
  });
  return row.id;
}

export function finishRun(runId, payload) {
  update('sync_runs', (row) => row.id === runId, (row) => ({
    ...row,
    finished_at: new Date().toISOString(),
    status: payload.status,
    summary: payload.summary,
    processed_products: payload.processedProducts,
    processed_variants: payload.processedVariants,
    inventory_updated: payload.inventoryUpdated,
    missing_skus: payload.missingSkus,
    errors_count: payload.errorsCount
  }));
}

export function addLog(runId, level, message, scope = null, ref = null) {
  insert('sync_logs', {
    run_id: runId,
    level,
    scope,
    ref,
    message,
    created_at: new Date().toISOString()
  });
}

export function getRecentRuns(limit = 20) {
  return select('sync_runs')
    .sort((a, b) => b.id - a.id)
    .slice(0, limit);
}

export function getRecentLogs(limit = 200) {
  return select('sync_logs')
    .sort((a, b) => b.id - a.id)
    .slice(0, limit);
}
