# Copilot Instructions

## Planning-first workflow

Before implementing any code change, always open and review `plan.md` in the project root.

- Confirm the requested change aligns with the current living spec.
- If the request introduces new requirements, update `plan.md` first.
- Then implement code changes according to the updated plan.
- afterwards make sure any new feature is documented in the README and existing documentation is updated if needed
- If the any settings are added or changed make sure the settings.md documentation is up to date
- if a new tool on the DM controls is introduced ensure that it can be hidden via the settings and needs to be accessible via the local API

## Remote-control compatibility

- For every feature or fix affecting remote control, assess whether it remains compatible with existing remote clients and hosts.
- `src/main/java/dmmt/remote/RemoteCompatibility.java` defines the shared `VERSION` constant. Increment it when remote messages, commands, workspace snapshots, settings contracts or asset-transfer semantics change incompatibly.
- Keep the version unchanged for backward-compatible fixes and unrelated application changes. Compatibility must not depend on application-content hashes, identical builds, packaging or compiler output.
- Preserve compatibility checks before host approval or workspace replacement, and keep mismatch errors actionable by showing both compatibility versions.
- Cover changed remote behavior and version rejection with relevant tests; update `plan.md` and directly related documentation.

## Browser interactions

- After changing web client code or assets, run `npm run build` in `web`, then `mvn process-resources` from the project root before running the Java app: it serves classpath resources from `target\classes`, not `src\main\resources`; restart the host and reload browser pages, and rebuild the package when using a packaged app.
- Never use native browser prompts (`alert`, `confirm`, or `prompt`). Use themed, accessible in-app dialogs or inline messages when user input is necessary.
- Do not add confirmation steps to actions that already have explicit controls or usage limits unless the specification requires one.