# Share EasyBase64 on GitHub

These instructions are for creating your repository and publishing a release manually. Preparing these materials did not push code or publish a release. The application code and verified APK remain unchanged. The public source includes Chinese `README.md`, English `README.en.md`, the MIT `LICENSE`, and third-party license notices.

## 1. Create an empty repository

Create a repository such as `EasyBase64` under your GitHub account and select **Public**. Do not initialize it with another README, license, or `.gitignore`: the source archive already contains these files, and separate initialization can create unnecessary commit conflicts. Replace `OWNER` below with your GitHub username or organization; replace the repository name too if you choose a different one. [GitHub’s official import instructions](https://docs.github.com/en/migrations/importing-source-code/using-the-command-line-to-import-source-code/adding-locally-hosted-code-to-github)

## 2. Upload the small source archive with a new history

The source archive contains no `.git` directory. Extract it into a separate directory, enter the project root containing both `README.md` and `gradlew`, and run:

```sh
git init -b main
git add .
git status --short
git commit -m "Prepare EasyBase64 1.0.0 for public sharing"
git tag v1.0.0-public
git remote add origin https://github.com/OWNER/EasyBase64.git
git push -u origin main
git push origin v1.0.0-public
```

Before committing, check that `git status` lists only public source and documentation. If Git asks for an identity, configure your own `user.name` and `user.email` in this repository, then retry the commit. Use your configured GitHub authentication method when pushing.

The `v1.0.0-public` tag identifies the source prepared for public sharing. The application version is still **1.0.0**; preparing documentation did not change the APK.

## 3. Optional: preserve the existing history

To preserve development history, use the optional **public-preparation Git bundle** instead of step 2. Do not run `git init` first. Replace `PATH_TO_PUBLIC_BUNDLE` with that bundle’s actual path:

```sh
git clone PATH_TO_PUBLIC_BUNDLE EasyBase64
cd EasyBase64
git switch main
git remote set-url origin https://github.com/OWNER/EasyBase64.git
git push -u origin main
git push origin v1.0.0-public
```

This bundle already includes the public-preparation commit and `v1.0.0-public` tag, and retains the original `v1.0.0` tag. These commands upload the main branch history and public tag. To publish the old tag as well, run `git push origin v1.0.0` separately. There is no need to delete, move, or recreate the original tag.

Choose either the source-archive workflow or the bundle workflow.

## 4. Publish the installable APK

After uploading the repository, open **Releases → Draft a new release** on GitHub. Select `v1.0.0-public`, use a title such as `EasyBase64 1.0.0`, copy the prepared bilingual release notes, and attach the public assets. [GitHub’s official release instructions](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)

Suggested public assets:

- The existing `EasyBase64-1.0.0-universal.apk`; users install it directly without choosing a CPU architecture.
- `easybase64-certificate.pem`, the public certificate.
- `APK-SIGNATURE.txt`, the signature verification summary.
- The SHA-256 manifest prepared **specifically for public assets**.
- Optional source and verification archives; the Debug package is an optional download.

Keep APKs out of source commits. There is no need to rebuild or sign the verified APK again. Do not reuse the old all-in-one delivery checksum manifest as the public asset manifest if it includes private files. Use the accompanying download instructions for exact split-file names, reassembly, and checksum verification.

## 5. Keep signing materials private

**Never upload these items to a public repository, GitHub Release, issue, or discussion:**

- `EasyBase64-signing-private.zip`.
- `signing/easybase64-delivery.jks` and `signing/SIGNING-PRIVATE.txt`.
- `signing.properties` containing actual passwords.
- The old `EasyBase64-1.0.0-delivery.zip`: it contains the private signing archive.

Public certificates and certificate fingerprints may be shared. Private keys and passwords must remain private. Store the private signing archive in your own private backup location.

To continue local development using the same signing identity, create a separate local working copy and restore the signing materials there. **Do not extract the private signing ZIP into this public-preparation directory.** Follow the private signing instructions for restoration. A public clone can build the Debug APK; a signed release requires the maintainer’s own local signing configuration. Never commit those materials. Maintainer signing-verification scripts are not a required step for an ordinary public clone.
