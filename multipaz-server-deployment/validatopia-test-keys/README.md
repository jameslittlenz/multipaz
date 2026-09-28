# Validatopia TEST keys

The fixed, **test-only** PKI for the Validatopia Photo ID demo. Every deployment of the
`PROFILE=validatopia` container, and both Validatopia apps, share these keys. Trust anchors stay
the same across deployments, so the verifier can ship them bundled rather than fetching them from
each server.

> **These private keys are public.** They are committed to this repository, so anyone can mint a
> Photo ID or synthetic passport that chains to them. That is fine for a demo built on synthetic
> data, and it's why every anchor is labelled TEST. Never use them to vouch for a real identity.
> For anything beyond a demo, generate your own set (below), keep `validatopia-keys.conf` out of
> version control, mount it into the container, and set `VALIDATOPIA_KEYS_CONF` to its path.

| File | Contents | Private? | Used by |
|---|---|---|---|
| `validatopia-keys.conf` | Server config (JSON, merged with `-config`): the IACA as `root_identities.credential_signing`, and the test CSCA/DS as `validatopia_test_csca` | **yes** | issuer (`MainValidatopia`) |
| `validatopia-reader-key.json` | Verifier reader-authentication key: JWK plus `x5c` of reader cert and reader root | **yes** | verifier app |
| `validatopia_iaca.pem` | Validatopia TEST IACA, the root of every Photo ID's issuer chain | no | verifier trust store |
| `validatopia_test_csca.pem` | Validatopia Test CSCA, which signs every persona's synthetic passport | no | verifier CSCA store |
| `validatopia_test_ds.pem` | Validatopia Test Document Signer (under the CSCA; also embedded in each SOD) | no | reference |
| `validatopia_reader_root.pem` | Validatopia TEST Reader Root | no | wallet reader trust |

The apps don't read this directory at runtime. The generator also writes the public anchors and
the reader key as constants into
`samples/validatopia/shared/src/commonMain/kotlin/org/multipaz/samples/validatopia/shared/trust/ValidatopiaTestPki.kt`,
and `ValidatopiaTestPkiTest` fails if the two ever drift apart.

## How the issuer uses them

- **IACA.** `root_identities.credential_signing` pins the IACA. The issuer self-enrolls a
  short-lived Document Signer under it (180 days, stored in its database, renewed automatically).
  Issuer chains therefore rotate, but they always end at this IACA. `start-servers.sh` sets no
  `enrollment_server_url` in the Validatopia profile, so self-enrollment is what happens.
- **Test CSCA/DS.** `ValidatopiaTestCsca.getOrCreate()` reads `validatopia_test_csca`. Without
  it (unit tests, ad-hoc runs), it falls back to its old behaviour: generate a CSCA/DS once and
  persist it.

For a local, non-container run, pass the file explicitly:

```
./gradlew :multipaz-openid4vci-server:run -PmainClass=org.multipaz.openid4vci.server.MainValidatopia \
  --args="-config $PWD/multipaz-server-deployment/validatopia-test-keys/validatopia-keys.conf ..."
```

## Current set

Generated 2026-09-29. All keys are ECDSA P-256.

| Certificate | Subject | Valid until (UTC) | SHA-256 fingerprint |
|---|---|---|---|
| IACA | `CN=Validatopia TEST IACA,O=Validatopia,C=XV` | 2036-09-25 | `31:34:A0:BA:FE:EF:13:B4:E6:9A:92:6E:9D:28:D2:30:5B:08:12:E4:AC:15:DA:05:37:E2:79:55:42:DC:4A:75` |
| Test CSCA | `CN=Validatopia Test CSCA,O=Validatopia,C=XV` | 2041-09-24 | `6B:E7:A3:F0:62:2E:9E:8E:CA:08:5D:62:83:91:DB:50:78:33:5B:C9:51:F6:E6:79:B3:77:1D:4D:37:A5:74:91` |
| Test DS | `CN=Validatopia Test DS,O=Validatopia,C=XV` | 2036-09-25 | `DF:90:28:9C:7B:3C:97:0A:24:EB:D3:49:27:50:13:D7:A0:C3:FB:23:BB:1D:23:C1:06:20:C3:92:14:02:80:11` |
| Reader root | `CN=Validatopia TEST Reader Root,O=Validatopia,C=XV` | 2036-09-25 | `C0:C6:D4:A2:67:7D:F1:09:BD:5D:3A:CF:A7:B8:58:2F:29:99:B9:1B:0F:BC:FD:25:6C:53:92:1C:CA:3D:34:07` |
| Reader (`Validatopia Verify`) | `CN=Validatopia Verify,O=Validatopia,C=XV` | 2029-12-28 | `E4:DF:AA:CF:AB:BA:3A:7E:C0:14:FB:87:DE:F8:F8:90:51:24:5F:F5:A9:44:EE:24:E5:55:B1:13:3D:7C:A2:6C` |

The reader certificate expires first, because ISO/IEC 18013-5 caps reader certificates at
1187 days. Regenerate before 2029-12-28.

## Regenerating

```
./gradlew :multipaz-idv-backend:generateValidatopiaTestKeys
```

This replaces **every** key and rewrites `ValidatopiaTestPki.kt`. Credentials already issued stop
verifying, and wallets must be re-provisioned. Rebuild both apps and the container image, then
update the table above from the fingerprints the task prints.
