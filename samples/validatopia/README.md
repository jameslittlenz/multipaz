# Validatopia sample apps

Validatopia Wallet and Validatopia Verify: a Photo ID demo built on Multipaz (see
`docs/validatopia/PLAN.md`). Milestones M4 and M5 cover Android and iOS on the persona (test
identity) path.

| Module | What it is |
|---|---|
| `shared/` | KMP client library (Android, JVM, iOS): IDV client, wallet set-up, transport settings, use-case presets, reader flow, result analyser, cross-border passport check, bundled TEST trust anchors, brand colour tokens. On iOS it's the `ValidatopiaShared` framework |
| `wallet-android/` | Validatopia Wallet: get a Photo ID for a test identity, present by QR or NFC tap |
| `verifier-android/` | Validatopia Verify: five use cases, read by QR scan or NFC tap |
| `wallet-ios/` | Validatopia Wallet for iOS (SwiftUI): same flow, presents by QR code only (iOS apps can't present over NFC) |
| `verifier-ios/` | Validatopia Verify for iOS (SwiftUI): five use cases, read by QR scan, or NFC tap where the build has NFC reading |
| `ios-common/` | SwiftUI branding and components shared by both iOS apps |
| `ios-config/` | Xcode build settings shared by both iOS apps, plus the template for your local ones |

Trust anchors (IACA, test CSCA, reader root) are fixed TEST keys shared by every deployment. See
`multipaz-server-deployment/validatopia-test-keys/README.md`.

## Running locally

1. Start the issuer with the fixed keys and the placeholder personas:

   ```
   ./gradlew :multipaz-openid4vci-server:run -PmainClass=org.multipaz.openid4vci.server.MainValidatopia \
     --args="-param base_url=http://localhost:8007 -param database_engine=ephemeral \
     -config $PWD/multipaz-server-deployment/validatopia-test-keys/validatopia-keys.conf \
     -param personas_seed_dir=$PWD/multipaz-server-deployment/docker/init/personas \
     -param admin_bootstrap_pass=dev-only-password"
   ```

   Or run the container with `PROFILE=validatopia` and `BASE_URL=http://localhost:8000`; its issuer
   is at `http://localhost:8000/openid4vci`, which is the wallets' default.

2. Build the wallet pointed at the issuer, and install both apps. `adb reverse` makes the
   phone's `localhost` reach the host (repeat it for each device):

   ```
   ./gradlew :samples:validatopia:wallet-android:assembleDebug -Pvalidatopia.issuerUrl=http://localhost:8007
   ./gradlew :samples:validatopia:verifier-android:assembleDebug
   adb reverse tcp:8007 tcp:8007
   ```

   The issuer URL can also be changed in the wallet's Settings.

3. On the wallet: agree, then **Get a Photo ID** → pick a test identity. On the verifier: pick a
   use case, then **Scan their QR code** or **Tap their phone**.

Debug builds of the wallet sign wallet attestations with the public Multipaz TestApp development
identity (`DevWalletBackend`), because emulators and the iOS Simulator can't produce key
attestation or App Attest. Release builds use the attested back-end at `<issuer>/../backend/rpc`.

### iOS

Open `wallet-ios/ValidatopiaWallet.xcodeproj` or `verifier-ios/ValidatopiaVerify.xcodeproj`, or
build from the command line:

```
xcodebuild -project samples/validatopia/wallet-ios/ValidatopiaWallet.xcodeproj -scheme ValidatopiaWallet -sdk iphonesimulator build
xcodebuild -project samples/validatopia/verifier-ios/ValidatopiaVerify.xcodeproj -scheme ValidatopiaVerify -sdk iphonesimulator build
```

A build phase runs `:samples:validatopia:shared:embedAndSignAppleFrameworkForXcode`, which builds
the `ValidatopiaShared` Kotlin framework. `multipaz-swiftui` is compiled from source, as in
`samples/SwiftTestApp`.

To run on a phone, copy `ios-config/DeveloperConfig.xcconfig.template` to
`ios-config/DeveloperConfig.xcconfig` (gitignored) and fill in your team and a bundle-ID prefix.
A free Personal Team works, except that it can't provision NFC tag reading: the template shows how
to build the verifier without it (QR code only).

The iOS Simulator has no Bluetooth, so presenting and reading only work between devices. The
Simulator still runs issuance, the wallet's screens and the verifier up to engagement.

### Phones on the local network

An iPhone can't use `adb reverse`, so to mix Android and iOS devices, run the issuer with
`base_url` set to this machine's address on the local network (for example
`http://192.168.1.10:8007`), and point every app at that:

- iOS: `VALIDATOPIA_ISSUER_URL` in `ios-config/DeveloperConfig.xcconfig`, or the wallet's Settings.
  The first connection asks for local-network access.
- Android: build with `-Pvalidatopia.issuerUrl=http://192.168.1.10:8007
  -Pvalidatopia.devHost=192.168.1.10`. The `devHost` property allows plain HTTP to that one address
  in debug builds only; without it, only `localhost`, `127.0.0.1` and the emulator host are allowed.

### Handing over a QR code without a camera

Emulators can't see each other's screens, and it's awkward to aim a phone at a phone on a desk.
Debug builds of both verifiers therefore accept the QR payload directly, replacing only the camera;
the rest of the flow, Bluetooth included, is real.

- Android verifier:

  ```
  adb -s emulator-5556 shell am start -n org.multipaz.samples.validatopia.verifier/.MainActivity \
    --es debug_qr "mdoc:…" --es debug_use_case CROSS_BORDER
  ```

- iOS verifier: launch it with `-debugQr "mdoc:…" -debugUseCase CROSS_BORDER`, for example
  `xcrun devicectl device process launch --device <id> <bundle id> -debugQr "mdoc:…"
  -debugUseCase CROSS_BORDER`.

The payload is the text of the wallet's QR code: decode a screenshot with any QR reader, or, for
the iOS wallet, read the `VALIDATOPIA_QR` line its debug build logs. The use case is one of
`VENUE_ENTRY`, `LIQUOR_STORE`, `PARCEL_PICKUP`, `HOTEL_CHECK_IN`, `CROSS_BORDER`.

## Tests

- `./gradlew :samples:validatopia:shared:jvmTest` and
  `./gradlew :samples:validatopia:shared:iosSimulatorArm64Test` run `ValidatopiaRoundTripTest`. It
  covers persona issuance through the real issuer, OpenID4VCI redemption, then every use case
  presented by the Multipaz presentment code and checked by the verifier's analyser. Only the radio
  is left out. On the JVM the issuer runs in-process; for the iOS Simulator, Gradle starts a real
  `MainValidatopia` on localhost and stops it afterwards, and the wallet and verifier code runs
  natively.
- The iOS apps' UI tests (`xcodebuild test -scheme ValidatopiaWallet` / `ValidatopiaVerify` with an
  iOS Simulator destination) run accessibility audits (`performAccessibilityAudit`) on each screen,
  including at the largest accessibility text size. The wallet's test gets a Photo ID from a running
  issuer (the app's configured one, or `TEST_RUNNER_VALIDATOPIA_ISSUER_URL`).
