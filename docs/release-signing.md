# Release signing

Release APKs on GitHub and F-Droid are signed with the same release key:

| DN | SHA-256 |
| --- | --- |
| `CN=Stillwave, O=acilione` | `9df44930c696e8a57477d25b75b9ddc6e1de672ff19840b840fa8e638d1cfbaa` |

Android only installs an update when it carries the same signature as the installed app. Because F-Droid publishes the developer-signed APK, a GitHub install and an F-Droid install can update each other.

## Reproducible builds

F-Droid builds each release tag from source and checks that the result matches the published APK, ignoring the signature. Then it distributes the published APK. To keep builds byte-identical:

- Build releases from a clean checkout of the tag, not from a working tree with local changes. The APK records the commit it was built from.
- Use JDK 21, the version on F-Droid's build server, and the SDK versions in the [README](../README.md#build).
- The Gradle build turns off Google's encrypted dependency report (`dependenciesInfo`), which would differ between builds.

## Signing a release

Signing material is never committed. It lives in `~/.stillwave/`, or in the directory named by `STILLWAVE_SIGNING_DIR`, and must be backed up off the build machine. Losing the key means no further updates can be published for existing installations.

```sh
git clone --branch vX.Y.Z https://github.com/acilione/stillwave.git && cd stillwave
./gradlew :app:assembleRelease
tools/sign-release.sh app/build/outputs/apk/release/app-release-unsigned.apk stillwave-vX.Y.Z.apk
```

The script zipaligns, signs with APK Signature Scheme v2/v3, and prints the verification result. Do not add the key to Gradle's `signingConfig`.

`--lineage` is only for moving a device that runs a pre-release build signed with the earlier development key: it adds a key-rotation proof so that build updates in place on Android 9 or later. Published APKs never use it.
