# GleeshPlayer

**GleeshPlayer** is a lightweight, native Android video player built with Kotlin and [AndroidX Media3](https://developer.android.com/media/media3).

The app is designed around a straightforward local video library, practical playback controls, subtitle support, and a customizable viewing experience.

## Features

### Video library
- Browse videos by folder or in a combined video list.
- Search and sort the video library.
- View video thumbnails, duration, resolution, and file size.
- See recently played videos and playback progress.
- Rename, share, and delete videos using Android's media permissions.
- Select multiple videos or folders, with Select All, Invert Selection, and Delete Selected actions.

### Playback
- Playback powered by AndroidX Media3 ExoPlayer.
- Playlist navigation with Next and Previous controls.
- Resume playback from the last saved position, with an option to start from the beginning.
- Aspect-ratio options including Fit, Fill, Original, Stretch, standard ratios, cinema ratios, and custom adjustments.
- Remember aspect-ratio preferences.
- Playback gestures and standard playback controls.

### Subtitles
- Detect and display supported embedded subtitle tracks.
- Load external subtitle files.
- Choose available subtitle tracks from the subtitle controls.
- Customize external subtitle appearance and position.
- Display a subtitle indicator in the video library when subtitle availability has been detected or an external subtitle has been associated with a video.

Embedded subtitles use the player's native subtitle rendering. The external-subtitle customization controls are intended for external subtitles.

### Appearance and settings
- Dark and light appearance options.
- Picture-based theme option with transparency and blur controls.
- Playback and library preferences.

## Requirements

- Android 6.0 (API 23) or newer.
- Access to video files through Android media permissions.

## Tech stack

- Kotlin
- AndroidX Media3 ExoPlayer and Media3 UI **1.8.1**
- AndroidX AppCompat and Core KTX
- Material Components
- Gradle with Java 17

## Build from source

1. Clone the repository:

   ```bash
   git clone https://github.com/gleeshyyy/Gleeshyyy-Video-Player.git
   cd Gleeshyyy-Video-Player
   ```

2. Build the debug APK:

   **Linux / macOS**
   ```bash
   ./gradlew assembleDebug
   ```

   **Windows**
   ```bat
   gradlew.bat assembleDebug
   ```

3. Find the APK at:

   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

The project currently uses compile SDK 35, target SDK 35, minimum SDK 23, and Java 17.

## Permissions

GleeshPlayer requests access to video media so it can display and play videos stored on the device. Android may ask the user to grant or select media access depending on the Android version.

## Project

- Repository: https://github.com/gleeshyyy/Gleeshyyy-Video-Player
- Application ID: `com.gleeshyyy.videoplayer`

## License

No license has been specified for this repository yet. Unless a license is added, the source code remains subject to the repository owner's rights; reuse and redistribution are not automatically permitted.
