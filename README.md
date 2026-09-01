# Euro Pesa SMS Gateway

Euro Pesa SMS Gateway is a private Android SMS gateway APK for platforms that need to send operational SMS notifications through a phone SIM without depending entirely on a third-party Android SMS relay service.

The app is built for the Android package:

```text
com.europesa.smsgateway
```

It is intended to be installed privately by sideloading an APK onto the platform owner's Android phone. It is not designed for Google Play distribution.

## Why This Project Exists

The platforms previously depended on Telerivet to send SMS notifications through an Android phone route. That approach worked, but it created two operational problems.

### 1. Plan Limits

Telerivet can stop sending messages when the account reaches a daily or monthly plan limit. When that happens, important platform notifications can stop until the plan is upgraded or the limit resets.

For platforms where SMS is used to notify marketers about events such as withdrawals, top-ups, account changes, or trading-balance adjustments, that is a real operational risk. The platform may create the notification correctly, but the SMS still does not leave because the external plan has reached its cap.

### 2. Phone Queue Delays

Some messages are accepted by Telerivet but remain queued for several minutes before reaching the recipient. The delay appears to come from the Android phone route, background sync behavior, network state, battery restrictions, or the relay app's scheduling, not from the platform creating the message late.

That makes the user experience inconsistent. A withdrawal or top-up event can complete immediately in the platform, but the marketer may receive the SMS several minutes later.

### Why Not Immediately Use Celcom, Africa's Talking, Or Another Paid SMS Provider?

Carrier-grade SMS APIs such as Celcom, Africa's Talking, or similar providers are a better long-term option for scale, sender IDs, reporting, and uptime. However, they may require:

- Business registration documents
- Sender ID approval
- Account verification
- Paid SMS credits
- Compliance checks
- Setup time before production use

The user does not currently have all required business-registration documentation. This gateway therefore provides a private owner-controlled route that can be used now, while still allowing a paid SMS provider to be added later.

## Proposed Solution

Instead of calling Telerivet directly for every SMS, each web platform creates an outgoing SMS job in its own backend database. The Android APK then wakes up, authenticates to the backend, fetches pending jobs, sends them from the phone SIM using Android's `SmsManager`, and reports the result back to the backend.

The phone is only the sender. The backend remains responsible for queue ownership, safe claiming, retries, idempotency, and admin reporting.

## Where This Can Be Used

This project is useful for privately operated platforms that need SMS notifications but are not yet ready to use a commercial SMS API provider.

Expected platforms include:

- Tradenova
- Nexamarket
- Future Euro Pesa-managed domains
- Any future platform that implements the same SMS gateway backend contract

The APK is not hardcoded to one platform. One installed app can serve multiple backend connections at the same time. Each connection has its own base URL, device ID, API key, device secret, enabled flag, FCM registration status, and optional SIM/subscription ID.

## Important Scope

This project is an Android sender gateway. It does not create withdrawal records, top-up records, account records, or trading-balance records. It only fetches SMS jobs that the backend has already queued.

Each platform backend remains responsible for:

- Creating `SmsOutbox` records
- Claiming queued SMS jobs safely
- Preventing duplicate job creation
- Retrying failed jobs
- Marking dead-letter jobs
- Displaying admin SMS logs
- Sending Firebase Cloud Messaging wake-up pushes
- Managing device credentials

## High-Level Flow

1. A marketer withdraws money, receives a top-up, or an admin adjusts a marketer's trading balance.
2. The platform generates the SMS text exactly as it does today.
3. Instead of calling Telerivet directly, the platform creates a pending record in an `SmsOutbox` table.
4. The backend sends a Firebase Cloud Messaging data push to the Android gateway app.
5. The push contains only a wake-up signal, not the SMS content.
6. The Android app wakes up and calls `/api/sms-gateway/pending`.
7. The backend returns one or more pending SMS jobs.
8. The Android app sends each SMS using the phone SIM through Android `SmsManager`.
9. The app waits for Android sent callbacks.
10. The app reports each job result to `/api/sms-gateway/status`.
11. The backend marks the job as `sent` or `failed`.
12. The admin dashboard can show pending, sent, failed, and dead-letter SMS logs.

## Provider-Router Architecture

The recommended backend architecture is a provider abstraction. Business logic should not know whether a message is going through Telerivet, the Android gateway, Celcom, Africa's Talking, or a future provider.

Recommended shape:

```text
Notification event
  -> create SmsOutbox record
  -> SMS provider router
      -> Telerivet, if enabled and available
      -> Custom Android gateway
      -> future Celcom/Africa's Talking provider
```

This keeps the withdrawal, top-up, and account-adjustment code stable. The platform can change SMS providers later without rewriting business workflows.

## Android App Capabilities

The APK currently supports:

- Private Android package `com.europesa.smsgateway`
- App display name `Euro Pesa SMS Gateway`
- Multiple backend connections
- Tradenova and Nexamarket quick presets
- Unlimited future HTTPS backend domains
- Per-connection API key and device secret
- Android Keystore encryption for stored API keys and device secrets
- Firebase Cloud Messaging token registration
- FCM data-message wake-up for immediate sync
- Expedited WorkManager sync after FCM wake-up
- 15-minute WorkManager polling as fallback
- Foreground gateway service while active
- Manual "Sync now" button
- Gateway active toggle
- Per-connection enabled/disabled toggle
- Optional SIM/subscription ID per connection
- Device health reporting
- Multipart SMS support
- Sent callback tracking before reporting success
- Short error reporting when Android rejects SMS sending
- Recent local job log with masked recipients

## Backend Connection Fields

Each backend connection in the app stores:

- Display name, for example `Tradenova`
- Base URL, for example `https://tradenovadigital.com/`
- Device ID, for example `tiko-phone-01`
- API key
- Device secret
- Enabled or disabled state
- Optional SIM subscription ID
- Last sync time
- Last error
- FCM registered status
- Pending count, when the backend provides it
- Recent sent count
- Recent failed count

The API key and device secret are encrypted before being stored on the phone.

## Expected Backend Base URLs

Examples:

```text
https://tradenovadigital.com/
https://nexamarketdigital.com/
```

The APK validates base URLs. HTTPS is required for normal builds. Insecure HTTP should only be used during debug/development builds.

## Required Backend Endpoints

Every platform backend that wants to use this APK must implement the same endpoint contract:

```text
GET  /api/sms-gateway/health
GET  /api/sms-gateway/pending
POST /api/sms-gateway/register-token
POST /api/sms-gateway/status
```

`/health` is recommended for status display, but `/pending`, `/register-token`, and `/status` are required for normal operation.

## Authentication And Signing

Every request from the APK to a backend must include:

```text
Authorization: Bearer {apiKey}
X-Timestamp: {current_epoch_milliseconds}
X-Signature: {hmac_sha256_hex}
```

The HMAC secret is the connection's `deviceSecret`.

Signature rules:

- For GET requests, sign the timestamp string only.
- For POST requests, sign `exactRawJsonBody + timestamp`.
- The signature must be lowercase hexadecimal.
- The backend must use the exact same signing rule.

The timestamp should be rejected by the backend if it is too old or too far in the future. A common allowance is 60 seconds.

## Firebase Cloud Messaging

Firebase Cloud Messaging is used only as a wake-up signal. FCM must not carry the SMS body, recipient phone number, withdrawal details, top-up details, or any sensitive business information.

Expected FCM data payload:

```json
{
  "event": "sms_pending",
  "queued_at": "ISO_DATE_STRING"
}
```

Optional routing fields may be included:

```json
{
  "event": "sms_pending",
  "connection_id": "connection-id-from-app",
  "queued_at": "ISO_DATE_STRING"
}
```

or:

```json
{
  "event": "sms_pending",
  "platform": "Tradenova",
  "queued_at": "ISO_DATE_STRING"
}
```

If no specific connection is provided, the app syncs all enabled backend connections.

## FCM Runtime Behavior

The APK handles FCM as follows:

1. On app start, it requests the Firebase token with `FirebaseMessaging.getInstance().token`.
2. The token is saved locally.
3. The token is registered with every enabled backend connection.
4. When Firebase refreshes the token, the app re-registers it with every enabled backend connection.
5. When an FCM data message arrives with `event=sms_pending`, the app triggers immediate sync.
6. The app also enqueues an expedited one-shot WorkManager job as a durable background path.
7. If Gateway Active is enabled, the app starts or uses the foreground gateway service.
8. The app calls `/pending`, sends jobs, then reports results to `/status`.

The 15-minute periodic WorkManager poll remains as a fallback only. Normal delivery should be triggered by FCM.

## Firebase Files

There are two different Firebase JSON files, and they must not be confused.

### Android `google-services.json`

This file is for the APK build. It must be created from a Firebase Android app registered with package:

```text
com.europesa.smsgateway
```

For local Android builds, place it at:

```text
app/google-services.json
```

For GitHub Actions builds, store the full JSON file contents in the GitHub Actions secret:

```text
GOOGLE_SERVICES_JSON
```

The workflow writes this secret to `app/google-services.json` during CI before running Gradle. The file is ignored by git and should not be committed.

### Firebase Admin SDK Service Account JSON

The Admin SDK service account JSON is for backend servers only. It lets Tradenova, Nexamarket, or another backend send FCM wake-up pushes to the APK.

Do not:

- Put the Admin SDK JSON in the Android app
- Commit it to this repository
- Add it to the Android APK build workflow
- Ship it inside an APK

It contains a private key and must be treated as a backend secret.

## Pending Jobs API

The APK calls:

```text
GET /api/sms-gateway/pending
```

Expected response:

```json
{
  "jobs": [
    {
      "id": "123",
      "recipient": "+254700000000",
      "message": "Your withdrawal has been processed. - Platform Name",
      "idempotency_key": "unique-key"
    }
  ],
  "poll_interval_hint_seconds": 15,
  "pending_count": 0
}
```

The backend may return string or numeric IDs, but the APK treats job IDs as strings. This is intentional because different platforms may use different ID formats.

## Status Report API

After sending, the APK calls:

```text
POST /api/sms-gateway/status
```

Expected request:

```json
{
  "device_id": "tiko-phone-01",
  "results": [
    {
      "id": "123",
      "status": "sent",
      "error": null,
      "sent_at": "2026-09-01T12:00:00Z"
    }
  ],
  "device_health": {
    "battery_pct": 80,
    "network_type": "WIFI",
    "sim_present": true
  }
}
```

For failed SMS sends, `status` is `failed`, `sent_at` is `null`, and `error` contains a short reason.

## Register Token API

The APK calls:

```text
POST /api/sms-gateway/register-token
```

Expected request:

```json
{
  "device_id": "tiko-phone-01",
  "fcm_token": "firebase-registration-token"
}
```

Each backend stores the token for that gateway device. When the backend creates a new SMS job, it can send a high-priority FCM data message to that token.

## Health API

Recommended endpoint:

```text
GET /api/sms-gateway/health
```

Example response:

```json
{
  "ok": true,
  "pending_count": 2
}
```

The APK can use this to show connection status and pending count, but SMS delivery must not depend on `/health`. `/pending` remains the authoritative queue-fetch endpoint.

## Multi-Backend Queue Safety

The local APK logic treats each SMS job by compound identity:

```text
connection_id + backend_job_id
```

This prevents collisions where two independent platforms both return a job with the same ID, for example:

```text
Tradenova job 123
Nexamarket job 123
```

Those are different jobs because they came from different backend connections.

## SMS Sending Behavior

The APK sends SMS through Android `SmsManager`.

Behavior:

- Jobs are processed sequentially.
- Multipart messages are supported.
- A job is not reported as `sent` until all Android sent callbacks succeed.
- If any SMS part fails, the whole job is reported as `failed`.
- If a selected SIM/subscription ID is configured for the connection, that SIM is used.
- If no SIM/subscription ID is configured, Android's default SMS subscription is used.

Captured Android send outcomes include:

- `RESULT_OK`
- `RESULT_ERROR_GENERIC_FAILURE`
- `RESULT_ERROR_NO_SERVICE`
- `RESULT_ERROR_RADIO_OFF`
- Unknown result codes

## Android Permissions

The APK requires:

```text
SEND_SMS
INTERNET
ACCESS_NETWORK_STATE
FOREGROUND_SERVICE
FOREGROUND_SERVICE_DATA_SYNC
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
RECEIVE_BOOT_COMPLETED
POST_NOTIFICATIONS
```

On newer Android versions, sideloaded apps may show a warning that SMS permissions can put personal and financial information at risk. That is Android protecting sensitive permissions.

For private testing, the phone owner may need to:

1. Open Android Settings.
2. Go to Apps.
3. Open Euro Pesa SMS Gateway.
4. Use the three-dot menu.
5. Choose Allow restricted settings.
6. Go back to Permissions.
7. Allow SMS permission.

The app needs SMS sending permission only. It should not need permission to read personal SMS messages.

## Battery And Background Restrictions

Android aggressively limits background work, especially for sideloaded apps and on some manufacturer builds.

For reliable operation:

- Keep Gateway Active enabled in the app.
- Allow notification permission so the foreground service can show a persistent notification.
- Disable battery optimization for the APK.
- Keep the phone powered on.
- Keep the phone connected to mobile network or Wi-Fi.
- Ensure the SIM has airtime or SMS bundle.
- Avoid putting the app into deep sleep, app standby, or restricted battery mode.

FCM high-priority data messages are the primary wake-up trigger. Periodic polling exists only as a fallback.

## Backend `SmsOutbox` Requirements

Each platform should maintain its own independent SMS outbox table. Tradenova and Nexamarket should not share one database unless the wider platform architecture explicitly requires that.

Recommended fields:

- `id`
- `idempotency_key`
- `recipient_msisdn`
- `message_body`
- `event_type`
- `reference_id`
- `status`
- `assigned_provider`
- `claimed_by_device`
- `claimed_at`
- `claim_expires_at`
- `attempt_count`
- `max_attempts`
- `last_error`
- `sent_at`
- `created_at`
- `updated_at`

Recommended statuses:

```text
pending
claimed
sent
failed
dead_letter
```

The backend should claim jobs atomically so two gateway devices or two overlapping sync requests do not send the same SMS twice.

For PostgreSQL, the claim query should use row locking such as:

```sql
FOR UPDATE SKIP LOCKED
```

The backend should also use idempotency keys so retrying the business operation does not create duplicate SMS records.

## Retry And Dead Letter Behavior

The Android app reports what happened on the phone. The backend decides what to do next.

Recommended backend behavior:

- Retry temporary failures with backoff.
- Stop retrying after `max_attempts`.
- Move permanently failed jobs to `dead_letter`.
- Alert the admin when jobs enter `dead_letter`.
- Allow fallback to another provider if configured.

Example fallback order:

```text
1. Custom Android gateway
2. Telerivet
3. Celcom or Africa's Talking
```

or:

```text
1. Telerivet
2. Custom Android gateway
3. Celcom or Africa's Talking
```

The best order depends on cost, reliability, verification status, and operational needs.

## Security Notes

The APK is private and powerful because it can send SMS from the owner's SIM. Treat it as infrastructure, not as a public consumer app.

Security rules:

- Do not commit API keys.
- Do not commit device secrets.
- Do not commit Firebase Admin SDK service account JSON.
- Do not ship backend private keys inside the APK.
- Use HTTPS backend URLs.
- Store backend credentials only through the app UI.
- Keep API keys and device secrets unique per backend.
- Rotate credentials if a phone is lost or replaced.
- Disable a gateway device from the backend if it is no longer trusted.
- Do not log API keys, device secrets, phone numbers, or SMS bodies in release builds.

The app uses Android Keystore encryption for sensitive connection credentials stored on the phone.

## Compliance And Message Wording

The gateway sends normal SMS messages from a phone SIM. It is not a licensed bulk SMS provider and does not provide the same compliance tooling as a carrier-grade SMS API.

Important:

- Do not impersonate Safaricom.
- Do not impersonate M-PESA.
- Do not make a message look like it came from a bank, carrier, or payment provider if it did not.
- The message should clearly identify the platform sending it.
- High volume may trigger SIM, carrier, or fair-use limits.
- For production scale, plan a migration or fallback to a verified SMS provider.

Example safer wording:

```text
Your withdrawal of KES 2,000 has been processed. - Tradenova
```

## Build System

This is a Gradle Android project using Kotlin and Jetpack Compose.

Important files:

```text
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/europesa/smsgateway/
.github/workflows/build-apk.yml
APK_INSTALL.md
```

Local debug build:

```powershell
.\gradlew.bat assembleDebug
```

Local release build requires release signing configuration.

## GitHub Actions Build

GitHub Actions builds APK artifacts on pushes and pull requests to `main`.

The workflow produces:

- `sms-gateway-debug-apk`, containing `app-debug.apk`
- `sms-gateway-release-apk`, containing `app-release.apk`, only when signing secrets exist

Required GitHub secret for FCM-capable APK builds:

```text
GOOGLE_SERVICES_JSON
```

Optional GitHub secrets for signed release APKs:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

The workflow writes `GOOGLE_SERVICES_JSON` into `app/google-services.json`, validates that it is valid JSON, and confirms that it contains the package `com.europesa.smsgateway`.

## Installing A Test APK

For testing:

1. Open the GitHub repository.
2. Go to Actions.
3. Open the latest Build Android APK run.
4. Download `sms-gateway-debug-apk`.
5. Unzip the artifact.
6. Transfer `app-debug.apk` to the phone as a file/document.
7. Install the APK.
8. Allow restricted settings if Android blocks SMS permission.
9. Grant SMS permission.
10. Grant notification permission.
11. Open the app and add backend connections.
12. Turn Gateway Active on.
13. Disable battery optimization for the app.

Do not install `app-release-unsigned.apk`. Android rejects unsigned release APKs.

## Configuring The App On The Phone

For each backend:

1. Tap Add connection, Add Tradenova, or Add Nexamarket.
2. Enter the display name.
3. Enter the backend base URL.
4. Enter the device ID created by the backend.
5. Enter the API key created by the backend.
6. Enter the device secret created by the backend.
7. Optionally enter a SIM subscription ID.
8. Keep Enabled checked.
9. Save the connection.
10. Turn Gateway Active on.
11. Confirm the connection shows FCM registered.

## Acceptance Test

End-to-end acceptance test:

1. Build the APK using the real Firebase `google-services.json`.
2. Install the APK on the owner's Android phone.
3. Grant SMS and notification permissions.
4. Allow restricted settings if Android requires it.
5. Disable battery optimization for the APK.
6. Add Tradenova and Nexamarket connections.
7. Turn Gateway Active on.
8. Confirm each connection shows FCM registered.
9. Create a test SMS job from the backend.
10. Backend sends high-priority FCM data message with `event=sms_pending`.
11. APK receives FCM and calls `/api/sms-gateway/pending` immediately.
12. APK sends the SMS through the phone SIM.
13. APK reports `/api/sms-gateway/status`.
14. Backend marks the job as `sent`.
15. Admin dashboard shows the correct SMS status.

## Troubleshooting

### APK installs but FCM does not wake the app

Check:

- `GOOGLE_SERVICES_JSON` GitHub secret is set.
- Firebase Android app package is `com.europesa.smsgateway`.
- The APK was rebuilt after setting the real Firebase config.
- The backend stores the latest FCM token from `/register-token`.
- The backend sends a data message with `event=sms_pending`.
- The phone has network access.
- Battery optimization is disabled.
- Gateway Active is turned on.

### Connection does not show FCM registered

Check:

- Base URL is correct and uses HTTPS.
- `/api/sms-gateway/register-token` exists.
- API key and device secret match the backend.
- Backend signature verification uses `rawJsonBody + timestamp`.
- Backend returns a successful response.

### SMS does not send

Check:

- SMS permission is granted.
- Android restricted settings were allowed.
- SIM is inserted and active.
- SIM has airtime or SMS bundle.
- Phone has cellular service.
- Optional SIM subscription ID is correct.
- Message body is not empty.

### Backend rejects `/pending` or `/status`

Check:

- `Authorization` header is `Bearer {apiKey}`.
- `X-Timestamp` is epoch milliseconds.
- GET signature is HMAC over timestamp only.
- POST signature is HMAC over exact raw JSON body plus timestamp.
- Signature is lowercase hex.
- Backend clock and phone clock are close enough.

## Current Limitations

- The phone must be powered on.
- The phone must have network access.
- The SIM must be active and able to send SMS.
- Android can still restrict background execution on some devices.
- SMS delivery reports are not as strong as carrier-grade SMS APIs.
- High volume can hit SIM or carrier fair-use limits.
- This is a private APK, not a Play Store app.
- A commercial SMS provider is still recommended for long-term scale.

## Roadmap

Possible future improvements:

- Backend dashboard for gateway device health
- Multiple gateway phones per backend
- Better SIM/subscription picker UI
- QR-code provisioning for connection credentials
- Encrypted backup/export for connection profiles
- Firebase App Distribution for easier tester installs
- Signed release publishing through GitHub Releases
- Provider router implementation in each platform backend
- Celcom or Africa's Talking fallback provider

## Repository Safety

The repository ignores sensitive local build and credential files:

```text
app/google-services.json
google-services.json
*firebase-adminsdk*.json
*.jks
*.keystore
local.properties
```

Keep those files out of git. The Android Firebase config should be supplied to CI through `GOOGLE_SERVICES_JSON`, and the Firebase Admin SDK service account should live only in backend hosting secrets.
