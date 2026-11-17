---
title: Tally privacy policy
permalink: /privacy
---

# Tally privacy policy

**Effective date:** 17 November 2026

Tally is a free, open-source expense tracker for Android (app ID `io.github.gengar93.tally`). This policy explains what happens to your information when you use it. The source code is public at [github.com/gengar93/tally](https://github.com/gengar93/tally), so you can check everything described here.

## The short version

- Your expenses, categories and chats are stored only on your phone.
- The developer of Tally collects nothing. There are no accounts, no analytics, no ads, no crash reporting and no Tally server.
- The AI features are optional. They work only if you add your own API key from an AI provider. When you use them, Tally sends what's needed to answer your request directly from your phone to that provider, and nowhere else.
- You can erase everything from inside the app at any time.

## What the developer collects

Nothing. Tally has no server, and the app contains no analytics, advertising or tracking code. The developer never receives your expenses, chats, recordings, API keys or any information about how you use the app.

The only way the developer hears from you is if you choose to email, for example with the Report action described below.

## What is stored on your phone

Tally keeps the following in its private app storage, which other apps can't read:

- **Expenses:** date, amount, category and an optional note.
- **Categories:** names, descriptions and their order.
- **Chats** from the Ask screen, including the AI's replies.
- **Settings:** your currency, chosen AI model and voice model, and similar preferences.
- **API keys** you add. Keys are encrypted with a key held by the Android Keystore on your phone, and are never sent anywhere except to the provider they belong to.

Tally turns off Android's automatic cloud backup, so this data isn't copied to your Google account. If you want a copy, use Settings → Export backup (a `.tally` file) or Export CSV, and save the file wherever you choose. What happens to that file afterwards is up to you and wherever you save it.

Home-screen widgets show your spending totals, so anyone who can see your home screen can see them too.

## What is sent to AI providers, and when

The AI features (the Ask chat and voice questions) only work after you paste in an API key from Anthropic, OpenAI, Google (Gemini) or OpenRouter. Without a key, Tally makes no network requests at all.

Requests go **directly from your phone to the provider you chose**, over an encrypted HTTPS connection, using your key. Tally has no server in between, and the developer can't see what is sent.

**When you add a key,** Tally sends it once to that provider to check that it works and to see which models it can use.

**When you ask something in the Ask chat,** Tally sends the provider:

- your question and the earlier messages in that chat, including the AI's replies;
- your category names and descriptions, your currency, and today's date and time zone;
- the expense records the AI looks up to answer you (dates, amounts, categories and notes). A broad question, such as "how much did I spend this year?", can involve many of your expenses.

The AI can add, edit or delete expenses when you ask it to. It acts on the copy on your phone, and deletions need your confirmation.

**When you ask a question out loud,** Tally records audio only between your tap on the mic and your tap on stop (at most one minute). The recording is sent to the provider of the voice model you picked in Settings (OpenAI, Google, or OpenRouter with Gemini or Qwen) to be turned into text. Your category names and up to 50 recent expense notes are sent with it as spelling hints. The recording is deleted from your phone as soon as transcription finishes or fails. The resulting text is then sent as a normal chat message, as above.

**Once data reaches a provider, that provider's own terms and privacy policy apply**, including how long it keeps the data and whether it may use it to improve its services. Some free tiers allow this, so check the terms that apply to your key. OpenRouter passes requests on to the company that runs the model you picked, whose policy also applies.

- Anthropic: [Privacy policy](https://www.anthropic.com/legal/privacy)
- OpenAI: [Privacy policy](https://openai.com/policies/privacy-policy/)
- Google: [Gemini API additional terms](https://ai.google.dev/gemini-api/terms) and [Google privacy policy](https://policies.google.com/privacy)
- OpenRouter: [Privacy policy](https://openrouter.ai/privacy)

## Reporting an AI reply

Each AI reply has a Report action. It opens your own email app with a message to the developer, prefilled with that reply, the question that led to it, the app version and the AI model's name. Nothing is sent unless you choose to send it. If you do, the developer receives that email, including your email address, and uses it only to look into the report. You can edit or remove anything in the message before sending.

## Permissions

- **Microphone:** used only for voice questions, only while you're recording. Tally asks for it the first time you tap the mic. You can say no and use everything else.
- **Internet:** used only to reach the AI provider you chose, to check keys and answer your requests.

Tally doesn't ask for your location, contacts, photos, files or any other permission. Import and export use Android's file picker, so Tally only sees the file you pick.

## Keeping and deleting your data

Your data stays on your phone until you delete it.

- **Settings → Erase everything** removes all expenses, categories, chats and saved API keys. Your currency choice is kept.
- **Individual chats** can be deleted from the Ask screen, and Settings can delete chats automatically after a week without new messages.
- **Uninstalling Tally** removes all of its data from your phone.

Deleting data in Tally doesn't delete copies a provider already received. To remove those, use that provider's own tools or contact it.

## Children

Tally is not directed at children, and the developer doesn't knowingly collect information from anyone, including children.

## Changes to this policy

If this policy changes, the new version will be posted at this address with a new effective date. Significant changes will also be noted in the app's release notes. The history of this page is public in the [GitHub repository](https://github.com/gengar93/tally).

## Contact

Questions about this policy: [aditya.janaswamy+tally@gmail.com](mailto:aditya.janaswamy+tally@gmail.com)
