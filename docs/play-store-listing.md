# Google Play listing for Spendidly

Everything to paste into Play Console for `io.github.gengar93.spendidly`, version 1.0. Character counts were checked against Play's limits. Items marked **Check** are judgement calls to confirm against Play's current help pages before submitting.

## Store listing

### App name (max 30)

```
Spendidly: Expense Tracker
```

26 characters. The longer `Private Expense Tracker` variant no longer fits in 30 with this name. Play's metadata policy rejects names that make claims or rankings, so avoid words like "best" or "#1".

### Short description (max 80)

```
Track spending on your phone. Optional AI chat with your own API key.
```

69 characters.

### Full description (max 4000)

2,318 characters.

```
Spendidly is a simple expense tracker that keeps your data on your phone. There's no account to create, no ads and no analytics. It's free and open source (GPL-3.0).

Log expenses quickly
• Tap +, type the amount, an optional note and the first letters of a category, pressing Enter after each. You never have to leave the keyboard.
• Tap any entry to edit or delete it.

See where it goes
• Summary: today's total, bars for this week, month or year, and this month split into everyday spending and large expenses. Spendidly learns what counts as large from your own entries.
• History: day-by-day cards with search and category filters, plus a full table you can filter, group by day, category or month, and sort.
• Home-screen widgets: Week, Today and Square.

Make it yours
• 12 currencies, including the Iranian toman.
• Your own categories, with optional descriptions, in the order you like.
• Import and export a .spendidly backup or a CSV file.
• Demo data, two months of fictional expenses, if you want to try things out first.

Ask: optional, needs your own API key
Spendidly includes an AI chat that can answer questions like "how much did I spend on food last month?" and add, edit or delete expenses for you. You can also ask out loud.

The AI isn't included. To use it, paste an API key from one of these providers in Settings:
• Anthropic (Claude)
• OpenAI
• Google (Gemini)
• OpenRouter
Usage is billed to you by that provider under its own terms. Voice questions need an OpenAI, Google or OpenRouter key. Everything else in Spendidly works without a key.

Privacy
• Expenses, categories and chats are stored only on your phone.
• The developer collects nothing. Spendidly has no server.
• API keys are encrypted with the Android Keystore and stay on your phone.
• When you use Ask, your question, the chat so far, your categories and the expenses needed to answer are sent directly from your phone to the provider you chose, over HTTPS.
• Voice recordings are made only while you're recording, sent only to your voice provider, and deleted once they're turned into text.
• Settings → Erase everything deletes all your data and keys.

Permissions: microphone (only for voice questions) and internet (only for AI requests and checking keys).

Source code and privacy policy: github.com/gengar93/spendidly
```

### Category and tags

- **App category:** Finance.
- **Tags:** Play Console offers a fixed list (up to 5). Pick the closest matches to: Budgeting, Expense tracker, Personal finance, Money manager, Productivity.

### Contact details

- **Email:** `aditya.janaswamy+spendidly@gmail.com` (required, shown publicly).
- **Website:** `https://github.com/gengar93/spendidly`
- **Phone:** leave blank.
- **Privacy policy URL** (App content → Privacy policy): `https://gengar93.github.io/spendidly/privacy`

## Data safety form

### How Play defines the terms

From Play's Data safety guidance, as of writing (**Check** that it's still worded this way):

- **Collected** means data transmitted off the device by the app. Data that's only stored or processed on the device isn't collected, so the on-device database, chats and keys don't need declaring.
- **Shared** means data transferred to a third party. Transfers the user starts, where they'd reasonably expect the data to go to that third party, are exempt from "sharing".
- Play counts data as collected whether it goes to the developer's server or to someone else's, so the absence of a Spendidly server doesn't by itself mean "no collection".

### Recommended answers

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | AI requests send expense data, chat text and audio off the device. They go to a provider the user picked rather than to the developer, but Play's definition of collection is about leaving the device, not about who receives it. Declaring it is the safer reading; under-declaring is what Play enforces against. **Check:** you could argue "No", since the developer never receives anything, but that is the riskier answer. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | All provider endpoints are HTTPS, and the manifest sets `usesCleartextTraffic="false"`. |
| Do you provide a way for users to request that their data is deleted? | **Yes** | In the app: Settings → Erase everything, plus deleting chats. No accounts exist, so no web deletion form is needed. **Check:** data already sent to a provider can only be deleted with that provider; the privacy policy says so. |

### Data types

Declare as **collected**, **not shared** (the user starts each transfer, to a provider they chose), **optional** (users can use Spendidly without the AI features), purpose **App functionality** only.

| Data type | Declare? | What is sent | Notes |
|---|---|---|---|
| Financial info → **Purchase history** | Yes | Expense dates, amounts, categories and notes the AI looks up to answer, and recent notes as voice spelling hints. | Closest fit for an expense log. **Check:** whether "Other financial info" fits better or should be added. |
| Audio → **Voice or sound recordings** | Yes | Voice questions, sent to the voice provider for transcription. | Recording is deleted on the phone after transcription. |
| App activity → **Other user-generated content** | Yes | Chat messages, earlier replies in that chat, category names and descriptions. | **Check:** "Messages → Other in-app messages" is meant for messages between people, so user-generated content seems the better fit. |
| Personal info (name, email, address and so on) | No | Spendidly never asks for these. | A user could type them into a note or chat. Play generally doesn't expect you to declare free text the user chooses to enter, but **check** this. |
| Location | No | Only today's date and the phone's time zone go into the AI's instructions. | A time zone isn't location data in Play's list. **Check** if you want to be thorough. |
| Device or other IDs, App info and performance, Web browsing, Contacts, Photos and videos, Files and docs, Calendar, Health and fitness | No | Not sent. | No analytics or crash reporting is included. |

**Processed ephemerally?** Answer **No**. Spendidly deletes the recording itself, but the providers may keep what they receive under their own terms, so "ephemeral" isn't something Spendidly can promise. **Check** this.

**API keys:** a key is sent only to its own provider to authenticate. Play has no data type for credentials the user supplies for a third-party service, so it isn't declared. **Check.**

**Report emails:** the Report action opens the user's email app; Spendidly itself transmits nothing, so this isn't collection by the app. **Check.**

## App content

### Privacy policy

`https://gengar93.github.io/spendidly/privacy`

### Ads

**No**, the app doesn't contain ads.

### App access

Choose **All or some functionality in my app is restricted**, because Ask needs an API key. Everything else works without signing in. Add one entry:

- **Name:** AI features (Ask and voice)
- **Username / password:** leave blank (there's no login).
- **Any other information required to access your app:**

```
Spendidly has no accounts. Summary, History, quick add, widgets and Settings work immediately. To fill the app with sample data, open Settings and tap "Load demo data".

The Ask chat and voice questions use the user's own API key for an AI provider. For review, use this key:
REVIEW_KEY_HERE

1. Open Settings → API keys and paste the key. Spendidly checks it with the provider.
2. Open Ask, tap +, and ask "How much did I spend last week?" or "Add 12.50 for lunch today".
3. To try voice, tap the mic in an empty chat box, allow the microphone, speak, then tap stop.
4. To report an AI reply, use the Report action under it.

Questions: aditya.janaswamy+spendidly@gmail.com
```

For `REVIEW_KEY_HERE`, create a dedicated key with a small spending limit, for example an OpenRouter key with a credit cap, which works for both chat and voice. Revoke it after review. Don't reuse your own key.

### Target audience and content

- **Target age groups:** recommend **18 and over** only. Spendidly is a personal finance tool, Anthropic's and Google's Gemini API terms require users to be 18 or over (**check** current terms). Including under-18 groups would also bring in the Families policy requirements. 13+ is defensible for the tracker alone, but not for the AI features.
- **Could your store listing unintentionally appeal to children?** No.

### Content rating (IARC questionnaire)

- **Category:** Reference, News or Educational doesn't fit; choose **All other app types** (utility, productivity, communication or other).
- **Violence, fear, sexuality, language, controlled substances, crude humour:** No. The app has no built-in content of this kind.
- **Gambling or simulated gambling:** No.
- **Users can interact or exchange content with each other:** No. There's no user-to-user communication.
- **Shares the user's current physical location with other users:** No.
- **Allows users to purchase digital goods:** No.
- **Unrestricted internet access (for example a browser):** No.
- **Generative AI:** if the questionnaire asks whether the app generates content with AI, answer **Yes** and describe it as a chat about the user's own expenses using the user's own API key. **Check** the exact wording; the questionnaire changes.

Expected result: Everyone / PEGI 3 or similar.

### Other declarations

- **Financial features:** Spendidly doesn't move money, lend, trade or connect to bank accounts. Choose **My app doesn't provide any financial features** unless the form lists a personal-budgeting option. **Check** the current list.
- **Government app, News app, Health apps, COVID-19:** No / not applicable.
- **Price:** Free. No in-app purchases.

### AI-generated content policy

Play requires apps that generate content with AI to let users report or flag offensive output. Spendidly has a Report action on every AI reply. It opens the user's email app with the reply, the question, the app version and the model name prefilled, addressed to `aditya.janaswamy+spendidly@gmail.com`; nothing is sent automatically. Mention this in the App access note above and in any policy questionnaire that asks.

**Check:** Play's policy says reporting should work "without needing to exit the app". Opening an email app does leave Spendidly. If a reviewer objects, an in-app form that sends the report would be needed, but Spendidly has no server to receive it.

## Graphics

| Asset | Spec | Plan |
|---|---|---|
| App icon | 512 × 512 px, 32-bit PNG with alpha, max 1 MB. Full square; Play applies the rounded mask. | Export from the adaptive icon (`res/mipmap-anydpi/ic_launcher.xml`, foreground over background). In Android Studio: **New → Image Asset → Launcher Icons (Adaptive and Legacy)**, point it at the existing foreground and background drawables, and it writes `ic_launcher-playstore.png` at 512 × 512. Discard the other files it generates. |
| Feature graphic | 1024 × 500 px, JPEG or 24-bit PNG (no alpha), max 15 MB. | The icon and "Spendidly" on the left, a phone showing Summary on the right. Keep important content away from the edges, since it can be cropped. |
| Phone screenshots | 2 to 8. PNG or JPEG, max 8 MB each, 16:9 or 9:16, each side 320 to 3,840 px. Use at least 4 at 1080 px or more to qualify for promotion. | See the list below. |

Screenshots to capture, in this order. Use **Settings → Load demo data** first so no real spending appears, and capture in light theme (optionally one in dark):

1. **Summary**: today's total, the week bars and the everyday vs large split for the month.
2. **History**: day cards with a search or category filter applied.
3. **Expense table**: grouped by category, sorted by total.
4. **Quick add**: the sheet mid-entry, with the category list narrowed.
5. **Ask**: a question such as "How much did I spend on food this month?" with its answer and table.
6. **Ask adding an expense**: "Add 12.50 for lunch" and the confirmation receipt.
7. **Home-screen widgets**: Week, Today and Square on a clean home screen.
8. **Settings**: currency, categories and the API keys section, with no key visible.

## Before you submit

- Make sure the in-app support address (`SUPPORT_EMAIL` in the app code) matches `aditya.janaswamy+spendidly@gmail.com`.
- Publish the privacy policy (GitHub Pages from `main`, `/docs`) and confirm `https://gengar93.github.io/spendidly/privacy` loads.
- New personal developer accounts must run a closed test with at least 12 testers for 14 days before applying for production. **Check** whether this applies to your account.
