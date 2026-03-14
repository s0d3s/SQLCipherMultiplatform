# SQLCipher source location

This folder is intentionally kept out of the main code so you can vendor SQLCipher source here.

For the current CMake setup, put the SQLCipher amalgamation files in this directory:

- `sqlite3.c`
- `sqlite3.h`

Then build native bridge from project root:

- `gradlew :native-bridge:buildNative`

> Note: this MVP is focused on Windows x64 first. Linux/macOS build tuning is planned next.
