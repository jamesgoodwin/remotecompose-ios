# Releasing

`Package.swift` names a release asset and a checksum, so the package resolves against a GitHub
release and nothing else. The zip is 38MB and `build/` is ignored, so it is never committed. It is
built and uploaded:

```bash
tools/release-xcframework.sh v0.1.0     # assembles, zips, writes the url and checksum
git commit -am "Point the package at v0.1.0"
git push
gh release create v0.1.0 build/XCFrameworks/RemoteComposeShared.xcframework.zip --title v0.1.0
```

Upload the exact file that script produced. Kotlin/Native does not link reproducibly, so building
again gives a different zip and the checksum just committed no longer matches.

Give each release a new version; do not replace one. SwiftPM defends against a published tag being
repointed, in two ways that both look like a broken build to whoever hits them:

- it records version to revision the first time it resolves a package, in
  `~/Library/org.swift.swiftpm/security/fingerprints`, and refuses afterwards with *does not match
  previously recorded value*
- it caches the binary artifact under the download URL, in
  `~/Library/Caches/org.swift.swiftpm/artifacts`, so the same URL with new bytes hands back the old
  ones and fails the checksum

Neither affects a machine that has never resolved the package, which is why replacing v0.1.0 before
anyone depended on it was safe. It is not safe afterwards, and nothing about the failure tells the
person hitting it to delete those two directories.

The repository has to be public before SwiftPM can fetch the asset; a private release asset needs
credentials SwiftPM will not send.
