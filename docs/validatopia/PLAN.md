# Validatopia: Photo ID digital-credential demo suite

## Context

This is an end-to-end demonstration of digital credentials, branded for **Validatopia**, a fictional country. It has five parts:
- **Wallet (Android + iOS).** The user scans their e-passport over NFC and completes a liveness check and a server-side face match. They then receive an ISO/IEC 23220-4 **Photo ID** mdoc, along with three companion documents issued from the same identity proofing: a **Driver Licence** (ISO 18013-5 mDL), a **Gym Membership** (a multipaz-utopia loyalty card) and an **Age Verification** (`age_over_18` and `age_over_21` only). The wallet can also issue these from dummy test identities.
- **Issuer backend.** It signs with software keys, runs in a single Docker container and is **publicly reachable**, so it is hardened against misuse.
- **Admin website.** Admins can view issued credentials, revoke them and change settings such as face-match thresholds.
- **Verifier (Android + iOS).** It reads credentials in person (ISO 18013-5) and offers several selective-disclosure use cases, including **cross-border travel**.
- **Automated test suite.** It covers all of the above.

**Decisions:**
- Face match runs server-side. Liveness is checked on the device.
- Passport chips are read with JMRTD on Android and NFCPassportReader on iOS (the iOS counterpart of JMRTD, MIT-licensed).
- There will be new, focused sample apps. `samples/testapp` stays untouched and serves as a reference.
- UI is native on each platform: Jetpack Compose with Material 3 on Android, SwiftUI following Apple's HIG on iOS. All UI must meet WCAG 2.2 AA.
- DG11 is not read. Identity claims come from DG1 (the MRZ stored on the chip) and the portrait comes from DG2.

### DG1, DG2 and SOD in the credential
`PhotoID.kt` implements ISO/IEC 23220-4 Table C.3 (the data elements defined by ICAO 9303 part 10). The relevant elements are in namespace `org.iso.23220.datagroups.1` (`PhotoID.kt:455-638`):
- `version`
- `sod`, `dg1`, `dg2` … `dg16`, all byte strings and **all optional** (`mandatory = false`).

So including them is allowed but not required. **The plan includes `version`, `sod`, `dg1` and `dg2` in every Photo ID**, containing the exact bytes read from the chip. For dummy identities they are synthetic bytes, signed by a test CSCA.

A verifier can then independently re-check the passport data against the issuing country's CSCA. That is the basis of the cross-border use case.

This reflects multipaz's encoding of the standard and should be cross-checked against the ISO text.

### Design trade-offs
- **`dg1` cannot be disclosed selectively.** Requesting `dg1` reveals the whole MRZ: name, date of birth, sex, nationality, document number and expiry. That fits a border check and nothing else. The verifier UI makes this explicit, showing "DG1 reveals: …".
- **Keeping DG2 on the server.** If DG2 and SOD are in the credential, the server must keep them to re-issue credentials (refresh). The plan encrypts them at rest, with a retention period the admin can set (default 30 days). They are deleted on revocation. The selfie is still discarded immediately. The alternative is no refresh: the user verifies again once their credentials run out.
- **iOS cannot present over NFC.** multipaz has not implemented that. The iOS wallet presents by QR code plus BLE; the iOS verifier can still read over NFC.
- **Liveness checked on the device can be spoofed.** Device attestation on a public server raises the bar but does not remove the risk. The UI says so.

### Target passports: New Zealand (NZL) and Australia (AUS)
- **Trust:** the default CSCA store in both the server and the verifier contains the **NZL and AUS CSCA certificates**, plus the Validatopia Test CSCA.
  - Sources:
    - The **ICAO Master List** (https://www.icao.int/icao-pkd/icao-master-list), plus PKD document signer certificates and CRLs (https://download.pkd.icao.int/). This is the primary source for both countries.
    - **AU direct:** the Australian Passport Office page (https://www.passports.gov.au/help/australian-country-signing-certificate-authority-csca), which lists the DER files and the CRL. The subject is `CN=Passport Country Signing Authority, OU=APO, OU=DFAT, O=GOV, C=AU`.
    - **NZ:** the ICAO Master List, cross-checked against the BSI German Master List. Ask DIA if a third confirmation is needed.
  - Verify each master list's signer before importing it. Keep link certificates too.
  - Cross-check fingerprints between at least two sources.
  - Record the fingerprints and expiry dates.
  - Other countries are accepted only in demo mode, flagged `UNTRUSTED_CSCA`, or once an admin uploads their CSCA.
- **M1 discovery task, before building crypto:** read real NZ and AU passports and inspect their CSCA certificates. Record:
  - the SOD signature algorithm (RSA, PSS or ECDSA, and the curve)
  - the hash algorithm
  - whether they use PACE or BAC
  - whether DG14 (Chip Authentication) and DG15 (Active Authentication) are present
  - whether DG2 is JPEG or JPEG2000
  - MRZ name formatting

  This goes into `docs/validatopia/passport-profiles.md`.
- **Priority:** core crypto fixes that NZ and AU need are done in M1. Anything they don't need is deferred, e.g. brainpool on iOS if neither uses brainpool.
- **Test fixtures:** `SyntheticPassportFactory` can generate `NZL` and `AUS` profiles that mirror the observed algorithms and data-group layout. Tests then cover the real configurations without committing real passport data. Never commit real passport dumps.
- `idv_require_active_auth` stays configurable, defaulting to off until AA support is confirmed for both countries.
- Country mapping `NZL→NZ` and `AUS→AU` is covered by tests, as is nationality display in both apps.

## What we reuse (verified)

**Doctype**
- `multipaz-doctypes/.../knowntypes/PhotoID.kt`.
- Formats to watch:
  - `birth_date` is a CBOR map.
  - Country fields use ISO alpha-2 codes. The MRZ uses alpha-3, so a mapping table is needed.

**Issuer (`multipaz-openid4vci`)**
- `CredentialFactory` and `CredentialFactoryRegistry`. `CredentialFactoryMdocPid.kt` is the template for minting.
- `util/preauthorized.kt: generatePreauthorizedOffer()`.
- `request/credential.kt:391 readSystemOfRecord()` is the only place identity data enters minting.
- `util/IssuanceState.kt`.
- The `admin*` endpoints and the plain HTML/JS admin UI in `src/main/resources/resources/www/` (`admin.html`, `session.html`, `login.js`).
- `util/auth.kt`: checks client attestation and client assertions.

**Software keys**
- `multipaz-server/.../ServerEnvironment.kt` uses `SoftwareSecureArea`.
- The IACA is served at `GET /ca/credential_signing`.
- The `StorageTable` pattern via `BackendEnvironment.getTable(...)` is used for settings, audit and personas.

**Device authentication**
- `multipaz-backend-server`: `OpenID4VCIBackendImpl` and `ClientRegistrationImpl`.
- `client_requirements` in its `default_configuration.json` checks Android key attestation (package name, signature digests, verified boot) and iOS App Attest (app identifiers).
- The wallet side uses `RpcAuthorizedDeviceClient`.

**Deployment**
- `multipaz-server-deployment/docker/`: `Dockerfile`, `start-servers.sh`, `nginx.conf` and `services/*.conf`.
- Build with `./gradlew :multipaz-server-deployment:buildDockerImage`.

**Android UI (multipaz-compose)**
- `camera/Camera`, `qrcode/QrCodeScanner`, `presentment/MdocProximityQrPresentment`, `mdoc/MdocNfcV2Service` + `MdocNdefService`, `prompt/PresentmentActivity`, `cards/VerticalCardList`, `provisioning/ProvisioningBottomSheet`.

**iOS UI (multipaz-swiftui)**
- `MdocProximityQrPresentment`, `ConsentPromptDialog`, `VerticalCardList`/`CardCarousel`, `ProvisioningView`, `X509CertViewer`, `SmartSheet`.
- The framework integration follows `samples/SwiftTestApp`: the `XCFramework("Multipaz")` Gradle module, built by the Xcode phase `embedAndSignAppleFrameworkForXcode`, with multipaz-swiftui compiled from source.
- Minimum iOS version is 26.0.

**Core library**
- `NfcTagReader` works on Android and iOS; the iOS version is CoreNFC ISO7816, with AID A0000002471001 listed in Info.plist.
- `scanMdocReader`, `MdocTransportFactory` (BLE on both platforms; NFC reader on iOS).
- `SecureEnclaveSecureArea`, `AndroidKeystoreSecureArea`, `ProvisioningModel`.
- `VerificationUtil`, `TrustManager`, `ASN1`, `X509Cert`/`X509CertChain`.

**Tests**
- `ProvisioningClientTest.kt`: pre-authorized issuance against an in-process server.
- `Iso18013PresentmentTest.kt`: loopback proximity presentment.

## Architecture

```
Wallet  device attestation → backend-server (ClientRegistration) → wallet attestation JWT
        MRZ OCR (ML Kit | Vision) → chip read (JMRTD | NFCPassportReader): COM,SOD,DG1,DG2,[DG14,DG15]
        verify OCR-MRZ == DG1-MRZ (reject on mismatch) → liveness → selfie
        POST /idv/start  (client attestation + PoP)   → {session, nonce}
        Active Auth over nonce (if DG15)
        POST /idv/evidence (client attestation + PoP, CBOR ≤2 MB)
Issuer  passive auth (shared multipaz-idv code) → AA verify → expiry → face match (ONNX)
        → SoR data (DG1 claims + DG2 portrait + raw sod/dg1/dg2, AES-GCM at rest)
        → IssuanceState.systemOfRecordData → generatePreauthorizedOffer (bound, 5 min),
          one offer per companion document too: {"offer": photoId, "offers": [photoId, mDL, gym, age]}
        → discard selfie + embeddings → audit record
Wallet  ProvisioningModel(offers[0]) → /token /nonce /credential (key attestation required)
        → CredentialFactoryPhotoId.mint() → Photo ID mdoc (Keystore | Secure Enclave)
        → once issued, ValidatopiaIssuance redeems offers[1..] on a second, headless
          ProvisioningModel → Driver Licence, Gym Membership, Age Verification mdocs
Dummy   GET /idv/personas → POST /idv/persona {id} (same auth, admin-toggled) → same offer path
Verifier QR | NFC engagement → DeviceRequest(use case) → verify issuer (Validatopia IACA)
        → cross-border: passive auth of sod/dg1/dg2 against CSCA list + DG1↔claims check
```

**Checking the MRZ against the chip (replaces DG11):**
- The wallet compares the OCR'd MRZ with the MRZ in DG1.
- The server confirms that DG1 and DG2 are hashed by the same SOD.
- All name, date of birth and sex claims come from DG1. The portrait comes from DG2.
- Known limitation: MRZ names are truncated and transliterated.

## Components

### A. `multipaz-idv`: new KMP library (jvm, android, iosArm64/iosSimulatorArm64/iosX64; detekt-enabled)
One implementation shared by the server and all four apps.
- `mrz/Mrz.kt`: TD3/TD1 parsing, check digits, century of the birth year, `<<` handling.
- `lds/Lds.kt`: TLV parsing; DG1 → MRZ; DG2 face image (ISO 19794-5 and 39794-5).
- `cms/SignedData.kt`, `lds/LdsSecurityObject.kt`: a CMS parser built on `org.multipaz.asn1.ASN1`. Signed attributes are hashed over the raw `0x31 || len || content` bytes.
- `pa/PassiveAuthenticator.kt`: SOD signature → DS→CSCA chain (`X509CertChain.validate`) → comparing DG hashes. Returns a result with flags such as `UNTRUSTED_CSCA`, `ALGORITHM_UNSUPPORTED_ON_DEVICE` and `HASH_MISMATCH`.
- `pa/CscaStore.kt`: loads PEM files, or an ICAO master list (also a CMS).
- `aa/ActiveAuthVerifier.kt`: ISO 9796-2 for RSA, and ECDSA.
- `IcaoCountryCodes.kt`: alpha-3 → alpha-2.
- `PassportEvidence`, `IdvResult` (`@CborSerializable`).
- `synthetic/SyntheticPassportFactory.kt`: creates a test CSCA and DS, and DG1/DG2/SOD/DG15 using multipaz crypto. It serves both the tests and dummy personas.

### B. Core `multipaz` crypto fixes (small, upstreamable, each with tests)
1. Support RSASSA-PSS in `crypto/X509Signed.kt` (OID and parameter parsing) and in the iOS `SwiftBridge.verifySignature` OID map.
2. The iOS `SwiftBridge.ecVerifySignature` must honour the algorithm's hash rather than implying it from the curve, and replace `try!` with thrown errors.
3. Brainpool ECDSA **verification** on iOS: a pure-Kotlin verify-only fallback in iosMain, or a bignum library. If this slips, passive auth reports `ALGORITHM_UNSUPPORTED_ON_DEVICE` for those passports on iOS instead of failing them.
4. Optional: BER indefinite-length and 3-byte length support in `ASN1.decodeLength`; SHA-224.

On Android and the JVM, BouncyCastle is registered as provider #1 so brainpool works.

### C. `multipaz-idv-backend`: new JVM module
- `face/FaceMatcher`:
  - `OnnxFaceMatcher`: YuNet (MIT) detection and alignment, SFace (Apache-2.0) embedding, cosine similarity.
  - `FakeFaceMatcher`, for tests.
  - Gradle task `downloadFaceModels`, pinned by SHA-256. The models are never committed.
- `image/Jp2Decoder`: converts DG2 JPEG2000 to JPEG for `portrait`.
- `PassportIdentityProofing` implements the new interface `org.multipaz.openid4vci.idv.IdentityProofing`, so heavy dependencies stay out of the issuer.
- **Personas (dummy data supplied separately):**
  - `PersonaStore` holds a `personas.json` (see below) plus portrait JPEGs. It is loaded from `/app/data/personas/` or uploaded through the admin site.
  - Each persona gets a synthetic passport signed by the **Validatopia Test CSCA**, so dummy credentials also carry `sod`/`dg1`/`dg2`, and the cross-border case works with them.
  - Dummy credentials set `issuing_authority = "Validatopia Test Issuance"`.
- **`IdvSettings`:** a `StorageTable` of runtime-editable settings. They default to the values in the config file and can be edited in the admin site:
  - face threshold
  - require Active Authentication
  - accept an untrusted CSCA (demo mode)
  - offer TTL
  - Photo ID validity
  - how long passport data is kept
  - dummy issuance on or off
  - rate limits
- **`IssuanceAudit`:** a `StorageTable` recording the time, method (passport or persona), nationality, masked document number, face score, flags, session id and credential count. It never contains the selfie.
- **`AdminAuditLog`:** every admin login, setting change, revocation and portrait reveal.

**`personas.json` schema** (placeholder file shipped; replaced with real test data later):
```json
[{ "id": "p1", "given_name": "", "family_name": "", "birth_date": "YYYY-MM-DD",
   "sex": 1|2|0, "nationality": "XVA", "document_number": "", "expiry_date": "YYYY-MM-DD",
   "portrait": "p1.jpg", "place_of_birth": "", "resident_address": "" }]
```

### D. Changes to `multipaz-openid4vci` and `multipaz-openid4vci-server`
**`credential/CredentialFactoryPhotoId.kt`**
- Settings: `configurationId="photo_id_mdoc"`, `scope="photo_id"`, `cose_key` binding.
- **Key attestation is required.** On Android, key attestation is checked against a package and signature allow-list. iOS keys come from the backend's attestation.
- Minting follows `CredentialFactoryMdocPid`, with no sample or portrait fallbacks.
- Claims:
  - `age_over_NN` for each threshold, plus `age_in_years` and `age_birth_year`.
  - `travel_document_*`, `nationality` and `sex` from DG1.
  - The Photo ID's own fields come from the Validatopia issuer: `document_number`, `issue_date`, `issuing_authority`, and `issuing_country="XV"`.
  - `expiry_date = min(passport expiry, now + validity)`.
  - The `datagroups.1` namespace carries `version`, `sod`, `dg1` and `dg2`.
- Card art in Validatopia branding (`credential_photo_id`).

**Companion documents** (`credential/CredentialFactoryValidatopia*.kt`)
- Issued from the same identity proofing as the Photo ID, with the same validity period, key attestation requirement and no sample or portrait fallbacks. `ValidatopiaCredentials.createFactories()` lists all four factories, Photo ID first; each sets `offeredAfterIdentityProofing`, and `createIdvOffers()` makes one pre-authorized offer per factory (wallets redeem only the first `credential_configuration_ids` entry of an offer).
- **Driver Licence** (`validatopia_mdl`, `org.iso.18013.5.1.mDL`): name, birth date, portrait, sex, nationality, `age_over_18`/`age_over_21`, `age_in_years`, `age_birth_year`, `issuing_country` and `un_distinguishing_sign` `"XV"`, a `VDL…` licence number and one class `B` driving privilege.
- **Gym Membership** (`validatopia_gym_membership`, `org.multipaz.loyalty.1`): name, portrait, an 8-digit membership number, tier `basic`, issue and expiry dates.
- **Age Verification** (`validatopia_age_verification`, `eu.europa.ec.av.1`): `age_over_18` and `age_over_21` only.
- Identity proofing adds `driving_licence` (licence number, vehicle category) and `gym_membership` (membership number, tier) to the system-of-record data. Titles come from `credential_validatopia_mdl`, `credential_validatopia_gym_membership` and `credential_validatopia_age_verification`.

**Hook in `IssuanceState` and `readSystemOfRecord()`**
- Add `systemOfRecordData: ByteString?` to `IssuanceState`, stored AES-GCM-encrypted with a server key held in the software secure area.
- Purged by a retention job, and on revocation.

**Endpoints:** `request/idvStart.kt`, `idvEvidence.kt`, `idvPersonas.kt` and `idvPersona.kt`.
- All of them require a **wallet client attestation plus PoP**, reusing the `util/auth.kt` checks, rate limits (Ktor `RateLimit`), and a body-size limit.
- They return 404 when IDV is disabled.

**`Main.kt` in the Validatopia profile**
- Registers **only** the Validatopia factories (`ValidatopiaCredentials.createFactories()`: Photo ID and its companion documents). The demo Utopia, PID and mDL factories are left out, since several of them don't require key attestation or fall back to sample data.

**Admin API:** replace `adminCookie.kt`; extend the list, session and status endpoints; add settings, personas, CSCA, audit and portrait-reveal endpoints.

### E. Public-server security
- **Wallets:** only attested genuine app instances can use `/idv/*` or redeem offers.
  - The attestation path is App Attest or Android key attestation → `ClientRegistration` in backend-server → a wallet-attestation JWT that the issuer trusts through `trusted_client_attestations`.
  - The public demo keys in `OpenID4VCILocalBackend` are **not** used.
  - Offers are single-use, expire after 5 minutes, and are bound to the attested client that started the session.
- **`/preauthorized_offer`:** denied at nginx and restricted in code to localhost with a shared secret.
- **Attack surface:** the records, CSA and verifier servers are not started in this profile. The records server has a demo "pick a person" login.
- **Admin:**
  - Accounts are held in a table, with Argon2id or PBKDF2 password hashes and **mandatory TOTP**.
  - Sessions are server-side and revocable. Cookies are `HttpOnly`, `Secure` and `SameSite=Strict`, with CSRF tokens.
  - Failed logins lock the account out with increasing delays.
  - Optional IP allow-list (`ADMIN_ALLOW_CIDR`).
  - The first admin is created from `ADMIN_BOOTSTRAP_*` environment variables. **The container refuses to start with an empty admin password when `BASE_URL` is not localhost** (fix `start-servers.sh`).
- **nginx:**
  - `limit_req` on `/idv/*`, `/admin_*`, `/token` and `/credential`.
  - Security headers: HSTS, CSP, `X-Frame-Options`, `Referrer-Policy`.
  - Body-size limits.
  - TLS terminates at nginx when certificates are mounted, otherwise at the platform proxy. `BASE_URL` must be https.
- **Privacy:** no PII in logs (only an HMAC of the document number). Portraits are hidden in the admin site unless an admin clicks to reveal one, and each reveal is audited.

### F. Admin website
Extends the existing plain HTML/JS in `multipaz-openid4vci/src/main/resources/resources/www/` (no build step). It uses Validatopia branding and meets WCAG 2.2 AA.
- **Login:** password, then TOTP.
- **Dashboard:** counts by method, pass/fail rate, recent flags.
- **Issued credentials:**
  - Filter by date, method and status.
  - Rows show name, masked document number, nationality, face score, flags, credential count and status.
  - Revoke via the existing `admin_set_credential_status`.
  - Delete retained passport data.
- **Settings:** the `IdvSettings` fields, with validation and descriptions of their effects. The face threshold shows guidance on false accepts and false rejects.
- **Trust:** upload CSCA PEMs or an ICAO master list; list them with expiry dates. Download the Validatopia IACA and the test CSCA.
- **Personas:** upload or replace `personas.json` and portraits, preview them, and switch dummy issuance on or off.
- **Audit log:** view it and export it as CSV.
- **Admin accounts:** add or remove admins and reset TOTP.

### G. Single Docker container
- Add a **Validatopia profile** to `multipaz-server-deployment`: `PROFILE=validatopia` runs openid4vci, backend and nginx only.
- The image contains:
  - the admin site
  - the ONNX models, fetched at image build time by `downloadFaceModels` and checked against their SHA-256
  - the Validatopia test CSCA
  - placeholder personas
- Volumes: `/app/data` holds the sqlite databases, personas, CSCA uploads and the encryption-key storage; `/app/logs` holds logs. The IACA and the Validatopia keys persist in `/app/data`, so they survive restarts.
- Environment variables: `BASE_URL`, `ADMIN_BOOTSTRAP_USER/PASS`, `IDV_DEMO_MODE`, `ADMIN_ALLOW_CIDR` and `TLS_CERT/TLS_KEY` (optional).
- nginx serves the wallet API (issuer, IDV, backend, `.well-known`) on port **6000** and the admin site and `/admin_*` API on port **6001**, each port refusing the other's paths; with TLS_CERT/TLS_KEY, 8443 and 8444 respectively.
- `docker run -p 6000:6000 -p 6001:6001 -v vdata:/app/data -e PROFILE=validatopia -e BASE_URL=https://… -e ADMIN_BOOTSTRAP_PASS=… multipaz/server-bundle:latest`

### H. Apps
**Layout**

`samples/validatopia/`:
- `shared/`: a KMP module for android and ios, exported as the `ValidatopiaShared` framework. It contains the IDV API client, evidence assembly, use-case presets, a result analyser that computes "shared / not shared / DG1 reveals", CSCA store loading and branding tokens.
- `wallet-android/`, `verifier-android/`: Compose and Material 3.
- `wallet-ios/`, `verifier-ios/`: Xcode projects using SwiftUI, in the style of SwiftTestApp.

**Branding and design**
- **Validatopia design tokens:** a primary colour and tonal palette, typography scale, logo and credential card art. Colour pairs are checked for contrast (4.5:1 for text, 3:1 for UI).
- **Card art** is drawn on the device from `ValidatopiaCardArt`, in the Photo ID's design (a dark background, three see-through rolling hills, "Validatopia" under the title, a "Powered by" credit) with a colour pairing per document type: Photo ID navy with green hills, Driver Licence teal with navy, Gym Membership navy with teal, Age Verification teal with green. The title always names the type, so colour is never the only cue. The card shows the holder's shortened name ("Claudia H.") when the document carries a name, a deliberate departure from the DISTF flash pass guidance; the Age Verification card shows none.
- **Android:** Material 3 components; dynamic colour is off to keep the branding.
- **iOS:** NavigationStack, SF Symbols, system materials.

**WCAG 2.2 AA on both platforms**
- TalkBack and VoiceOver labels and traits.
- Dynamic Type and font scaling up to 200% without clipping; reflow; both orientations.
- Touch targets of at least 48dp / 44pt.
- Status is never shown by colour alone: trust badges have an icon and text.
- No hard timeouts. NFC and liveness steps can be retried and extended (2.2.1).
- Nothing already entered is asked for again (3.3.7).
- **Liveness is accessible** (2.5.x, 3.3.8): the user can choose the challenge type (blink, or turn the head), gets spoken or haptic cues, and there is no drag-only interaction.
- Reduce-motion is respected.

**Wallet ("Validatopia Wallet")**
1. Welcome and consent (biometric processing, retention, server-side face match).
2. Choose "Verify with passport" or "Use a test identity". The test option is hidden when the server has dummy issuance off.
3. MRZ scan (ML Kit or Vision `VNRecognizeTextRequest`, with the check digits validated in shared code), with manual entry and a CAN fallback.
4. NFC chip read with progress:
   - Android: JMRTD over a `NfcTagReader` CardService adapter, trying PACE and then BAC.
   - iOS: NFCPassportReader, with the NFC entitlement and the AID A0000002471001 in Info.plist.
   - Then the OCR↔DG1 check.
5. Liveness and selfie (ML Kit face detection or Vision face landmarks and yaw).
6. Submit, then show the result and flags.
7. Provisioning (`ProvisioningBottomSheet` or `ProvisioningView`), via the attested backend.
8. Credential list and detail.
9. Present:
   - Android: QR plus NFC tap.
   - iOS: QR plus BLE.
   - Both use the consent sheet, which shows the verifier's name when a reader certificate is present.

**Verifier ("Validatopia Verify")**
- Engagement by QR scan or NFC tap (reader role, both platforms).
- The IACA and CSCA trust stores come bundled (the Validatopia IACA and the Validatopia Test CSCA, both marked TEST), and ICAO master lists can be imported.

| # | Use case | Requested (retain?) |
|---|---|---|
| 1 | Venue entry | `age_over_18` |
| 2 | Liquor store | `age_over_18`, `portrait` |
| 3 | Parcel pickup | `given_name`, `family_name`, `portrait` |
| 4 | Hotel check-in / KYC | mandatory set + `nationality`, `document_number` (retain) |
| 5 | **Cross-border travel** | `portrait`, `family_name`, `given_name`, `birth_date`, `sex`, `nationality`, `travel_document_type`, `travel_document_number`, `expiry_date` + `datagroups.1`: `version`, `sod`, `dg1`, `dg2` (retain). The verifier checks the mdoc against the Validatopia IACA **and** passive-authenticates the SOD against the CSCA store. It checks that the DG1 MRZ matches the claims, and shows the DG2 face next to the mdoc portrait. Two trust panels: "Credential issuer" and "Passport issuer (CSCA)". |
| 6 | ZKP age proof (stretch) | `age_over_18` via Longfellow |

- **Result screen:**
  - Trust badges (icon plus text): trusted / test / unknown issuer, signature or digest status, MSO validity, revocation.
  - A **"Not shared"** panel lists every Photo ID element that was not returned, greyed out.
  - A **"DG1 reveals"** note appears when `dg1` was shared.

## Automated tests
**`multipaz-idv` (commonTest, run on JVM and iOS simulator)**
- MRZ check digits using ICAO specimens; country mapping; TLV.
- CMS/SOD parsing and passive auth using `SyntheticPassportFactory`:
  - valid
  - tampered DG1 or DG2
  - wrong DS
  - expired DS
  - unknown CSCA
  - RSA, PSS and ECDSA P-256/384, plus brainpool on the JVM
- Master-list parsing.
- Active Authentication.

**Core crypto**
- PSS certificates, ECDSA with a mismatched hash on iOS, brainpool verify.

**`multipaz-idv-backend`**
- Face-match policy with `FakeFaceMatcher`, and an ONNX smoke test (`assumeTrue` when models are present).
- JP2 → JPEG.
- Persona loading and validation.
- Settings fallback.
- Retention purge.

**`multipaz-openid4vci`**
- `CredentialFactoryPhotoIdTest`: every element is in the doctype, mandatory elements are present, `birth_date` is a map, the datagroups verify.
- Ktor `testApplication` security tests:
  - `/idv/*` rejects callers without attestation or with a bad PoP.
  - Rate limit, size limit.
  - Personas return 404 when disabled.
  - `/preauthorized_offer` is forbidden from outside.
  - Admin: wrong password or TOTP, lockout, CSRF missing, cookie flags, and a non-admin cannot reach any admin route.

**End-to-end: `PhotoIdEndToEndTest.kt`**
- Harness based on `ProvisioningClientTest`.
- Evidence and persona paths → offers → OpenID4VCI → `DocumentStore`, for the Photo ID and each companion document.
- Loopback presentment for each use case, 1–5, asserting that the disclosed elements are **exactly** the requested ones.
- The cross-border case also passes passive auth on the verifier side.

**UI accessibility**
- Android: Compose UI tests with `enableAccessibilityChecks()`.
- iOS: XCUITest `performAccessibilityAudit()` on the main screens.
- Admin site: Playwright + axe-core.

**Container smoke test**
- Build the image, run it with `BASE_URL=http://localhost:6000`, then run a script that checks health, IACA fetch, that each port serves only its own half, admin login (on port 6001) and the persona-issuance flow.

## Milestones (each ends demoable)
- **M0.** Scaffolding: modules, `settings.gradle.kts`, catalog entries, `detektModules`, empty apps on both platforms, Validatopia design tokens.
- **M1.** `multipaz-idv` plus the core crypto fixes; common tests pass on the JVM and the iOS simulator.
- **M2.** Server: Photo ID factory with datagroups, encrypted SoR hook, `/idv/*` plus personas, attestation-gated auth, settings and audit tables. The E2E test passes with `FakeFaceMatcher`.
- **M3.** Admin website, hardened single-container profile, nginx rules. **Safe to deploy publicly.**
- **M4.** Android wallet and verifier on the persona path, with all use cases including cross-border. The full selective-disclosure demo works on Android.
- **M5.** iOS wallet and verifier on the persona path; mixed Android/iOS presentment.
- **M6.** Real NZ and AU passports on both platforms: OCR, chip read, AA where supported, liveness, ONNX face match, threshold calibration.
- **M7.** Accessibility audit and fixes, ZKP use case, reader certificates for each use case, README and demo script.

## Verification
```
./gradlew :multipaz:jvmTest :multipaz-idv:jvmTest :multipaz-idv-backend:test :multipaz-openid4vci:test detekt
./gradlew :multipaz-idv:iosSimulatorArm64Test                       # macOS only
./gradlew :samples:validatopia:wallet-android:assembleDebug :samples:validatopia:verifier-android:assembleDebug
xcodebuild -project samples/validatopia/wallet-ios/ValidatopiaWallet.xcodeproj -scheme ValidatopiaWallet -sdk iphonesimulator build
xcodebuild -project samples/validatopia/verifier-ios/ValidatopiaVerify.xcodeproj -scheme ValidatopiaVerify -sdk iphonesimulator build
./gradlew :multipaz-server-deployment:buildDockerImage && ./multipaz-server-deployment/validatopia-smoke.sh
```

The iOS builds and tests need macOS with Xcode. This Linux environment can't compile or run them, so they need a Mac or macOS CI.

**Manual demo script**
1. Deploy the container.
2. Create an admin and set up TOTP.
3. Upload the personas.
4. Fetch the IACA in the verifier.
5. On the wallet, issue a Photo ID (and its companion Driver Licence, Gym Membership and Age Verification) through the passport path and through the persona path, on both platforms.
6. Run use cases 1–5 across each combination: Android↔Android, iOS↔iOS and mixed.
7. Revoke a credential in the admin site and show the verifier flags it.

## Getting started in a development environment
- **Branch:** `claude/upbeat-rubin-hap160` on `jameslittlenz/multipaz`. It is upstream `main` plus this plan (`docs/validatopia/PLAN.md`). No code has been written yet.
- **Needed for M0–M4 and the server:**
  - JDK 17 or later (21 works).
  - Android SDK with `ANDROID_HOME` set, or `local.properties` with `sdk.dir`.
  - Network access to `dl.google.com` and Maven Central.
  - Docker or podman, for the container milestone.
  - Two NFC-capable Android phones, plus NZ or AU passports for M6.
- **Needed for M5 and the iOS half of M6:** macOS with Xcode, an Apple developer team (for the NFC and App Attest entitlements), and physical iPhones.
- **Check the environment first:** `./gradlew :multipaz:jvmTest` must pass before any changes. That proves the toolchain works.
- **Suggested first prompt for Claude Code:** "Read `docs/validatopia/PLAN.md` and `CLAUDE.md`, then implement milestone M0 and then M1. Run the listed Gradle tests after each and stop at the end of each milestone for review."
- **Before M1's crypto work:** decide the Validatopia country codes, and have NZ and AU CSCA certificates available.

## Deployed issuance server
The live Validatopia issuer runs the container's `PROFILE=validatopia` on a Linux server behind Caddy. Passwords are never kept in the repo.

**Access and layout**
- **Host:** `ssh aws-server`, an alias in the developer's `~/.ssh/config`. It runs Ubuntu 24.04 on x86_64, with passwordless `sudo`, at public IP `52.64.118.51`. The server has about 2 GB of RAM, and the container uses roughly 220 MB.
- **Public URLs** (DNS A records at Cloudflare, pointing at `52.64.118.51`):
  - Wallet API: `https://validatopia-server.linodigital.co.nz`. The issuer is at `…/openid4vci`, and that is the wallets' issuer URL, ending in `/openid4vci`.
  - Admin site: `https://validatopia-admin.linodigital.co.nz`.
- **Caddy** (`/etc/caddy/Caddyfile`, systemd unit `caddy`) terminates TLS with Let's Encrypt and proxies `validatopia-server` → `localhost:6000` and `validatopia-admin` → `localhost:6001`. The same Caddy also serves other sites, so edit only the two Validatopia blocks. Back up the file first, then run `sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile` and `sudo systemctl reload caddy`.
- **Docker CE** with the compose plugin. `~/validatopia-server/docker-compose.yml` runs the image `multipaz/server-bundle:latest-amd64` as the container `validatopia-server`:
  - `PROFILE=validatopia` and `BASE_URL=https://validatopia-server.linodigital.co.nz`.
  - Ports `127.0.0.1:6000` (wallet API) and `127.0.0.1:6001` (admin site), reachable only through Caddy.
  - `restart: unless-stopped`.
- **`~/validatopia-server/.env`** (mode 600) holds `ADMIN_BOOTSTRAP_PASS`. It is only used to create the `admin` account when none exists.
- **Volumes:**
  - `validatopia-server_validatopia-data` is `/app/data`: the issuer's `openid4vci.db` and the backend's `backend.db` (SQLite; records are CBOR blobs), and the personas. It survives restarts and image updates.
  - `validatopia-server_validatopia-logs` is `/app/logs`.

**Deploy an update** (from the repo, on a Mac)
```
./gradlew :multipaz-server-deployment:buildDockerImageAmd64
docker save multipaz/server-bundle:latest-amd64 | gzip -1 | ssh aws-server 'gunzip | sudo docker load'
ssh aws-server 'cd ~/validatopia-server && sudo docker compose up -d && sudo docker image prune -f'
```
Run `./multipaz-server-deployment/validatopia-smoke.sh` against the native image first. On macOS, AirPlay takes port 5000 and other ports may be taken too, so pass `WALLET_PORT`/`ADMIN_PORT` if needed. Afterwards, check `https://validatopia-server.linodigital.co.nz/.well-known/openid-credential-issuer/openid4vci`, which should return 200 and list all four credential configurations.

**Operate**
- **Status:** `ssh aws-server 'cd ~/validatopia-server && sudo docker compose ps'`.
- **Issuer log:** `sudo docker exec validatopia-server tail -f /app/logs/openid4vci.log`.
- **nginx access log:** `sudo docker exec validatopia-server tail -f /var/log/nginx/access.log`. It shows requests that nginx answered itself, such as 404s, which never reach the issuer.
- **Caddy log:** `sudo journalctl -u caddy`.
- **Database:** `sudo docker exec validatopia-server sqlite3 /app/data/openid4vci.db ".tables"`. Tables are prefixed `Mz`, for example `MzAdminAccounts`, `MzAdminActionLog` (logins and failures, with the client IP) and `MzIssuanceAudit`.
- **Back up:**
  - Stop the container with `sudo docker compose stop`.
  - Copy `openid4vci.db` and `backend.db` out of the data volume. Its path comes from `sudo docker volume inspect validatopia-server_validatopia-data --format '{{.Mountpoint}}'`.
  - Then start it again with `sudo docker compose up -d`.

**Reset the admin password, or clear a lockout.** Three failed logins lock the account for 30 s, doubling to at most 15 min, until a full login succeeds. There's no admin CLI, so the account is recreated through the bootstrap path:
1. `cd ~/validatopia-server && sudo docker compose stop`, then back up `openid4vci.db` as above.
2. Delete the account using the image's `sqlite3`, with the data volume mounted: `sudo docker run --rm -v validatopia-server_validatopia-data:/app/data --entrypoint sqlite3 multipaz/server-bundle:latest-amd64 /app/data/openid4vci.db "delete from MzAdminAccounts where id = 'admin';"`.
3. Put the new password in `.env` as `ADMIN_BOOTSTRAP_PASS=…`, then run `sudo docker compose up -d`. The log shows "Bootstrapped admin account 'admin'", and TOTP enrollment happens on the next login.

This only works when `admin` is the sole account, because bootstrap runs only when there are none. Other accounts are managed on the admin site's Accounts page.

**Gotchas**
- **DNS:** new names can be cached as nonexistent for up to 30 minutes by a local resolver (for example a Pi-hole; its "Restart DNS resolver" button clears the cache). Test from outside with `curl --resolve <host>:443:52.64.118.51`.
- **Wallet issuer URL:** it must end in `/openid4vci`. Without that, wallets post to `/challenge` at the root and get a 404.
- **Admin site exposure:** it's public and gets crawled. Logins need a password plus TOTP; `ADMIN_ALLOW_CIDR` in the compose file can restrict it to known addresses.
- **Moving hosts:** change `BASE_URL` in the compose file, the Caddy site names and the DNS records together.

## Open questions / risks
- The Validatopia codes: proposed alpha-2 `XV` and alpha-3 `XVA`, from the ISO user-assigned range. To be confirmed.
- **Brainpool on iOS** is the hardest crypto gap. It only matters if NZ or AU passports use brainpool, which the M1 discovery task settles. Until it's done, those passports degrade gracefully to "unsupported on device".
- Getting current NZ and AU CSCA certificates, including rollover and the terms for redistributing them in the apps.
- NFCPassportReader depends on OpenSSL through SPM. JMRTD and scuba are LGPL, used only in the Android wallet as unmodified jars, with a NOTICE file.
- Face-model accuracy and demographic bias need calibration. Avoid ArcFace/InsightFace weights, which are licensed for non-commercial use only.
- IsoDep timeouts on long DG2 reads, and clashes between BouncyCastle and Android's own copy of it.
- App Attest and Android key attestation don't work on simulators or emulators. Debug builds need an explicitly flagged dev allow-list, which is off in the public profile.
- JMRTD, NFCPassportReader, ML Kit and ONNX Runtime versions still need to be pinned.
