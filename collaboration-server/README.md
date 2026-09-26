# Artify collaboration relay

This service pairs the two longest-waiting available clients, creates a private room, and relays
live stroke previews plus completed PNG patches. It does not retain artwork.

Run locally:

```bash
npm install
npm start
```

For an Android emulator, set this in the Android project's `local.properties`:

```properties
COLLABORATION_SERVER_URL=ws://10.0.2.2:8787
```

For production, deploy the Docker image behind TLS and build with:

```bash
./gradlew assembleRelease -Partify.collabServerUrl=wss://your-relay.example.com
```

The health endpoint is `/health`. The relay keeps rooms only in memory; use sticky sessions or a
single replica unless the room registry is moved to a shared store.
