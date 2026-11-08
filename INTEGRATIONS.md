# Transaction integrations

## Google Wallet

This app can only make a best-effort attempt to read Google Wallet notifications through Android's notification listener. Google Pay's public Android API is a checkout API for merchants; it does not expose a consumer's Google Wallet payment history to this app. Notification capture can miss, duplicate, or misread payments, so review captured transactions against the bank statement.

## Currency display

Choose MDL, EUR, or USD from the currency chips; MDL is the default. The transaction list and summaries show the selected currency, and amounts in different currencies are not combined or converted using a fixed rate. Budget limits are saved separately per currency.

## MICB Mobile Banking and OTP Mobile Banking Moldova

The app listens for visible transaction notifications from the Android packages `md.micb.mwb.prod` and `md.mobiasbanca.otpdirekt`. Enable transaction push notifications in each banking app and grant Android notification access to Pulse Wallet. This is notification parsing, not an authenticated transaction feed, and the bank can change notification text at any time.

## Capture accuracy and review

Clear purchase alerts with one exact supported-currency amount and an identifiable merchant are recorded automatically. The parser removes amounts explicitly labelled as balances, handles decimal/grouping conventions without floating-point rounding, and does not pick the first amount when a notification contains fees, conversion amounts, or several transactions. It prefers Android's expanded text over the collapsed copy and skips notification group summaries. Only the known banking/Wallet app packages are trusted.

Ambiguous amounts, missing merchants, unclear transaction direction, and possible duplicates go to **Needs review**. They are visible in the selected currency, show the original notification in the review dialog, and are excluded from every spending/income summary and budget until confirmed. If no amount is reliable, the amount field is left empty. Review state is included in CSV exports; only `CONFIRMED` entries belong in financial totals. Notification text stays encrypted on the device.

Explicit own-account/card transfers in English, Romanian, and Russian are ignored. Every other detected transfer is held for review, because a recipient name or amount cannot establish ownership. Choose **This is my own transfer — ignore** to hide it permanently, or **Confirm external transfer** for money sent to someone else. The same ignore action is available on other captured alerts if the bank omitted the transfer label. Incoming deposits/credits and pending, failed, declined, or cancelled alerts are not automatically counted as spending. Refunds are handled as credits.

A Wallet and bank alert with the same amount, currency, merchant identity, and a timestamp within three minutes can be paired once. Wallet's virtual card suffix may differ from the bank's physical card suffix, so suffix mismatch alone does not produce a second expense. Two bank sources are never merged, and several possible matches or inconsistent merchant labels require review. Separate same-source purchase events are preserved; a reused notification key with identical purchase details is reviewed rather than silently discarded or counted twice.

Persistent event receipts remember both sides of a matched purchase. Replaying, reconnecting, rescanning, ignoring, or deleting an already captured event does not recreate it. If an event changes its amount/currency/type/merchant, the saved entry is marked for review instead of adding another purchase. Explicit user confirmations/corrections survive automatic recategorization. A factory reset intentionally clears receipts along with all history.

Database version 5 preserves existing version 3/4 history and rules. Previously automatic transfers become review items on upgrade; manual/user-edited transactions keep their chosen status. Older incorrectly captured purchase amounts cannot be repaired safely without their bank evidence: inspect and correct or ignore those entries. No history is silently deleted or repriced.

These safeguards cannot make notification capture a complete bank ledger. Missing/hidden alerts, unspecified destinations, delayed alerts, and bank format changes still require review against a bank statement. The app does not learn account ownership from names or matched amounts and does not claim direct bank access.

## Kiwi design

The existing purchases, summary/categories, and tools screens use a forest-and-sage palette, an original lightweight vector kiwi mascot, and an adaptive kiwi launcher icon (including Android themed icons). MDL remains the default, with EUR and USD selectable independently.

## Moldindconbank and OTP Bank Moldova

Both banks publish Open Banking / PSD2 APIs for third-party providers and sandboxes. Their onboarding is for registered/certified TPPs. This standalone app does not have TPP registration, bank-issued production access, or a secure consent/backend flow, so it does not connect directly to either bank. Do not collect or store internet banking passwords as a substitute.

To build these bank connections legitimately, first obtain the required TPP authorization and bank onboarding, then implement the banks' documented account-information and consent flows using a secure backend and the bank sandboxes. Only enable production sync after certification and bank approval.
