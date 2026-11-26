# NavBeam — Roadmap & Backlog

---

## 🟢 P1 — Map & Routing Independence

- [ ] **Keyless Open Vector Basemap (In-App & Streamed Dash):**
  - Currently, `NavBeamMap` uses the Google Maps Compose SDK (requires `MAPS_API_KEY`) and the streamed dash (`TileProvider` + `MapRenderer`) fetches `mt1.google.com` 256 px raster tiles.
  - Migrate both in-app display and off-screen dash rendering to a keyless open vector basemap (e.g., MapLibre Native + OpenFreeMap / PMTiles) with change-detection caching to maintain low CPU/thermal overhead.
- [ ] **Independent Routing & Search Backend:**
  - Currently, `Router` depends on Google Directions API (debug) and Google Routes API (release) with `MAPS_API_KEY`.
  - Support an open, keyless/self-hostable routing & geocoding provider (e.g., Valhalla, GraphHopper, or self-hosted OSRM + Photon/Nominatim).
- [ ] **Persistent Offline Regions & Long-Corridor Packs:**
  - Basic route corridor prefetching (`TileProvider.prefetchRoute` + `RouteViewModel.saveActiveRouteOffline`) is already implemented, but tiles are stored in `cacheDir`, capped at `600` tiles, and marked stale after 5 minutes.
  - Move saved offline route/region packs to persistent app storage without TTL eviction, support long-distance corridors (>600 tiles), and/or package offline `.pmtiles`/`.mbtiles` regions.
- [ ] **Dash & Map Night / High-Contrast Style:**
  - App UI now supports Light/Dark/System themes, but `MapRenderer` and `NavBeamMap` still render a fixed daytime map style.
  - Add a toggle between high-contrast daylight and low-glare dark/OLED styling for both the streamed dash map and in-app map.

---

