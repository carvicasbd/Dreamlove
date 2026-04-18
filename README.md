# Dreamlove Shopify Importer PRO

Versión PRO de una app/importador para Shopify que sincroniza automáticamente el feed XML de Dreamlove con catálogo, variantes, imágenes, stock, logs y trazabilidad local.

## Qué hace

- Descarga y analiza el XML de Dreamlove.
- Agrupa variantes por producto padre.
- Crea o actualiza productos en Shopify con `productSet`.
- Actualiza stock con `inventorySetQuantities` usando idempotencia.
- Guarda un historial local en JSON.
- Lleva log por ejecución, producto y SKU.
- Detecta productos/variantes ausentes en el feed y puede poner su stock a 0.
- Panel web para lanzar sync manual, ver estado, últimos logs y configuración activa.

## Stack

- Node.js 20+
- Express
- almacenamiento local JSON
- Shopify Admin GraphQL API

## Scopes mínimos en Shopify

- `read_products`
- `write_products`
- `read_inventory`
- `write_inventory`

## Instalación

```bash
cp .env.example .env
npm install
npm run db:init
npm run dev
```

Panel:

```bash
http://localhost:3000
```

## Endpoints

- `GET /` Panel principal
- `GET /health` Estado técnico
- `POST /sync` Lanza sincronización manual
- `GET /api/runs` Últimas ejecuciones
- `GET /api/logs` Últimos logs
- `GET /api/config` Configuración segura visible

## Flujo de sincronización

1. Descargar XML
2. Extraer items del feed
3. Agrupar por `item_group_id` / SKU padre
4. Buscar producto existente por variante SKU
5. Crear/actualizar producto con `productSet`
6. Buscar inventoryItem por SKU
7. Aplicar stock absoluto con `inventorySetQuantities`
8. Marcar en BD el resultado
9. Detectar SKUs ausentes y, si está activado, dejar stock a 0

## Notas importantes

- El mapeo del XML está preparado para Dreamlove, pero puede requerir ajustar claves si el feed cambia.
- Esta versión guarda estado local en `storage/dreamlove-db.json`.
- Está orientada a una **Custom App / integración privada**. Si luego quieres una app embebida completa dentro del admin de Shopify, esta base se puede migrar a App Bridge + OAuth.

## Estructura

```text
src/
  config/
  db/
  lib/
  routes/
  services/
  utils/
  views/
scripts/
storage/
```

## Siguientes mejoras recomendadas

- Colecciones automáticas por categoría/marca
- Metafields Dreamlove
- Gestión avanzada de imágenes duplicadas
- Cola distribuida / workers separados
- OAuth real para múltiples tiendas
- UI embebida dentro del admin de Shopify
