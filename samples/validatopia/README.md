# Validatopia sample apps

Validatopia Wallet and Validatopia Verify: a Photo ID demo built on Multipaz (see
`docs/validatopia/PLAN.md`). Milestone M4 covers Android and the persona (test identity) path.

| Module | What it is |
|---|---|
| `shared/` | KMP client library: IDV client, use-case presets, reader flow, result analyser, cross-border passport check, bundled TEST trust anchors, branding |
| `wallet-android/` | Validatopia Wallet: get a Photo ID for a test identity, present by QR or NFC tap |
| `verifier-android/` | Validatopia Verify: five use cases, read by QR scan or NFC tap |
| `wallet-ios/`, `verifier-ios/` | Placeholders until M5 |

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
   is at `http://localhost:8000/openid4vci`, which is the wallet's default.

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
identity (`DevWalletBackend`), because emulators can't produce Android key attestation. Release
builds use the attested back-end at `<issuer>/../backend/rpc`.

### Two emulators

Emulators bridge Bluetooth to each other, but can't scan each other's screens or do NFC. Debug
builds of the verifier therefore take the QR payload by intent, which replaces only the camera:

```
adb -s emulator-5556 shell am start -n org.multipaz.samples.validatopia.verifier/.MainActivity \
  --es debug_qr "mdoc:…" --es debug_use_case CROSS_BORDER
```

The payload is the text of the wallet's QR code (decode a screenshot with any QR reader).
`debug_use_case` is one of `VENUE_ENTRY`, `LIQUOR_STORE`, `PARCEL_PICKUP`, `HOTEL_CHECK_IN`,
`CROSS_BORDER`.

## Tests

`./gradlew :samples:validatopia:shared:jvmTest` runs `ValidatopiaRoundTripTest`. It covers
persona issuance through the real issuer, OpenID4VCI redemption, then every use case presented by
the Multipaz presentment code and checked by the verifier's analyser. Only the radio is left out.
