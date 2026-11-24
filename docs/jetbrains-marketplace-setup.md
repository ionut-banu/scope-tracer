# JetBrains Marketplace setup (one-time, maintainer only)

These steps prepare the repository to sign the IntelliJ plugin and publish it to
the [JetBrains Marketplace](https://plugins.jetbrains.com/) from the
`publish-plugin` job of `.github/workflows/release.yml`.

Once complete, every `v*.*.*` tag builds the plugin zip from the tag, checks it,
signs it, attaches the signed zip to the tag's GitHub Release and, once the
Marketplace upload is switched on (step 6), uploads it to the Marketplace. The
Marketplace reviews every version, the first and every update, before users see
it: normally within two business days, with no guaranteed time frame. If a
version is still waiting after 3-4 working days, write to
<marketplace@jetbrains.com>.

Until the three signing secrets of step 5 exist, every release's
`publish-plugin` job is red and names the missing secrets. Maven Central and the
GitHub Release are not affected: the `publish` job finishes them first.

Everything here is done by the maintainer, by hand. No agent creates the
account, the token or the key, and no secret value goes into an issue, a pull
request, a commit or a log.

---

## 1. JetBrains Account and the Developer Agreement

1. Sign in to <https://plugins.jetbrains.com/> with a JetBrains Account (create
   one at <https://account.jetbrains.com/> if needed).
2. Accept the **JetBrains Marketplace Developer Agreement** when the
   Marketplace asks for it (it does on the first upload attempt, or from your
   profile).

## 2. Vendor profile

1. On the Marketplace, open your profile and create a vendor profile for an
   **individual** vendor named **Ionut Banu**. `plugin.xml`'s `<vendor>` element
   names the same vendor, with the email address and URL that appear on the
   listing:

   ```xml
   <vendor email="ionutba@protonmail.com" url="https://github.com/ionut-banu/scope-tracer">Ionut Banu</vendor>
   ```

   The email address is public on the listing.
2. Fill in the **trader status** declaration the Marketplace asks of every
   vendor under the EU Digital Services Act (whether you act as a trader in the
   EEA).

## 3. Marketplace personal access token

1. On the Marketplace, open your profile → **My Tokens** → **Generate Token**.
   Name it after this repository (for example `scope-tracer release.yml`).
2. Copy the token immediately. **It is shown only once**; if you lose it,
   revoke it and generate a new one.

This is the repository secret `JETBRAINS_MARKETPLACE_TOKEN` (step 5).

## 4. Signing key and certificate

The release signs the plugin zip itself, with a self-signed certificate, so the
Marketplace can tell that the zip it received is the one CI built. The commands
are JetBrains' own, from <https://plugins.jetbrains.com/docs/intellij/plugin-signing.html>.
Run them in a private directory outside any git tree:

```bash
# A 4096-bit RSA key, encrypted with a password you choose (you are asked for it).
openssl genpkey\
  -aes-256-cbc\
  -algorithm RSA\
  -out private_encrypted.pem\
  -pkeyopt rsa_keygen_bits:4096

# The same key in the form the signer reads (asks for the password again).
openssl rsa\
  -in private_encrypted.pem\
  -out private.pem

# The self-signed certificate. -days is the validity you pick; JetBrains' example is 365.
openssl req\
  -key private.pem\
  -new\
  -x509\
  -days 365\
  -out chain.crt
```

`openssl req` asks for the certificate's subject; give your name and the
vendor's email address. Then read the expiry date and write it in the table
below, so the renewal is not a surprise:

```bash
openssl x509 -enddate -noout -in chain.crt
```

| Certificate | Created | Validity | Expires |
|---|---|---|---|
| current | _(date)_ | _(days)_ | _(date from the command above)_ |

The secrets take the chain and the key Base64-encoded, on one line. `signPlugin`
decodes both itself; for `verifyPluginSignature`, which would otherwise write the
Base64 text to a `.pem` file as it is and find no certificate in it,
`scope-tracer-plugin/build.gradle.kts` decodes the chain first:

```bash
base64 -i chain.crt | tr -d '\n'     # macOS; on Linux: base64 -w0 chain.crt
base64 -i private.pem | tr -d '\n'   # macOS; on Linux: base64 -w0 private.pem
```

Keep `private_encrypted.pem`, `private.pem`, `chain.crt` and the password
somewhere safe and offline (a password manager), never in the repository.

## 5. GitHub repository secrets

Go to `https://github.com/ionut-banu/scope-tracer/settings/secrets/actions` and
add four repository secrets:

| Secret name | Value | Read by Gradle as |
|---|---|---|
| `JETBRAINS_CERTIFICATE_CHAIN` | Base64 of `chain.crt` (step 4) | `CERTIFICATE_CHAIN` |
| `JETBRAINS_PRIVATE_KEY` | Base64 of `private.pem` (step 4) | `PRIVATE_KEY` |
| `JETBRAINS_PRIVATE_KEY_PASSWORD` | The password you chose in step 4 | `PRIVATE_KEY_PASSWORD` |
| `JETBRAINS_MARKETPLACE_TOKEN` | The token from step 3 | `PUBLISH_TOKEN` |

`release.yml` gives them to Gradle only as environment variables of the steps
that sign and upload, under the names in the last column. Gradle records task
inputs, these among them, in plain text in its project cache, so those steps keep
that cache under the runner's temp directory (`--project-cache-dir
"$RUNNER_TEMP/gradle-project-cache"`) and the job's last step deletes it, however
the job ended. If you ever sign by hand, delete `scope-tracer-plugin/.gradle/`
afterwards for the same reason. A step before the
build checks only whether each one is set, never its value: with any of the
first three missing, `publish-plugin` fails before building and never uploads an
unsigned zip.

## 6. The first upload, then the switch

The Marketplace takes a plugin's **first** version only from its web form, so
the first upload is manual: #198. Upload the signed zip that `publish-plugin`
attached to the GitHub Release (`scope-tracer-plugin-X.Y.Z-signed.zip`), never a
zip built on your machine.

After the Marketplace has accepted the first version, add a repository
**variable** (not a secret) under Settings → Secrets and variables → Actions →
Variables:

| Variable name | Value |
|---|---|
| `PLUGIN_MARKETPLACE_PUBLISH` | `true` |

From then on, each release tag uploads its signed zip with `publishPlugin`. With
the variable unset or anything but `true`, the upload step is skipped with a
notice saying the first upload is manual. With it `true` and
`JETBRAINS_MARKETPLACE_TOKEN` empty, `publish-plugin` fails naming that secret.

## 7. When the certificate expires or the token leaks

- **The certificate expires** (the date in step 4's table): repeat step 4 with a
  new key, replace `JETBRAINS_CERTIFICATE_CHAIN`, `JETBRAINS_PRIVATE_KEY` and
  `JETBRAINS_PRIVATE_KEY_PASSWORD`, and update the table. Do it before the expiry
  date, so no release is signed with an expired certificate.
- **The token leaks or is lost**: revoke it on the Marketplace under profile →
  **My Tokens**, generate a new one (step 3) and replace
  `JETBRAINS_MARKETPLACE_TOKEN`.
- **The key leaks**: treat it as expired: make a new key and certificate now and
  replace the three signing secrets.

## 8. Dry-run without publishing

Dispatch the `Release` workflow from `main` with `check_only` ticked:

```bash
gh workflow run release.yml --ref main -f tag=vX.Y.Z -f check_only=true
```

The `check` job builds the tag's plugin and runs `verifyPluginStructure`,
`verifyPlugin` and `.github/scripts/check-plugin-zip.sh` on it, with no secret.
Nothing is signed or uploaded: `publish` and `publish-plugin` are skipped. See
"Releasing" in [CONTRIBUTING.md](../CONTRIBUTING.md#releasing).
