# Sabadell Transport Live

Aplicación Android para consultar información de Renfe/Rodalies, TUS Sabadell, Sagalés y Monbus.

- Renfe: GTFS-Realtime oficial (`trip_updates.json` y `alerts.json`).
- TUS, Sagalés y Monbus: páginas oficiales abiertas dentro de una vista integrada.
- La aplicación diferencia datos en tiempo real, avisos oficiales y ausencia de datos abiertos.

## Compilar

```bash
gradle :app:assembleDebug
```
