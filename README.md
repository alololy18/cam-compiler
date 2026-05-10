# Cam Compiler — v2

A focused Android app for **merging video clips from any camera (action cam, dashcam, helmet cam) into a single video file**. Optimized for the workflow of compiling many short clips into one longer vlog/video.

## Features (v2)

### Core
- **Pick any folder** on your phone — SD card, internal storage, USB drive
- **Auto-scan** for video files (MP4, MOV, MKV, AVI)
- **Auto-sort chronologically** the moment you pick a folder, using:
  1. Date/time embedded in filename (handles common camera formats like `2025_05_09_14_32_15`, `20250509_143215`, `VID_*`, etc.)
  2. Sequence numbers in filename (`MOV0001.MP4`, `GH010001.MP4`)
  3. File modification time (fallback)
  4. Cross-validates filename order vs file timestamps and **warns** if they disagree

### Merging
- **Merge ALL chronologically** — one tap, all clips in the folder, in correct order
- **Merge selected** — tap clips in custom order if you want a subset
- **Total duration display** before merging — know how long the output will be
- **Two-engine merging** — uses Media3 Transformer (fast, hardware-accelerated) and automatically falls back to FFmpegKit if Media3 can't handle the codec
- **Stream-copy when possible** — for clips with identical encoding (typical from one camera), no re-encoding needed → near-instant merge

### Reliability
- **Foreground service with progress notification** — keep your phone screen off, lock the device, switch apps; the merge keeps running
- **Cancel button** — stop a merge in progress (in-app or directly from the notification)
- **Persistent folder memory** — opens to your last-used folder automatically on next launch
- **Permission survival** — folder access persists across reboots

### Output
- Saved to phone's **Downloads folder** as `vlog_TIMESTAMP.mp4`
- Visible in the standard Files app, gallery, and any video player

---

## Updating an Existing GitHub Repo (Recommended Path)

Since you already have the `cam-compiler` repo set up from v1, here's how to upgrade to v2:

### Option A: Replace all files (cleanest — recommended)

1. Go to your `cam-compiler` repo on GitHub
2. **Delete the old files** — easiest is to delete the repo and recreate (https://github.com/YOUR_USERNAME/cam-compiler/settings → Danger Zone → Delete), then create a fresh empty `cam-compiler` repo
3. Or, manually delete files via the GitHub web UI: open each file, click trash icon, commit

4. Unzip the new `CamCompiler.zip` on your PC
5. Go to your repo's empty page → "uploading an existing file"
6. **Drag the contents of the unzipped folder** (not the folder itself) into the upload area
7. Make sure `.github` folder is included (enable "Show hidden files" in Windows Explorer)
8. Commit changes

### Option B: Keep the repo, replace files in place

If you want to keep your repo's history:
1. Go to your repo → click each file you want to replace → pencil icon → paste new contents → commit
2. For new files (like `MergeService.kt`, `Prefs.kt`, etc.), use Add file → Create new file → paste contents

This is more tedious for v2 since the folder structure changed (new package name `com.camcompiler.app`). **Option A is recommended.**

### After uploading

1. Go to **Actions** tab → wait for green checkmark (5–7 mins)
2. Open Actions on your phone → latest build → download **CamCompiler-APK** artifact
3. Extract the zip, tap the APK to install

> If you've already installed v1 ("HelmetCam Compiler"), v2 ("Cam Compiler") will install **alongside** it because the package name changed. You can uninstall v1 anytime — they don't share data.

---

## Using the App

1. Insert your camera's microSD card via USB-C SD reader
2. Open Cam Compiler
3. Tap **Pick Folder** → navigate to your SD card → select the folder containing the videos
4. The app scans, lists all clips, and **auto-sorts them chronologically**
5. Read the status bar to see how it sorted them ("Sorted by date in filename", etc.)
6. **If a yellow warning appears**, the app's two ordering signals disagree — review the order before merging
7. Tap the **"Merge ALL N clips chronologically"** button at the top → it runs in the background
8. You can lock the phone, switch apps, or just wait — a notification shows progress
9. When done, you'll see a notification + toast → file is in **Downloads**

### Want a custom subset/order?
- Just tap clips individually instead of using the "Merge ALL" button
- Numbered badges show your custom order
- Tap **"Merge N selected"** at the bottom

---

## Behind the Scenes — How Auto-Sort Works

The app reads filenames and tries to extract dates in this priority:

1. **Full date+time patterns**: `2025_05_09_14_32_15`, `20250509_143215`, `2025-05-09 14:32:15`, etc.
2. **Two-digit year variants**: `250509_143215`
3. **Sequence numbers**: longest digit run in the filename (`MOV0001` → 1, `GH010002` → 10002)
4. **File modification time**: when nothing else works

Then it **cross-checks** the filename-derived order against the file modification time order. If they agree, no warning. If they disagree by more than ~10%, a yellow warning banner appears explaining what happened, so you can decide whether to trust the sort.

This is robust enough to handle Qubo, GoPro, Insta360, dashcams, generic action cams, and most Android-recorded videos without any user configuration.

---

## Troubleshooting

**"No video files found"** — the picker is showing files of an unsupported extension. Check that the videos really are `.mp4`/`.mov`/`.mkv`/`.avi`. Some cameras use unusual extensions; let me know which and I'll add support.

**Yellow sort warning** — the app's filename-based and modification-time-based sort orders disagree. Common causes: camera clock was wrong, files were copied to a new location (which updates modification time), or filenames don't follow a date pattern. **The app trusts filename dates over file timestamps**, which is usually correct for fresh-from-camera files. You can manually pick clips in the order you want if needed.

**Merge fails on both engines** — likely a corrupted clip. Try smaller batches to find the bad one, then exclude it.

**Notification doesn't appear** — Android 13+ requires notification permission. Settings → Apps → Cam Compiler → Notifications → Allow.

**"App not installed" when re-installing** — uninstall the previous version first if installation fails.

---

## What's Coming in v3 (when you ask)

- Trim individual clips before merging (in/out points)
- Drag-to-reorder selected clips
- Background music
- Speed-up sections (timelapse for boring stretches)
- Save merged file to a chosen folder (not just Downloads)
- Built-in preview player

For now, v2 is focused 100% on **fast, reliable compilation** — which is what you asked for.
