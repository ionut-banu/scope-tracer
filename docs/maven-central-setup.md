# Maven Central setup (one-time, maintainer only)

These steps prepare the repository to publish artifacts to Maven Central via
the `release` Maven profile and the `release.yml` GitHub Actions workflow.

Once complete, every `v*.*.*` tag pushed to the repo will trigger an automated
release: build → test → sign → publish → GitHub Release with the matching
CHANGELOG section.

---

## 1. Verify the `com.ionutbanu` namespace on Sonatype Central Portal

1. Create an account at <https://central.sonatype.com/> (sign in with GitHub
   is the simplest option).
2. Open **Namespaces** → **Add Namespace** → enter `com.ionutbanu`.
3. The portal will display a verification key (looks like a UUID).
4. Add a DNS TXT record on `ionutbanu.com`:

   | Field | Value |
   |---|---|
   | Type | TXT |
   | Name / Host | `@` (or leave blank, depending on your DNS provider's UI) |
   | Value | the UUID shown by the portal |
   | TTL | default (300s) is fine |

5. Wait for propagation (usually under a minute; max 24h) — verify with
   `dig TXT ionutbanu.com +short`.
6. Click **Verify** on the portal. The namespace should flip to **Verified**.

## 2. Generate user token for the publishing API

1. In the portal, click your username → **View Account** → **Generate User Token**.
2. The portal will show two values **only once** — copy both immediately:
   - **Token Username** (looks like a random short string)
   - **Token Password** (looks like a long random string)

These map to the GitHub secrets `CENTRAL_USERNAME` and `CENTRAL_TOKEN`.

## 3. Generate a GPG signing key

Maven Central requires every artifact to be signed.

```bash
# Generate a key — choose RSA, 4096 bits, no expiry (or 2 years, your call).
# Use the same name and email you use on Git commits.
gpg --full-generate-key

# List your secret keys to find the long key ID (the 16-char hex after "sec rsa4096/").
gpg --list-secret-keys --keyid-format=long

# Example output:
# sec   rsa4096/ABCDEF0123456789 2026-05-13 [SC]
#       <fingerprint>
# uid                          Ionut Banu <ionutba@protonmail.com>
# Replace KEYID below with the long key ID (ABCDEF0123456789 in this example).
export KEYID=<your-key-id>

# Publish the public key to the major keyservers (Central verifies via these).
gpg --keyserver keys.openpgp.org --send-keys "$KEYID"
gpg --keyserver keyserver.ubuntu.com --send-keys "$KEYID"
```

> `keys.openpgp.org` will email a verification link to the address on the key
> — click it to make the key publicly discoverable by email.

Export the secret key for the GitHub workflow:

```bash
gpg --armor --export-secret-keys "$KEYID"
```

Copy the **entire** output, including the
`-----BEGIN PGP PRIVATE KEY BLOCK-----` and
`-----END PGP PRIVATE KEY BLOCK-----` lines.

## 4. Configure GitHub repository secrets

Go to `https://github.com/ionut-banu/scope-tracer/settings/secrets/actions`
and add four repository secrets:

| Secret name | Value |
|---|---|
| `CENTRAL_USERNAME` | Token Username from step 2 |
| `CENTRAL_TOKEN` | Token Password from step 2 |
| `GPG_PRIVATE_KEY` | The armored secret key block from step 3 |
| `GPG_PASSPHRASE` | The passphrase you set when generating the key |

## 5. Dry-run the release locally (recommended)

Before cutting the first tag, sanity-check that the release profile produces
all expected artifacts and signs them.

```bash
# Import your GPG key into the local keychain (already done if you used gpg on this machine).
export MAVEN_GPG_PASSPHRASE='<your-passphrase>'

# Build with sources + javadoc + signatures, skipping the actual publish.
mvn -P release verify
```

After this completes, each library module's `target/` should contain:

- `scope-tracer-<module>-0.1.0-SNAPSHOT.jar`
- `scope-tracer-<module>-0.1.0-SNAPSHOT-sources.jar`
- `scope-tracer-<module>-0.1.0-SNAPSHOT-javadoc.jar`
- A `.asc` signature file alongside each of the above

If signatures are missing, the GPG key isn't available to Maven — check
`gpg --list-secret-keys` and verify `MAVEN_GPG_PASSPHRASE` is exported.

## 6. Cut the first release

Follow the **Cutting a release** section in [CONTRIBUTING.md](../CONTRIBUTING.md#releasing).

The very first release is the most likely to surface issues — watch the
workflow run carefully. Common failures:

| Symptom | Likely cause | Fix |
|---|---|---|
| `401 Unauthorized` on publish | wrong token, or token revoked | regenerate user token, update `CENTRAL_USERNAME` / `CENTRAL_TOKEN` |
| `gpg: signing failed: No secret key` | key not imported, or wrong `MAVEN_GPG_PASSPHRASE` | check the `setup-java` step's GPG import; re-export the key |
| Validation rejection on Central Portal | missing javadoc/sources, or pom missing required fields | check `mvn -P release verify` output; compare to `<licenses>`, `<scm>`, `<developers>` in parent pom |
| Namespace not verified yet | DNS hasn't propagated | wait and re-verify in portal |

## 7. Post-release

- Verify the artifact appears on <https://repo1.maven.org/maven2/com/ionutbanu/>
  (~30 minutes after publish completes).
- From a fresh, empty directory, run:

  ```bash
  mvn dependency:get -Dartifact=com.ionutbanu:scope-tracer-core:0.1.0
  ```

  Success means the artifact is consumable by any Maven user worldwide.
- Update the README badge once a Maven Central badge service has indexed the new
  version (use <https://central.sonatype.com/artifact/com.ionutbanu/scope-tracer-core>).
