import fs from 'fs';
import path from 'path';

const storageDir = path.resolve(process.cwd(), 'storage');
if (!fs.existsSync(storageDir)) fs.mkdirSync(storageDir, { recursive: true });

const dbPath = path.join(storageDir, 'dreamlove-db.json');

const initialData = {
  sync_runs: [],
  sync_logs: [],
  product_links: [],
  variant_links: [],
  counters: {
    sync_runs: 0,
    sync_logs: 0,
    product_links: 0,
    variant_links: 0
  }
};

function readDb() {
  if (!fs.existsSync(dbPath)) {
    fs.writeFileSync(dbPath, JSON.stringify(initialData, null, 2));
    return structuredClone(initialData);
  }
  return JSON.parse(fs.readFileSync(dbPath, 'utf8'));
}

function writeDb(data) {
  fs.writeFileSync(dbPath, JSON.stringify(data, null, 2));
}

export function initDb() {
  if (!fs.existsSync(dbPath)) writeDb(initialData);
  return dbPath;
}

export function insert(table, row) {
  const db = readDb();
  db.counters[table] = (db.counters[table] || 0) + 1;
  const newRow = { id: db.counters[table], ...row };
  db[table].push(newRow);
  writeDb(db);
  return newRow;
}

export function update(table, predicate, updater) {
  const db = readDb();
  db[table] = db[table].map((row) => predicate(row) ? updater(row) : row);
  writeDb(db);
}

export function upsert(table, predicate, createRow, updateRow) {
  const db = readDb();
  const index = db[table].findIndex(predicate);
  if (index === -1) {
    db.counters[table] = (db.counters[table] || 0) + 1;
    const row = { id: db.counters[table], ...createRow() };
    db[table].push(row);
    writeDb(db);
    return row;
  }
  db[table][index] = updateRow(db[table][index]);
  writeDb(db);
  return db[table][index];
}

export function select(table, predicate = () => true) {
  const db = readDb();
  return db[table].filter(predicate);
}

export function all(table) {
  const db = readDb();
  return db[table];
}

export function getDbPath() {
  return dbPath;
}

initDb();
