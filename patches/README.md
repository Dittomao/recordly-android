# Renderer patches

The Android build runs the upstream Recordly renderer from
`recordly-upstream/`, which is an independent checkout pinned at:

- version `2.0.0-beta.1`
- commit `20e9490fb3b1739cb777fa1dd56c43021114521b` (2026-11-15)

`recordly-android.patch` contains every renderer change this port needs:

- phone shell: dashboard/editor drawers, compact timeline, touch settings
- touch auto-zoom scheduling, adaptive depth, and fitted spring timing shared
  by playback and export
- touch settings surface (no taskbar/updates/shortcuts/account on Android)
- consent-denial handling and the `canceled` result type
- the earlier thumbnail-capture fixes

The generated renderer bundle is not tracked. Apply the patch to a clean
upstream checkout, then build the bundle:

```sh
git -C recordly-upstream apply ../patches/recordly-android.patch
node scripts/build-renderer.mjs
```

`scripts/build-android.ps1` also builds the renderer automatically when the
bundle is missing or older than the renderer/bridge sources, and forces an asset
merge and repackage when the bundle is newer than the debug APK.

## Regenerating

After editing `recordly-upstream/`, regenerate from the repository root:

```sh
git -C recordly-upstream add -N .
git -C recordly-upstream diff --binary > patches/recordly-android.patch
git -C recordly-upstream reset
```

Use raw redirection (`cmd /c` on Windows) so the patch is UTF-8, not UTF-16,
and keep it LF. The repository marks `patches/*.patch` as `-text` so a Windows
checkout does not convert it to CRLF, which would make every hunk fail. Check
that it matches the working tree in reverse, and that it applies to a pristine
upstream checkout:

```sh
git -C recordly-upstream apply --check -R ../patches/recordly-android.patch
git -C <clean-upstream-at-pinned-commit> apply --check ../patches/recordly-android.patch
```
