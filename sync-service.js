import { env, validateEnv } from '../config/env.js';
import { extractDreamloveItems, groupVariantsByParent } from '../config/dreamlove-map.js';
import { downloadAndParseXml } from '../lib/xml.js';
import { findVariantBySku, productSet, setInventoryQuantity, setProductStatus } from '../lib/shopify.js';
import { addLog, createRun, finishRun } from './log-service.js';
import { getMissingVariants, markAllVariantsAsUnseen, upsertProductLink, upsertVariantLink } from './link-service.js';

let running = false;
let lastState = {
  running: false,
  lastRunId: null,
  lastSummary: null,
  lastError: null,
  startedAt: null,
  finishedAt: null
};

function buildProductSetInput(group, existingProductId = null) {
  const optionNames = [];
  const optionMap = new Map();

  for (const variant of group.variants) {
    for (const opt of variant.options) {
      if (!optionNames.includes(opt.name)) optionNames.push(opt.name);
      if (!optionMap.has(opt.name)) optionMap.set(opt.name, new Set());
      optionMap.get(opt.name).add(opt.value);
    }
  }

  const productOptions = optionNames.map((name, idx) => ({
    name,
    position: idx + 1,
    values: Array.from(optionMap.get(name)).map((value) => ({ name: value }))
  }));

  const variants = group.variants.map((variant, idx) => ({
    sku: variant.sku,
    price: String(variant.price || 0),
    compareAtPrice: variant.compareAtPrice || undefined,
    barcode: variant.barcode || undefined,
    taxable: true,
    inventoryPolicy: 'DENY',
    optionValues: variant.options.map((opt) => ({ optionName: opt.name, name: opt.value })),
    file: variant.image ? { originalSource: variant.image, alt: variant.title || group.title } : undefined,
    position: idx + 1
  }));

  const input = {
    title: group.title,
    descriptionHtml: group.bodyHtml,
    vendor: group.vendor,
    productType: group.productType,
    status: env.defaultProductStatus,
    productOptions,
    files: group.images.slice(0, env.maxProductImages).map((src) => ({ originalSource: src, alt: group.title })),
    variants
  };

  if (existingProductId) input.id = existingProductId;
  return input;
}

async function syncMissingVariants(runId, result) {
  const missing = getMissingVariants();
  result.missingSkus = missing.length;

  if (!missing.length) {
    addLog(runId, 'info', 'No hay SKUs desaparecidos respecto al último feed.', 'missing');
    return;
  }

  addLog(runId, 'warning', `Detectados ${missing.length} SKUs ausentes en el feed actual.`, 'missing');

  for (const row of missing) {
    try {
      if (env.stockZeroForMissing && row.inventory_item_id) {
        await setInventoryQuantity({
          inventoryItemId: row.inventory_item_id,
          locationId: row.location_id || env.locationId,
          quantity: 0,
          compareQuantity: row.last_quantity,
          referenceDocumentUri: `gid://dreamlove-import/missing/${row.sku}`
        });
        addLog(runId, 'warning', `SKU ausente ${row.sku} ajustado a stock 0.`, 'missing', row.sku);
      }

      if (env.archiveMissingProducts && row.parent_sku) {
        const variant = await findVariantBySku(row.sku);
        if (variant?.product?.id) {
          await setProductStatus(variant.product.id, 'DRAFT');
          addLog(runId, 'warning', `Producto del SKU ${row.sku} marcado como borrador.`, 'missing', row.sku);
        }
      }
    } catch (error) {
      result.errors.push(`SKU ausente ${row.sku}: ${error.message}`);
      addLog(runId, 'error', `Error tratando SKU ausente ${row.sku}: ${error.message}`, 'missing', row.sku);
    }
  }
}

export async function syncDreamloveFeed() {
  if (running) {
    return { ok: false, message: 'Ya hay una sincronización en curso.' };
  }

  validateEnv();
  const runId = createRun();
  running = true;
  lastState = { ...lastState, running: true, lastRunId: runId, startedAt: new Date().toISOString(), finishedAt: null, lastError: null };

  const result = {
    ok: true,
    runId,
    processedProducts: 0,
    processedVariants: 0,
    inventoryUpdated: 0,
    missingSkus: 0,
    errors: [],
    summary: ''
  };

  try {
    addLog(runId, 'info', 'Inicio de sincronización Dreamlove.', 'sync');
    markAllVariantsAsUnseen();

    const parsed = await downloadAndParseXml(env.xmlUrl);
    const items = extractDreamloveItems(parsed);
    const groups = groupVariantsByParent(items);

    addLog(runId, 'info', `XML descargado. Items detectados: ${items.length}. Productos agrupados: ${groups.length}.`, 'feed');

    for (const group of groups) {
      try {
        const existingByFirstSku = group.variants[0]?.sku ? await findVariantBySku(group.variants[0].sku) : null;
        const existingProductId = existingByFirstSku?.product?.id || null;
        const productInput = buildProductSetInput(group, existingProductId);
        const product = await productSet(productInput);

        upsertProductLink({
          parentSku: group.parentSku,
          shopifyProductId: product.id,
          shopifyHandle: product.handle,
          title: product.title
        });

        addLog(runId, 'info', `Producto sincronizado: ${group.title}`, 'product', group.parentSku);
        result.processedProducts += 1;

        const productVariants = product.variants?.edges?.map((edge) => edge.node) || [];

        for (const variant of group.variants) {
          try {
            let variantNode = productVariants.find((v) => v.sku === variant.sku);
            if (!variantNode) variantNode = await findVariantBySku(variant.sku);

            if (!variantNode?.inventoryItem?.id) {
              throw new Error('No se localizó inventoryItem para la variante');
            }

            await setInventoryQuantity({
              inventoryItemId: variantNode.inventoryItem.id,
              locationId: env.locationId,
              quantity: variant.stock,
              referenceDocumentUri: `gid://dreamlove-import/sku/${variant.sku}`
            });

            upsertVariantLink({
              sku: variant.sku,
              parentSku: group.parentSku,
              shopifyVariantId: variantNode.id,
              inventoryItemId: variantNode.inventoryItem.id,
              locationId: env.locationId,
              lastQuantity: variant.stock,
              seenInLastFeed: 1
            });

            addLog(runId, 'info', `Stock actualizado SKU ${variant.sku} => ${variant.stock}`, 'variant', variant.sku);
            result.processedVariants += 1;
            result.inventoryUpdated += 1;
          } catch (error) {
            result.errors.push(`SKU ${variant.sku}: ${error.message}`);
            addLog(runId, 'error', `Error en SKU ${variant.sku}: ${error.message}`, 'variant', variant.sku);
          }
        }
      } catch (error) {
        result.errors.push(`Producto ${group.parentSku}: ${error.message}`);
        addLog(runId, 'error', `Error en producto ${group.parentSku}: ${error.message}`, 'product', group.parentSku);
      }
    }

    await syncMissingVariants(runId, result);

    result.summary = `Productos ${result.processedProducts}, variantes ${result.processedVariants}, stock ${result.inventoryUpdated}, ausentes ${result.missingSkus}, errores ${result.errors.length}`;
    finishRun(runId, {
      status: result.errors.length ? 'finished_with_errors' : 'success',
      summary: result.summary,
      processedProducts: result.processedProducts,
      processedVariants: result.processedVariants,
      inventoryUpdated: result.inventoryUpdated,
      missingSkus: result.missingSkus,
      errorsCount: result.errors.length
    });

    addLog(runId, 'info', result.summary, 'sync');
    lastState = { ...lastState, running: false, lastSummary: result.summary, finishedAt: new Date().toISOString() };
    return result;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    result.ok = false;
    result.errors.push(message);
    finishRun(runId, {
      status: 'failed',
      summary: message,
      processedProducts: result.processedProducts,
      processedVariants: result.processedVariants,
      inventoryUpdated: result.inventoryUpdated,
      missingSkus: result.missingSkus,
      errorsCount: result.errors.length
    });
    addLog(runId, 'error', message, 'sync');
    lastState = { ...lastState, running: false, lastError: message, finishedAt: new Date().toISOString() };
    throw error;
  } finally {
    running = false;
  }
}

export function getSyncState() {
  return { ...lastState, running };
}
