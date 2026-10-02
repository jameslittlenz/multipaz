# Passport profiles

How the target countries' passport chips work, as found by reading real passports with the Android
wallet. The "Show chip details" report on the wallet's "Passport chip read" screen produces most of
these items. It contains no personal data.

Never record personal data here: no names, dates, document numbers, MRZ text or images.
`SyntheticPassportProfile` in `multipaz-idv` mirrors each profile, so tests cover the real layouts
without real passport data.

## New Zealand (NZL)

Read on 2026-10-02 from one current passport, with a Moto G15 (Android 15).

| Item | Finding |
|---|---|
| Access control | PACE (the wallet tries PACE first and falls back to BAC; BAC wasn't tested) |
| Data groups (EF.COM) | DG1, DG2, DG12, DG13, DG14, DG15 |
| Chip Authentication (DG14) | Present |
| Active Authentication (DG15) | Present |
| SOD signature | ECDSA with SHA-256 (ES256) |
| SOD and data group hash | SHA-256 |
| Document Signer issuer | `CN=Passport CSCA, OU=Identity and Passport Services, O=Government of New Zealand, C=NZ` |
| CSCA | In the ICAO master list bundled as `IcaoCscaCertificates`. The current NZ CSCAs are NIST P-384; an older one is RSA. None use brainpool curves. |
| SOD encoding | The `0x77` EF.SOD tag around a `ContentInfo` with BER **indefinite lengths** (`30 80`, `A0 80`); the `SignedData` inside is DER |
| DG2 encoding | ISO/IEC 19794-5, one template with one image |
| DG2 image | JPEG (not JPEG 2000), 413 x 531 pixels, about 15 KB |
| DG2 feature points | 2, before the image |
| MRZ | Read by the camera once frames are thresholded to black and white: the security printing behind the MRZ otherwise defeats ML Kit |
| Face match | Selfie against the DG2 portrait: SFace cosine similarity 0.58 to 0.71 over 8 attempts by the holder. One person, so not a calibration |

What this meant for the code:
- `SignedData.parse` had to accept the EF.SOD tag and indefinite lengths, and keep the signed
  attributes and certificates exactly as encoded.
- `Lds.parseDG2` had to skip DG2's feature points.
- Passive authentication then passes against the bundled CSCAs, and the issuer issues a Photo ID.
- DG15 is present, so Active Authentication is possible for NZ passports. It isn't implemented
  (see "Open questions" in `PLAN.md`).

Not yet recorded: the Document Signer's curve, whether the chip also accepts BAC, and MRZ name
formatting (DG11 isn't read, so names come only from DG1).

## Australia (AUS)

Not yet tested: no Australian passport has been available. The AU CSCAs are in
`IcaoCscaCertificates`, so passive authentication should pass if the SOD's algorithms are supported.
Read one with a debug build and record the same items before relying on it.

## Face-match calibration

Not done. It needs genuine and impostor selfie-passport pairs from several consenting people. Record
the false accept and false reject rates at candidate thresholds here. The default threshold of 0.5
is a guess: the two placeholder persona portraits (different people) score 0.363 against each other,
and the one NZ holder's genuine pairs scored 0.58 to 0.71.
