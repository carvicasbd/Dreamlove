import { getDbPath, initDb } from '../src/db/database.js';
initDb();
console.log(`Base JSON inicializada en ${getDbPath()}`);
