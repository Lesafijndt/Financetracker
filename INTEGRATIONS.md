# Transaction integrations

## Google Wallet

This app can only make a best-effort attempt to read Google Wallet notifications through Android's notification listener. Google Pay's public Android API is a checkout API for merchants; it does not expose a consumer's Google Wallet payment history to this app. Notification capture can miss, duplicate, or misread payments, so review captured transactions against the bank statement.

## Currency display

Choose MDL, EUR, or USD from the currency chips; MDL is the default. The transaction list and summaries show the selected currency, and amounts in different currencies are not combined or converted using a fixed rate. Budget limits are saved separately per currency.

## MICB Mobile Banking and OTP Mobile Banking Moldova

The app listens for visible transaction notifications from the Android packages `md.micb.mwb.prod` and `md.mobiasbanca.otpdirekt`. Enable transaction push notifications in each banking app and grant Android notification access to Pulse Wallet. This is notification parsing, not an authenticated transaction feed, and the bank can change notification text at any time.

When two sources report the same currency, amount, normalized merchant, compatible card suffix, and a timestamp within three minutes, the app stores one transaction and remembers the matching sources and notification IDs. Repeated callbacks for a remembered notification are ignored. This reduces duplicates; it cannot prove two alerts describe the same purchase. Two genuine purchases with identical details close together can be merged, while differently worded merchant names or different amounts can still produce duplicates.

An own-account transfer is ignored only when the notification explicitly identifies it as a transfer between the user's own accounts/cards or to their own account. If the alert omits that information, the app cannot reliably know who owns the destination. Incoming deposits and alerts marked pending, failed, declined, or cancelled are excluded from spending totals. Verify captured history against bank statements before relying on totals.

## Moldindconbank and OTP Bank Moldova

Both banks publish Open Banking / PSD2 APIs for third-party providers and sandboxes. Their onboarding is for registered/certified TPPs. This standalone app does not have TPP registration, bank-issued production access, or a secure consent/backend flow, so it does not connect directly to either bank. Do not collect or store internet banking passwords as a substitute.

To build these bank connections legitimately, first obtain the required TPP authorization and bank onboarding, then implement the banks' documented account-information and consent flows using a secure backend and the bank sandboxes. Only enable production sync after certification and bank approval.
