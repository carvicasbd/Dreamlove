import { select, update, upsert } from '../db/database.js';

export function upsertProductLink({ parentSku, shopifyProductId, shopifyHandle, title }) {
  const now = new Date().toISOString();
  return upsert(
    'product_links',
    (row) => row.parent_sku === parentSku,
    () => ({
      parent_sku: parentSku,
      shopify_product_id: shopifyProductId,
      shopify_handle: shopifyHandle,
      title,
      updated_at: now
    }),
    (row) => ({
      ...row,
      shopify_product_id: shopifyProductId,
      shopify_handle: shopifyHandle,
      title,
      updated_at: now
    })
  );
}

export function upsertVariantLink({ sku, parentSku, shopifyVariantId, inventoryItemId, locationId, lastQuantity, seenInLastFeed = 1 }) {
  const now = new Date().toISOString();
  return upsert(
    'variant_links',
    (row) => row.sku === sku,
    () => ({
      sku,
      parent_sku: parentSku,
      shopify_variant_id: shopifyVariantId,
      inventory_item_id: inventoryItemId,
      location_id: locationId,
      last_quantity: lastQuantity,
      seen_in_last_feed: seenInLastFeed,
      updated_at: now
    }),
    (row) => ({
      ...row,
      parent_sku: parentSku,
      shopify_variant_id: shopifyVariantId,
      inventory_item_id: inventoryItemId,
      location_id: locationId,
      last_quantity: lastQuantity,
      seen_in_last_feed: seenInLastFeed,
      updated_at: now
    })
  );
}

export function markAllVariantsAsUnseen() {
  update('variant_links', () => true, (row) => ({ ...row, seen_in_last_feed: 0 }));
}

export function getMissingVariants() {
  return select('variant_links', (row) => row.seen_in_last_feed === 0);
}
