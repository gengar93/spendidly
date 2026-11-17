# Tally for Android

Open the `android` directory in Android Studio, install Android SDK Platform 37 and SDK Build Tools 36.0.0, and run the `app` configuration on a device or emulator with Android 8.0 (API 26) or later. The app follows the system light or dark theme.

For a command-line build, use JDK 17 or later and point `ANDROID_HOME` at the Android SDK:

```sh
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Release builds

The app ID is `io.github.gengar93.tally`. For Google Play, build the bundle:

```sh
./gradlew :app:bundleRelease
```

It is written to `app/build/outputs/bundle/release/app-release.aab`, shrunk with R8, with the R8 mapping included for Play's crash reports. `:app:assembleRelease` builds the same thing as an APK.

Release builds are signed with the Play upload key when these Gradle properties are set, normally in `~/.gradle/gradle.properties` so they stay out of the repo:

```properties
TALLY_UPLOAD_STORE_FILE=/path/to/upload-keystore.jks
TALLY_UPLOAD_STORE_PASSWORD=...
TALLY_UPLOAD_KEY_ALIAS=upload
TALLY_UPLOAD_KEY_PASSWORD=...
```

Without them, the release build is signed with the debug key and Gradle prints a warning, so anyone can still build and install it.

The support address for reported AI replies and the privacy policy link are in `Support.kt`.

The Android backend is available on `TallyApplication.backend`. Use `expenses` for local category and expense operations, `providers` for saved model settings, and `chat` for the current in-memory conversation. Call synchronous expense operations from a worker thread.

Pass a pending deletion's token to `chat.resolveDeletion` after the user makes a choice. Chat outcomes contain the current turn's complete set of receipts and table descriptors. After confirmation of a full reset, call `resetAllData` to clear expenses, categories, saved keys, and the current chat, then restore the starter categories.

API keys are encrypted with the Android Keystore. The AI's system prompt is built in `backend/ChatPrompt.kt` and its tools are defined in `assets/expense-tools.json`, with placeholders filled in for the chosen currency.

## Screens

- **Summary**: today's total and category mix, this week's bars (hold a bar to ask about that day), and the month split into everyday spending and large expenses. The large-expense line is learned from your data: about your top 5% over the last 90 days, once there are 30 entries.
- **History**: day cards with search and category filters. Tap an entry to edit or delete it.
- **Ask**: a list of saved chats. The + button starts a new chat in a full-screen view; tap a chat to continue it, with the AI keeping that conversation's context. Chats are stored on the phone and removed by Erase everything. To ask out loud, tap the mic in the chat box (it shows while the box is empty), then tap stop: Tally transcribes the recording in the language spoken and sends it as your message. Recordings stop after a minute, go only to the voice model's provider and are deleted once transcribed. Category names and recent notes are sent along as spelling hints. With voice set up, the widgets' chat button becomes a mic that opens a new chat and starts recording. Each reply has a small Report link: after a short explanation it opens the email app with a draft to support holding the reply, the question, the app version and the model; nothing is sent until the user sends it.
- **Settings**: currency, model and API keys (the provider is detected from the key, which is checked once when added), the voice model (GPT Transcribe with an OpenAI key, Gemini 3.8 Flash with a Google key, Gemini 3.8 Flash or Qwen 3.8 Omni Flash with an OpenRouter key; Anthropic has no audio input), categories with descriptions (drag to reorder), import and export (a `.tally` backup or CSV), demo data (two months of fictional expenses ending today, for showing the app to others), erase everything, and the privacy policy link and app version.
- **Quick add** (the + button): type the amount, press Enter, type an optional note, press Enter, then type to narrow the categories and press Enter to save the first match, or tap a category.

A fresh install has no expenses and eight starter categories (with descriptions in India only), and its currency comes from the phone's region. Changing the currency relabels amounts without converting them. Erase everything leaves the app empty, with no categories.

## Importing data

Settings → Import from file reads a `.tally` backup (older `.lifelog` files work too): a zip holding `expenses.csv` (columns `Date,Amount,Category,Note`) and `categories.json` (`[{"name", "description"}]`). Categories are matched by name and their descriptions are replaced; unknown CSV categories are created. Expenses identical to existing ones are skipped, so importing twice is safe.

Build one from a CSV, with the category descriptions listed in the script:

```sh
python3 scripts/build-android-import.py expenses.csv Tally-import.tally
```
