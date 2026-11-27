# Copilot Instructions

- when the user asks you to implement a feature, then you should implement the entire feature in one go. Do not report intermediate progress before implementing the entire feature, unless the user specifically requiests this. The user will ask for changes if needed. If feature details are unclear and you need guidance you can ask the user.

## Implementation and documentation

- Before changing code, inspect the relevant implementation, tests and focused documentation. Consult `docs/ARCHITECTURE.md` when a change touches a cross-cutting contract; it is a concise reference, not a required full-project spec.
- Do not maintain a per-fix planning or implementation-status log. Update canonical docs when public behavior, settings, APIs, saved-data formats or protocol contracts change.
- Keep the root README concise and user-facing. Put task instructions in the user guide and technical details in the corresponding reference docs.
- Document user-visible features in the appropriate guide. Keep `docs/SETTINGS.md` current when settings are added or changed.
- New tools in the DM controls must be hideable through Settings and accessible through the local API.

## Remote-control compatibility

- For every feature or fix affecting remote control, assess whether it remains compatible with existing remote clients and hosts.
- `src/main/java/dmmt/remote/RemoteCompatibility.java` defines the shared `VERSION` constant. Increment it when remote messages, commands, workspace snapshots, settings contracts or asset-transfer semantics change incompatibly.
- Keep the version unchanged for backward-compatible fixes and unrelated application changes. Compatibility must not depend on application-content hashes, identical builds, packaging or compiler output.
- Preserve compatibility checks before host approval or workspace replacement, and keep mismatch errors actionable by showing both compatibility versions.
- Cover changed remote behavior and version rejection with relevant tests; update directly related documentation and `docs/ARCHITECTURE.md` only when a durable invariant changes.

## Browser interactions

- After changing web client code or assets, run `npm run build` in `web`, then `mvn process-resources` from the project root before running the Java app: it serves classpath resources from `target\classes`, not `src\main\resources`; restart the host and reload browser pages, and rebuild the package when using a packaged app.
- Never use native browser prompts (`alert`, `confirm`, or `prompt`). Use themed, accessible in-app dialogs or inline messages when user input is necessary.
- Do not add confirmation steps to actions that already have explicit controls or usage limits unless the specification requires one.