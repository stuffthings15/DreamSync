# DreamSync source provenance

Date: 2026-07-19

Product: DreamSync

Publisher: Remote

Package: `com.remote.DreamSync`

Audited source boundary: commit `33e4cef12479ce448938a931cd01d3cb2c2bae70`

## Executive finding

The live tree is technically traceable, but public-production ownership clearance is not yet proven. The first Git commit, `fdd1244`, imported an earlier phone/watch prototype and related assets. Later commits substantially renamed, redesigned, and hardened that work. Git history proves when files entered this repository; it does not prove that Remote received copyright or publication rights from every earlier contributor.

Internal testing and capstone use may continue. Public production release stays blocked until the owner signs `PROVENANCE_SIGNOFF_TEMPLATE.md` and attaches the supporting records named below.

## Evidence register

| Material | Origin visible in repository | Current evidence | Status | Required production action |
|---|---|---|---|---|
| Baseline phone/watch Java, XML, and Gradle files | Imported in `fdd1244` from an earlier prototype archive | Git import history and surviving rewritten files | Unresolved | Obtain a signed assignment, license, or original-author declaration covering the imported prototype. |
| Code added after baseline | Developed under Remote direction and committed under the local owner Git identity | Full Git history from `109b3ab` through the audited commit | Traceable, not legal proof | Preserve task records and have the owner confirm authority in the sign-off. |
| DreamSync vector launcher art and interface shapes | Project-created assets first committed with the baseline or later redesigns | Git history and source XML | Origin partly unresolved | Confirm creator and assignment for baseline vectors; identify later project-created assets in the sign-off. |
| Play feature graphics | Project-generated artwork, including AI-assisted work | PNG files, Git history, SHA-256 values in `RELEASE_CHECKSUMS.txt` | Traceable | Retain generation/task records and owner approval. Do not represent synthetic art as a real device photograph. |
| Phone/tablet store screenshots | Captured from DreamSync builds/emulators | PNG files and release checksums | Traceable | Owner confirms the displayed content is Remote's app and contains no third-party personal data. |
| Presentations, proposals, matrices, and reports | Project-authored capstone material using cited research | Git history, source links, and final deck checksum | Traceable | Keep citations; do not redistribute full third-party papers unless licensed. |
| `docs/sleep study data.docx` and other supplied research/course documents | User-supplied reference material | Local files only; not packaged into either AAB | Private reference | Confirm course/paper sharing rights. Never ship these source documents inside the public app. |
| AndroidX, Room, Kotlin, coroutines, JSpecify, and Guava support code | Resolved Gradle dependencies | `THIRD_PARTY_NOTICES.md` and Gradle dependency report | Third-party terms apply | Retain notices and comply with the named licenses. |
| Google Play services for Wear OS | Resolved Gradle dependency | Gradle POM identifies Android SDK License terms | Third-party terms apply | Comply with Google APIs/Android SDK and Play distribution terms. |
| Samsung Health Sensor SDK | Optional dependency path only; AAR absent | Conditional `wear/build.gradle` entry and fallback implementation | Not approved or packaged | Obtain AAR through Samsung, partnership approval, and package/signature registration before enabling advanced Samsung status. |
| Upload key and Play account | Locally controlled, ignored signing material and Remote Play account | Release signatures and Play release records | Private evidence | Keep credentials private; retain the registration receipt and signing-certificate fingerprints outside Git. |

## Artifact fingerprints

- Source commit: `33e4cef12479ce448938a931cd01d3cb2c2bae70`
- Store icon: `292BA3E52D15BD34E18D7C87085AB748B710CC81503CD2DB1223449F806102F7`
- Final capstone deck: `18E75F71FF7AC4B2533BBD4F9A64E83FC925E34C4624086A22B76192FAFBF2B0`
- All current AAB, APK, feature-graphic, and screenshot fingerprints: `RELEASE_CHECKSUMS.txt`

## Production evidence packet

Before requesting public production access, collect these items outside the repository:

1. Signed `PROVENANCE_SIGNOFF_TEMPLATE.md`.
2. Written prototype assignment/license or a declaration from the original author.
3. Asset creation or generation records for the icon and feature graphics.
4. Confirmation that course and research documents are reference-only and will not ship.
5. Google Play account receipt and current signing-certificate fingerprints.
6. Samsung approval and registration records if the Samsung SDK is added.
7. A release archive containing this register, `THIRD_PARTY_NOTICES.md`, checksums, and the exact Git commit.

## Release boundary

- Private/internal testing may continue for capstone and engineering validation.
- Public production publication remains blocked until the signed evidence packet exists.
- The proprietary `LICENSE` states Remote's intended terms for original DreamSync material. It does not override third-party licenses or cure missing contributor rights.
- The Samsung AAR must never be copied from an unofficial source or committed to this repository.

This is an engineering provenance register, not legal advice and not a medical, clinical, or efficacy claim.
