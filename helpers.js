export function asArray(value) {
  if (!value) return [];
  return Array.isArray(value) ? value : [value];
}

export function pick(obj, keys, fallback = null) {
  for (const key of keys) {
    if (obj?.[key] !== undefined && obj?.[key] !== null && obj?.[key] !== '') {
      return obj[key];
    }
  }
  return fallback;
}

export function uniqueStrings(values) {
  return [...new Set(values.filter(Boolean).map((v) => String(v).trim()).filter(Boolean))];
}

export function slugify(input) {
  return String(input || '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/(^-|-$)/g, '');
}

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
