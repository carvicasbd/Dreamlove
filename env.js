import dotenv from 'dotenv';
dotenv.config();

export const env = {
  port: Number(process.env.PORT || 3000),
  appName: process.env.APP_NAME || 'Dreamlove Shopify Importer PRO',
  appBaseUrl: process.env.APP_BASE_URL || `http://localhost:${process.env.PORT || 3000}`,
  storeDomain: process.env.SHOPIFY_STORE_DOMAIN || '',
  adminToken: process.env.SHOPIFY_ADMIN_TOKEN || '',
  apiVersion: process.env.SHOPIFY_API_VERSION || '2026-04',
  locationId: process.env.SHOPIFY_LOCATION_ID || '',
  xmlUrl: process.env.DREAMLOVE_XML_URL || '',
  defaultVendor: process.env.DEFAULT_VENDOR || 'Dreamlove',
  defaultProductStatus: process.env.DEFAULT_PRODUCT_STATUS || 'ACTIVE',
  syncCron: process.env.SYNC_CRON || '*/30 * * * *',
  autoStartCron: String(process.env.AUTO_START_CRON || 'true') === 'true',
  stockZeroForMissing: String(process.env.SYNC_STOCK_ZERO_FOR_MISSING || 'true') === 'true',
  archiveMissingProducts: String(process.env.ARCHIVE_MISSING_PRODUCTS || 'false') === 'true',
  maxProductImages: Number(process.env.MAX_PRODUCT_IMAGES || 10)
};

export function validateEnv() {
  const required = ['storeDomain', 'adminToken', 'locationId', 'xmlUrl'];
  const missing = required.filter((key) => !env[key]);
  if (missing.length) {
    throw new Error(`Faltan variables de entorno: ${missing.join(', ')}`);
  }
}
