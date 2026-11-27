# Architecture and engineering invariants

This is a short reference to durable project structure and cross-cutting contracts, not a feature backlog or change log. For current behavior, prefer the implementation, tests and the focused documentation linked from the root README.

## Project structure

- `src/main/java/dmmt` contains the JavaFX desktop application. Packages separate models, services, rendering, lighting, audio, local API, Online Play and DM Link.
- `web/src` contains the TypeScript browser client for Online Play. It is built into the resources served by the desktop host.
- `dmmap-daggerheart/*.json` contains the editable Daggerheart catalogs. `docs/DAGGERHEART_DATA.md` defines their envelope and automation metadata.
- `docs/SETTINGS.md` is the settings reference; `docs/API.md` documents the local DM control API.

## Contracts to preserve

- Maps use a versioned `.dmmap` JSON format. Reuse the existing model, persistence services and migration paths when changing saved data.
- Multilevel maps have a manifest and per-level map files. Route saves through the application's `SaveTarget` flow so a level save also updates the correct package metadata.
- DM Link and Online Play are distinct protocols. `RemoteCompatibility.VERSION` governs DM Link compatibility; `OnlineProtocol.VERSION` governs browser/host HTTP and WebSocket compatibility. Increment only the affected version for incompatible contract changes, not for compatible fixes or unrelated application changes. Keep mismatch errors actionable and cover rejection behavior with tests.
- The local control API is opt-in and unauthenticated. Document its trusted-network scope; never describe it as safe to expose to the public internet.
- Catalog automation is data-driven. Do not infer executable rules from names or prose; use the documented metadata shapes and host validation. Keep schema guidance in `docs/DAGGERHEART_DATA.md`, not in the user-facing README.
- The launcher sets a default JavaFX texture-pool limit before JavaFX starts, while preserving an explicit user override.

## Browser resources and validation

After changing web code or assets, run `npm run build` in `web`, then `mvn process-resources` from the repository root. The Java app serves the built classpath resources, not the live `web/src` files; restart the app to load the updated bundle.

Use the existing test suites for the changed surface (`mvn test` for Java and `npm --prefix web test` for the browser client). Prefer focused tests for changed behavior and contracts.

## Documentation maintenance

Keep the root README a concise project introduction for prospective users. Put task instructions in the user guide and focused references. Update documentation when public behavior, settings, APIs, saved-data formats or protocol contracts change; do not append per-fix implementation notes or progress logs here.
