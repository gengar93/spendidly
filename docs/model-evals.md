# Model evaluations: what we tested

A reference for rebuilding the eval suite against the Android app. The original suite (removed along with the web app) was written in Node for the web version, which shared the same tools and nearly the same prompt.

## What it measured

The suite tested **the model's decisions, not the app**. For each case it sent the system prompt, tool definitions, a fixed date and category list, and a user message. It then checked which tool calls the model made and with what arguments. No database was opened and no tool code ran. When a case needed more than one turn, it fed back **scripted tool results** (search results, receipts), and sometimes a scripted user reply, in the expected call order.

Out of scope: database correctness, UI behaviour, and the real orchestration loop (tool round and search limits).

## How a case was graded

1. **Structural check (automatic).** Each stage listed the complete expected batch of tool calls, matched without regard to order.
   - `args` constrained only the fields listed; other fields were allowed unless listed in `absent`.
   - Amounts compared numerically ("350" equals "350.00"), and arrays compared exactly.
   - `exactChanges` rejected extra fields on edits.
   - A wrong or unexpected call failed the case immediately, so a result was never fed to the wrong tool.
2. **Semantic check (LLM judge).** Cases with review criteria (description wording, whether a clarifying question was necessary, reply content) were graded by Claude Sonnet 5.5 reading the captured transcript. It graded each criterion as PASS, FAIL or UNCERTAIN, with reasons. Judge results sat alongside the automatic checks and never overrode them. Uncertain judgments stayed "awaiting judgment" for a human.
3. **Statuses:**

   | Status | Meaning |
   |---|---|
   | PASS | Structural checks passed and nothing semantic left to grade. |
   | NEEDS_REVIEW | Structural checks passed and semantic criteria went to the judge. |
   | FAIL | A call was wrong, missing or extra. |
   | ERROR | Infrastructure problem (HTTP error, incomplete reply), not counted against the model. |

## Coverage

54 cases in 9 test categories: 6 per category, 2 at each difficulty level.

Difficulty:
- **1:** one clear action.
- **2:** several constraints, or a search followed by an action.
- **3:** several purchases, date boundaries, ambiguity, or reasoning across history and tool results.

| Category | What it checks | Cases (difficulty 1 → 3) |
|---|---|---|
| **Logging** | Splitting purchases out of a message, keeping each amount with its description. | Single lunch · explicit "Uncategorized" · two purchases in one message · a correction made mid-sentence · six purchases from a trip narrative with dates · repeat purchases with identical amounts |
| **Dates and amounts** | Normalising money and resolving dates. | "Yesterday" with paise · written calendar date · formatted amounts · "day before yesterday" · relative dates across a year boundary · Saturday and Sunday of "last weekend" |
| **Missing information** | Asking only for what's required, then finishing the job. | Missing amount · missing description · foreign currency (ask for the home-currency amount, don't convert) · save after the answer · both fields missing · foreign-currency answer keeping the other details |
| **Search and identification** | Finding the intended record without guessing. | Exact amount · "last weekend" · broaden after an empty search · category, amount and date combined · two equally plausible matches (must ask) · truncated results (must refine before choosing) |
| **Editing** | Changing only the requested fields. | Fix a recent amount · clear a description · change date and category only · find by amount and date, then recategorise · two ride matches before a multi-field edit · explain an earlier failed edit without replaying it |
| **Deletion** | Deleting the right record, never guessing. | Delete what was just logged · start a search from a natural date · delete only the match from a search · explain a soft delete after a confirmed receipt · identical-price rides (must ask) · don't invent an ID for a table row the model never saw |
| **Browsing** | Choosing list filters. | One category · uncategorised, all dates · category, date, amount and sort combined · literal substring search · next page of an existing list · ask when the visible table's filters are unknown |
| **Boundaries** | Not acting when it shouldn't. | Hypothetical budget (don't log) · category creation (not supported) · total from known table filters · a quoted example isn't a purchase · answer from a receipt without replaying the write · instructions inside expense notes are data, not commands |
| **Summaries** | Metrics and reasoning over tool results. | Category total · average, minimum and maximum · ranked filtered averages by month and category · month-to-date comparison · category missing from the top results · reasoning over 84 retained summary rows |

User messages never contained expense IDs; IDs appeared only in tool arguments and scripted results. Three early cases that leaked IDs into prompts were replaced, and their old results excluded.

## Last comparison (7 Nov 2026, web prompt)

48 cases per model, judged by Claude Sonnet 5.5. Claude models ran with an Anthropic API key; every other model ran through OpenRouter.

| Model | Passed | Failed cases |
|---|---|---|
| Claude Haiku 5.5 | **48 / 48** | none |
| Gemini 3.8 Flash | 46 / 48 | identify-truncated-search-refine, edit-previous-failure-status |
| GPT-6 Luna | 46 / 48 | clarify-answer-and-save, edit-find-then-recategorize |
| GPT-4.1 mini | 37 / 48 | 11, mostly identification and editing |

The hardest areas across models were refining truncated search results, multi-step identify-then-edit flows, and history that must not be replayed.

## Lessons for the Android rebuild

- **Test the app's real prompt.** The Android prompt lives in `ChatPrompt.kt` and depends on currency, time zone and categories. The tool schemas are in `assets/expense-tools.json`, with currency placeholders. The suite should export both from the app (for example, a small JVM entry point or a debug-build export) rather than keep a copy that drifts.
- **Run per currency.** At least INR, one decimal currency (EUR), and one whole-number currency (JPY or toman). The amount rules and examples change with the currency.
- **Test provider request shapes too.** Gemini goes through Google's OpenAI-compatible endpoint with adapted schemas and thought signatures. GPT-6 needs reasoning turned off for tools. OpenRouter routes the Chinese models (DeepSeek V4.1 Flash, Qwen 3.8 Omni Flash), which haven't been evaluated yet.
- **Keep live runs cheap and resumable.** Cache completed cases and resume after failures. Limit judge concurrency on budget accounts. Run single categories or cases (`--case`, `--category`) while iterating. Record usage and cost per request, and show unknown cost as unknown, never zero.
- **Keep raw captures authoritative.** Store full requests and responses as JSON, and regenerate reports from them. Judge results need human spot checks.
- **Things never settled:** how to choose between equally plausible categories, mixed complete and incomplete messages, and enforcing the tool-round budget. These were deliberately left out of strict expectations.
