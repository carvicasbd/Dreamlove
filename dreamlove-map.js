import { env } from './env.js';
import { asArray, pick, uniqueStrings } from '../utils/helpers.js';

export function extractDreamloveItems(parsedXml) {
  const catalog = parsedXml?.catalog || parsedXml?.products || parsedXml?.root || parsedXml;
  const items = asArray(
    catalog?.product ||
    catalog?.products?.product ||
    catalog?.item ||
    catalog?.items?.item
  );

  return items.map((item, index) => {
    const sku = String(pick(item, ['sku', 'reference', 'id', 'codigo'], `dreamlove-${index}`)).trim();
    const parentSku = String(pick(item, ['item_group_id', 'group_id', 'parent_sku'], sku)).trim();
    const commonTitle = String(pick(item, ['common_title', 'title', 'name', 'nombre'], sku)).trim();
    const variantName = String(pick(item, ['var_name_1', 'var_name_2'], '')).trim();
    const bodyHtml = String(pick(item, ['description', 'descripcion', 'long_description', 'desc'], '')).trim();
    const vendor = String(pick(item, ['brand', 'marca'], env.defaultVendor)).trim();
    const productType = String(pick(item, ['category', 'categoria', 'familia'], 'Dreamlove')).trim();
    const price = Number(pick(item, ['price', 'pvp', 'regular_price'], 0));
    const compareAtPrice = pick(item, ['price_original', 'compare_at_price', 'pvpr'], null);
    const stock = Number(pick(item, ['stock', 'quantity', 'qty'], 0));
    const barcode = pick(item, ['ean', 'barcode'], null);

    const option1Name = String(pick(item, ['var_name_1'], 'Color')).trim() || 'Color';
    const option1Value = String(pick(item, ['var_value_1', 'color', 'colour'], 'Default')).trim() || 'Default';
    const option2Name = String(pick(item, ['var_name_2'], 'Talla')).trim() || 'Talla';
    const option2Value = String(pick(item, ['var_value_2', 'size', 'talla'], 'Única')).trim() || 'Única';

    const imageCandidates = uniqueStrings([
      pick(item, ['image', 'image_1', 'main_image'], null),
      pick(item, ['image_2'], null),
      pick(item, ['image_3'], null),
      pick(item, ['image_4'], null),
      pick(item, ['image_5'], null)
    ]);

    const titleParts = [commonTitle, variantName].filter(Boolean);

    return {
      sku,
      parentSku,
      title: titleParts.join(' - ') || commonTitle,
      commonTitle,
      bodyHtml,
      vendor,
      productType,
      price,
      compareAtPrice: compareAtPrice ? String(compareAtPrice) : null,
      stock: Number.isFinite(stock) ? stock : 0,
      barcode,
      image: imageCandidates[0] || null,
      images: imageCandidates,
      options: [
        { name: option1Name, value: option1Value },
        { name: option2Name, value: option2Value }
      ]
    };
  });
}

export function groupVariantsByParent(items) {
  const map = new Map();

  for (const item of items) {
    const key = item.parentSku || item.sku;

    if (!map.has(key)) {
      map.set(key, {
        parentSku: key,
        title: item.commonTitle || item.title,
        bodyHtml: item.bodyHtml,
        vendor: item.vendor,
        productType: item.productType,
        images: new Set(item.images || []),
        variants: []
      });
    }

    const group = map.get(key);
    for (const img of item.images || []) group.images.add(img);
    group.variants.push(item);
  }

  return Array.from(map.values()).map((group) => ({
    ...group,
    images: Array.from(group.images)
  }));
}
