# Releasing Zeron Android

Release APKs are built by `.github/workflows/release.yml` when a `v*` tag is
pushed. They're signed with a release key that exists only as repository
secrets, plus one encrypted backup the owner keeps (#66).

## The release key (once)

1. Pick a long backup passphrase. Store it in the password manager first,
   then set it as the repository secret `ZERON_RELEASE_BACKUP_PASSPHRASE`
   (Settings → Secrets and variables → Actions).
2. Run **Actions → Release key → Run workflow** on `main`. The run log shows
   the new certificate's SHA-256 fingerprint. Within a day, download the
   `zeron-release-key-backup` artifact; it holds
   `zeron-release-key.tar.gz.enc`.
3. Decrypt and unpack it on a trusted machine (OpenSSL 1.1.1 or later; on
   Windows, Git Bash's `openssl` works):

   ```sh
   openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 \
     -in zeron-release-key.tar.gz.enc -out zeron-release-key.tar.gz
   # (prompts for the backup passphrase)
   mkdir zeron-release-key && tar -xzf zeron-release-key.tar.gz -C zeron-release-key
   cat zeron-release-key/release-key.txt
   ```

4. Set the three signing secrets with the GitHub CLI. Values piped through
   stdin never reach the shell history:

   ```sh
   base64 -w0 zeron-release-key/zeron-release.p12 | gh secret set ZERON_RELEASE_KEYSTORE_BASE64
   grep '^password: ' zeron-release-key/release-key.txt | cut -d' ' -f2 | tr -d '\n' | gh secret set ZERON_RELEASE_KEYSTORE_PASSWORD
   printf zeron-release | gh secret set ZERON_RELEASE_KEY_ALIAS
   ```

   (macOS `base64` has no `-w0`; use `base64 -i zeron-release-key/zeron-release.p12`.)

5. Hand the **encrypted** `zeron-release-key.tar.gz.enc` and the passphrase
   to the owner's password manager. Then delete the plaintext copies:
   `rm -rf zeron-release-key zeron-release-key.tar.gz`.

Losing both the secrets and the backup means future releases can't update
installed copies, because Android requires the same signing key.

## A release

The tag names the Zeron core the app runs (`zeron/Cargo.toml`'s
`[workspace.package]` version), optionally followed by a build number for a
re-release on the same core:

| Tag | versionName | versionCode |
|---|---|---|
| `v0.2.129` | 0.2.129 | 2129000 |
| `v0.2.129-2` | 0.2.129-2 | 2129002 |

```sh
git tag v0.2.129 && git push origin v0.2.129
```

The workflow then builds the core in the `mobile-dist` profile and the arm64
APK with R8. It signs the APK, verifies the signature, and publishes a GitHub
Release with `zeron-android-<version>-arm64-v8a.apk` and its `.sha256`. The
R8 mapping stays with the workflow run, as a 90-day artifact. A tag that
doesn't match the pinned core fails before anything builds, and so do
missing secrets.

Every pull request also builds the release variant, unsigned (the App
workflow's `release-check` job), so a broken keep rule shows up before a tag.
