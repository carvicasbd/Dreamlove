import axios from 'axios';
import crypto from 'crypto';
import { env } from '../config/env.js';

function gqlUrl() {
  return `https://${env.storeDomain}/admin/api/${env.apiVersion}/graphql.json`;
}

export async function shopifyGraphQL(query, variables = {}) {
  const response = await axios.post(
    gqlUrl(),
    { query, variables },
    {
      headers: {
        'X-Shopify-Access-Token': env.adminToken,
        'Content-Type': 'application/json'
      },
      timeout: 120000
    }
  );

  if (response.data.errors?.length) {
    throw new Error(`Shopify GraphQL error: ${JSON.stringify(response.data.errors)}`);
  }

  return response.data.data;
}

export async function findVariantBySku(sku) {
  const query = `#graphql
    query FindVariantBySku($query: String!) {
      productVariants(first: 1, query: $query) {
        edges {
          node {
            id
            sku
            inventoryItem { id }
            product {
              id
              title
              handle
              status
            }
          }
        }
      }
    }
  `;

  const data = await shopifyGraphQL(query, { query: `sku:${sku}` });
  return data.productVariants.edges[0]?.node || null;
}

export async function productSet(input) {
  const mutation = `#graphql
    mutation ProductSet($input: ProductSetInput!) {
      productSet(input: $input, synchronous: true) {
        product {
          id
          title
          handle
          variants(first: 100) {
            edges {
              node {
                id
                sku
                inventoryItem { id }
              }
            }
          }
        }
        userErrors {
          field
          message
        }
      }
    }
  `;

  const data = await shopifyGraphQL(mutation, { input });
  const result = data.productSet;
  if (result.userErrors?.length) {
    throw new Error(`productSet error: ${JSON.stringify(result.userErrors)}`);
  }
  return result.product;
}

export async function setInventoryQuantity({ inventoryItemId, locationId, quantity, referenceDocumentUri, compareQuantity = null }) {
  const idempotencyKey = crypto.randomUUID();
  const mutation = `#graphql
    mutation InventorySet($input: InventorySetQuantitiesInput!) @idempotent(key: "${idempotencyKey}") {
      inventorySetQuantities(input: $input) {
        inventoryAdjustmentGroup {
          id
          reason
          changes {
            name
            delta
          }
        }
        userErrors {
          field
          message
        }
      }
    }
  `;

  const quantityPayload = {
    inventoryItemId,
    locationId,
    quantity
  };

  if (Number.isFinite(compareQuantity)) {
    quantityPayload.compareQuantity = compareQuantity;
  }

  const input = {
    name: 'available',
    reason: 'correction',
    ignoreCompareQuantity: !Number.isFinite(compareQuantity),
    referenceDocumentUri,
    quantities: [quantityPayload]
  };

  const data = await shopifyGraphQL(mutation, { input });
  const result = data.inventorySetQuantities;
  if (result.userErrors?.length) {
    throw new Error(`inventorySetQuantities error: ${JSON.stringify(result.userErrors)}`);
  }
  return result.inventoryAdjustmentGroup;
}

export async function setProductStatus(productId, status) {
  const mutation = `#graphql
    mutation UpdateProductStatus($input: ProductSetInput!) {
      productSet(input: $input, synchronous: true) {
        product { id status title }
        userErrors { field message }
      }
    }
  `;

  const data = await shopifyGraphQL(mutation, {
    input: { id: productId, status }
  });

  const result = data.productSet;
  if (result.userErrors?.length) {
    throw new Error(`setProductStatus error: ${JSON.stringify(result.userErrors)}`);
  }

  return result.product;
}
