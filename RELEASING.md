# Releasing

Build with JDK 25 and Python 3.11+ with Pillow installed:

```bash
./gradlew build verificationJar
python3 tools/audit_assets.py --check
```

Before publishing, run the full server suite and restart checks in a disposable
world. The command below accepts Mojang's EULA for that test server:

```bash
python3 tools/run_runtime_checks.py --accept-eula --restart \
  --jar build/libs/amplified-items-1.8.0.jar \
  --verification-jar build/libs/ISOLATED-TESTS-ONLY.jar \
  --datapack build/distributions/amplified-items-datapack-1.8.0.zip \
  --resourcepack build/distributions/amplified-items-resources-1.8.0.zip
```

Also check the knight models and combat with a vanilla client using the resource
pack. Update the testing section in `RELEASE_NOTES.md` with the results.

Package the build:

```bash
python3 tools/package_release.py
```

Upload the contents of `build/release/1.8.0/` to a draft GitHub release tagged
`amplified-items-1.8.0`. Check the uploaded filenames and SHA-256 hashes before
publishing. Keep compiled files out of Git, and never upload or install
`ISOLATED-TESTS-ONLY.jar` on a real server.