# Online Play browser client

This is the browser client served by the Dungeon Master Map Tool desktop app. Players normally open the address provided by the DM; this folder is not a separate hosting service. The client uses TypeScript, Canvas 2D and Vite.

## Development

From this directory:

```sh
npm ci
npm run dev
npm test
npm run build
```

The DM-hosted client is served from the Java application's classpath. After changing browser code or assets, run `npm run build` here and then `mvn process-resources` from the repository root before launching the Java app.

Catalog and automation data are maintained in `../dmmap-daggerheart`; see [the data format reference](../docs/DAGGERHEART_DATA.md).

**Account safety:** accounts created without a password can be claimed by anyone who can reach the session. Set a password before making a session reachable beyond a trusted local network.
