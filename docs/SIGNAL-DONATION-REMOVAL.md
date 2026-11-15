# Signal donation removal

Collie does not initiate Signal donations or gift purchases. Donation checkout, card and bank-transfer forms, PayPal approval, Stripe 3-D Secure, donation management, profile upsells and donation failure notifications are removed from the app. Donation-only gateway clients, service operations and resources are removed with their callers.

The removed payment WebViews and their shared layout no longer generate the JavaScript/content-access findings. The donation notification PendingIntent is also gone. This is source removal, not a CodeQL suppression; the application scan must verify the final compiled source.

## Shared code that remains

Remote backup purchases still use subscriber creation, subscription status, Google Play purchase-token linking, receipt credentials and the permit issuer. The reduced `lib:donations` module supplies these shared permit and payment-record types; it contains no Stripe HTTP client, card tokenization or payment UI. Removing the module would break backup subscriptions.

Payment type codes, database/protobuf records, account-sync fields and stored values remain compatible with existing installations and backups. This patch does not erase donation history or cancel an existing server subscription. Existing received-gift messages and receipt redemption remain interoperable; the app cannot purchase new gifts.

Collie and Molly's external support links remain separate from Signal checkout.

## Upgrade and verification

Serialized donation-only job names map to `FailingJob.Factory`, which accepts old payloads and fails without executing payment code. Shared renewal and receipt-context jobs ignore donation subscriptions while continuing to process backup subscriptions.

When taking an upstream release, check for donation navigation destinations, manifest activities, setup/renewal scheduling, gateway clients and profile prompts. Reapply the removal to newly introduced donation-only code rather than adding feature flags or CodeQL exceptions.

Before merging, require a successful production/debug build and the full unit suite. Verify settings and profile behavior on API 35, including the retained external support links and backup settings. Confirm the built app has no PayPal/Stripe checkout fragments, donation checkout activity, donation error notification class or donation WebView layout. Run a fresh Java/Kotlin CodeQL scan against that source; shared or unrelated findings must stay visible.
