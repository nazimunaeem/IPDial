# Releasing DialDuo

The release pipeline lives in [`.github/workflows/release.yml`](.github/workflows/release.yml).
It runs unit tests, builds a **signed release APK** and an **AAB**, verifies the
signature, and publishes a GitHub Release with checksums.

You only need to set up the signing secrets **once** per repository.

---

## 1. Required secret names

| Secret name       | What it holds                                              |
| ----------------- | ---------------------------------------------------------- |
| `KEYSTORE_BASE64` | Your release keystore (`.jks`), encoded as one base64 line |
| `STORE_PASSWORD`  | The keystore password                                      |
| `KEY_ALIAS`       | The signing key alias                                      |
| `KEY_PASSWORD`    | The signing key password                                   |

The last three are the values already in your local `keystore.properties`
(that file is gitignored and is **never** uploaded — CI receives them as secrets):

```properties
STORE_FILE=../../keystore/ipdial.jks   # not needed in CI, only used locally
STORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

> Use **repository secrets**, not environment secrets. Environment secrets are
> only visible to jobs that declare that environment, and this workflow does not.

---

## 2. Encode the keystore as base64

The workflow cannot read a file from your machine, so the keystore is stored as
one long base64 string. **macOS:**

```bash
base64 -i /path/to/ipdial.jks | tr -d '\n' | pbcopy   # now on your clipboard
```

**Linux:**

```bash
base64 -w0 /path/to/ipdial.jks
```

**Windows (PowerShell):**

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\ipdial.jks")) | Set-Clipboard
```

The result is a single line starting with something like `MIIK...`. Save it
somewhere safe — you will paste it in the next step.

---

## 3. Add the secrets in GitHub (web UI)

1. Open the repository on GitHub: `https://github.com/nazimunaeem/IPDial`.
2. Click **Settings** (top tab bar of the repo).
3. In the left sidebar: **Secrets and variables → Actions**.
4. Make sure you are on the **Secrets** tab (not *Variables*).
5. Click **New repository secret**.
6. Enter the **Name exactly** as shown in the table — names are
   case-sensitive (`KEYSTORE_BASE64`, not `keystore_base64`).
7. Paste the **Secret** value.
   - For `KEYSTORE_BASE64`: paste the whole base64 line (no line breaks,
     no `data:` prefix, no surrounding quotes).
   - For the passwords/alias: paste the raw value with no extra spaces.
8. Click **Add secret**.
9. Repeat for all four names.

When done, the page should list:

```
KEYSTORE_BASE64      Updated now
KEY_ALIAS            Updated now
KEY_PASSWORD         Updated now
STORE_PASSWORD       Updated now
```

You can only replace a secret, never view it again. If you make a mistake,
click the secret → **Update** and paste again.

---

## 4. Add the secrets with the GitHub CLI (faster alternative)

Requires [`gh`](https://cli.github.com/) and `gh auth login` once.

```bash
cd /path/to/IPDial

# Keystore, base64 on one line (macOS)
gh secret set KEYSTORE_BASE64 --body "$(base64 -i /path/to/ipdial.jks | tr -d '\n')"

# Passwords/alias — omit --body and gh prompts without echoing the value
gh secret set STORE_PASSWORD
gh secret set KEY_ALIAS
gh secret set KEY_PASSWORD

# Confirm
gh secret list
```

For a different repo: `gh secret set NAME --repo owner/repo`.

---

## 5. Bump the version & update release notes

In `app/build.gradle`:

```groovy
versionCode 34        // always increment by 1 for every release
versionName "2.0.0"   // user-visible version
```

Add a matching `## v2.0.0` section in [`RELEASE_NOTES.md`](RELEASE_NOTES.md) (or pass notes directly when running the workflow manually; if neither is provided, CI falls back to git commit messages since the previous tag).

Commit and push that change before releasing.

---

## 6. Run the release

### Option A — from the Actions tab (recommended)

1. **Actions** → **Release Signed APK** (left sidebar).
2. **Run workflow** (right side).
3. Pick the branch (`main`).
4. Optionally enter **Release notes**, or tick **Publish as a draft release** / **Mark as pre-release**.
5. **Run workflow**.

### Option B — with the CLI

```bash
gh workflow run release.yml --ref main \
  -f release_notes="- Redesigned Contacts tab" \
  -f draft=false -f prerelease=false
gh run watch
```

### Option C — by pushing a tag

```bash
git tag v2.0.0          # must equal versionName exactly, prefixed with "v"
git push origin v2.0.0
```

The workflow fails fast if the tag does not match `versionName`.

The run creates (or updates) the GitHub Release
`v<versionName>` with the release notes and attaches:

- `DialDuo-v<versionName>.apk` — signed, installable APK
- `DialDuo-v<versionName>.aab` — signed bundle for Google Play
- `SHA256SUMS.txt` — checksums

---

## 7. Verify locally (optional)

The same env-var contract the CI uses works on your machine:

```bash
export STORE_FILE=/absolute/path/to/ipdial.jks
export STORE_PASSWORD='...'
export KEY_ALIAS='...'
export KEY_PASSWORD='...'
./gradlew :app:assembleRelease
```

Without those variables the build falls back to `keystore.properties`.

---

## 8. Troubleshooting

| Symptom | Fix |
| --- | --- |
| `KEYSTORE_BASE64 secret is missing` | The secret name is wrong or missing — re-add it exactly as spelled above. |
| `Decoded keystore is empty` | The secret contains line breaks/quotes; re-encode with `tr -d '\n'` and paste only the single line. |
| `Keystore was tampered with, or password was incorrect` | `STORE_PASSWORD` is wrong (keystore password, not key password). |
| `Cannot recover key` | `KEY_PASSWORD` is wrong, or `KEY_ALIAS` does not exist in that keystore. List aliases with `keytool -list -keystore ipdial.jks`. |
| `Tag v2.0.0 does not match app version v1.2.2` | Bump `versionName`, commit, or push the matching tag. |
| Build passes locally but fails in CI | CI recreates the gitignored `gradle.properties`; if you add new required Gradle flags, add them to the workflow's **Configure Gradle for CI** step. |
| `apksigner verify` fails | The keystore decoding step or passwords are wrong; check the build log around the signing config. |
