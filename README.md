# Spendidly

A local-first expense tracker for Android. Your expenses, categories and chats are stored on your phone. There are no accounts, no analytics, no ads and no Spendidly server.

An optional AI chat can answer questions about your spending and add, edit or delete expenses. It uses your own API key and talks directly to that provider.

Free and open source under the [GPL-3.0](LICENSE).

## Screenshots

_Coming soon._

## Features

- **Summary:** today's total, bars for this week, month or year, and this month split into everyday spending and large expenses. The large-expense threshold is learned from your own data.
- **History:** day cards with search and category filters, and a full expense table you can filter, group by day, category or month, and sort. Tap an entry to edit or delete it.
- **Quick add:** tap +, then type the amount, a note and a category, pressing Enter after each, without leaving the keyboard.
- **Ask:** an AI chat that answers questions about your spending and can add, edit or delete expenses. Deletions need your confirmation. Chats are saved on the phone.
- **Voice questions:** tap the mic in Ask and speak in any language.
- **Home-screen widgets:** Week, Today and Square.
- **Settings:** 12 currencies (including the Iranian toman), categories with descriptions that you can reorder, import and export (`.spendidly` backup or CSV), demo data, erase everything, AI model and API keys, and the voice model.

Spendidly runs on Android 8.0 (API 26) or later and follows the system light or dark theme.

## Download

- **GitHub Releases:** coming soon.
- **Google Play:** coming soon.

## Privacy

- Everything you enter is stored in a local database on your phone. Android cloud backup is turned off for Spendidly; use Export to keep a copy.
- The developer collects nothing.
- Nothing leaves your phone unless you use the AI features. Then, the data needed to answer goes directly to the provider you chose, over HTTPS.
- Settings → Erase everything deletes all expenses, categories, chats and keys. Uninstalling also removes everything.

Read the full [privacy policy](https://gengar93.github.io/spendidly/privacy).

## How AI keys work

The AI features are optional, and Spendidly doesn't include or pay for an AI service. To use them, paste an API key in Settings → API keys from one of:

- Anthropic
- OpenAI
- Google (Gemini)
- OpenRouter

Spendidly detects the provider from the key and checks it once with that provider. Keys are encrypted with the Android Keystore and never leave the phone except in requests to their own provider. Usage is billed to your account with that provider.

When you ask something, Spendidly sends the provider your question, the chat so far, your category list and the expense records the AI looks up to answer. Voice questions need an OpenAI, Google or OpenRouter key (through OpenRouter: AssemblyAI, GPT Transcribe, Voxtral or Qwen, with Qwen as the fallback when credit runs short). The recording is made only while you're recording, sent with your category names and recent notes as spelling hints, and deleted after transcription. Each provider's own privacy policy applies to what it receives.

## Building from source

You need JDK 17 or later and the Android SDK (Platform 37 and Build Tools 36.0.0). Open the `android` directory in Android Studio and run the `app` configuration, or build from the command line with `ANDROID_HOME` pointing at the SDK:

```sh
cd android
./gradlew :app:assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

Release builds are shrunk with R8. They're signed with an upload key when these Gradle properties are set, for example in `~/.gradle/gradle.properties`:

```properties
SPENDIDLY_UPLOAD_STORE_FILE=/path/to/upload-keystore.jks
SPENDIDLY_UPLOAD_STORE_PASSWORD=...
SPENDIDLY_UPLOAD_KEY_ALIAS=...
SPENDIDLY_UPLOAD_KEY_PASSWORD=...
```

Without them, the release build falls back to the debug key.

See [android/README.md](android/README.md) for how the app is put together.

## Importing from a CSV

Settings → Import from file reads a `.spendidly` backup or an expenses CSV with the columns `Date,Amount,Category,Note`. To build a `.spendidly` backup that also carries category descriptions:

```sh
python3 scripts/build-android-import.py expenses.csv Spendidly-import.spendidly
```

Edit the category descriptions in the script to match your own categories. Expenses identical to ones already in the app are skipped, so importing twice is safe.

## Licence

Spendidly is licensed under the [GNU General Public License v3.0](LICENSE).
