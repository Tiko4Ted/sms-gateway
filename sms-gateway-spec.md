# Custom Android SMS Gateway — Review + Production Spec

## 1. Review of the proposal as written

**What's right:**
- Provider abstraction (Telerivet → custom gateway → future paid API) is the correct shape. Don't rip out Telerivet — demote it to one option in a router.
- Pull-based polling from an untrusted device (rather than the server pushing to the phone) is the right trust direction — you never expose an inbound endpoint on a personal phone.
- Correctly identified you shouldn't impersonate Safaricom/M-PESA in message wording.

**What's missing or wrong:**

1. **This doesn't inherently fix the latency problem.** Telerivet's Android app already does exactly what you're proposing — poll/sync in the background and dispatch via `SmsManager`. The queuing delay you're seeing is Android's Doze mode, App Standby buckets, and OEM battery managers (Xiaomi MIUI, Tecno/Infinix HiOS, Oppo ColorOS are notorious for killing background services outright — common devices in Kenya). Writing your own app changes *who controls the code*, not *what OS constraints apply*. If you don't explicitly solve for this (FCM push wake, battery-optimization whitelist, ideally a dedicated always-charged device), you'll reproduce the same delay in a few weeks.
2. **No redundancy story.** Telerivet-via-Android already had this weakness; your replacement has it too, worse — it's now a single physical device with no fallback in the flow when *that specific phone* has no airtime bundle, is off, or is confiscated/lost. Recommend explicitly keeping Telerivet or a paid API as a live fallback in the router from day one, not "later."
3. **Regulatory/carrier exposure understated.** You're framing "no business verification" as pure upside. It's also a liability: Kenya's Communications Authority regulates bulk/premium SMS senders, and Safaricom's consumer SIM terms restrict automated/commercial use at volume. High enough withdrawal-notification volume on a personal line risks number flagging or suspension — which is a worse outage than a Telerivet plan cap, because you can't just top up. Worth getting real legal input before this is your primary channel, not just a stopgap.
4. **No idempotency/concurrency handling specified.** If two gateway polls overlap (e.g., app restarts mid-batch), the same `SmsOutbox` row could be claimed twice and sent twice — a marketer getting a duplicate "your withdrawal was processed" SMS is a trust problem, not just a nuisance. Needs row-level locking (spec below).
5. **No dead-letter/alerting path.** "Retry rules for failed messages" isn't enough — you need a terminal failure state that pages *you* (email/Telegram bot), because if the phone is the only channel and it silently dies, marketers stop getting withdrawal confirmations and you won't know until they complain.
6. **Single SIM = single throughput ceiling.** If withdrawal volume grows, one phone/SIM can't scale. The provider-router design should make adding a second gateway device trivial (device pool, not a singleton).

Net: the architecture is directionally right. Treat it as **fallback tier #2, not a Telerivet replacement**, until you've proven latency and reliability in production for a few weeks — and budget real effort into the Android background-execution problem specifically, since that's the actual root cause you're trying to escape.

---

## 2. Backend spec

### 2.1 Database schema (Postgres)

```sql
CREATE TYPE sms_status AS ENUM ('pending', 'claimed', 'sent', 'failed', 'dead_letter');
CREATE TYPE sms_provider AS ENUM ('telerivet', 'android_gateway', 'africas_talking', 'celcom');

CREATE TABLE sms_outbox (
    id              BIGSERIAL PRIMARY KEY,
    idempotency_key UUID NOT NULL UNIQUE,        -- generated at creation time; prevents duplicate rows from retried business logic
    recipient_msisdn VARCHAR(15) NOT NULL,        -- E.164, e.g. +2547XXXXXXXX
    message_body    TEXT NOT NULL,
    event_type      VARCHAR(50) NOT NULL,         -- 'withdrawal', 'balance_adjustment', etc — for audit/filtering
    reference_id    VARCHAR(100),                 -- FK-ish pointer to the withdrawal/adjustment record, not a hard FK across services

    status          sms_status NOT NULL DEFAULT 'pending',
    assigned_provider sms_provider,               -- set by the router at creation or on retry
    claimed_by_device VARCHAR(100),                -- device_id of the gateway that claimed it
    claimed_at      TIMESTAMPTZ,
    claim_expires_at TIMESTAMPTZ,                  -- claim is released back to pending if not resolved by this time (stale-claim recovery)

    attempt_count   INT NOT NULL DEFAULT 0,
    max_attempts    INT NOT NULL DEFAULT 3,
    last_error      TEXT,
    sent_at         TIMESTAMPTZ,
    delivered_report_at TIMESTAMPTZ,               -- if you later capture native delivery reports

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sms_outbox_pending ON sms_outbox (status, created_at) WHERE status = 'pending';
CREATE INDEX idx_sms_outbox_claimed_stale ON sms_outbox (status, claim_expires_at) WHERE status = 'claimed';

CREATE TABLE sms_gateway_device (
    id              BIGSERIAL PRIMARY KEY,
    device_id       VARCHAR(100) NOT NULL UNIQUE,
    device_name     VARCHAR(100),                  -- "Owner Pixel 6a - primary"
    api_key_hash    TEXT NOT NULL,                  -- store a hash, never the raw key
    is_active       BOOLEAN NOT NULL DEFAULT true,
    last_seen_at    TIMESTAMPTZ,
    last_battery_pct SMALLINT,
    last_network_type VARCHAR(20),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sms_gateway_log (
    id              BIGSERIAL PRIMARY KEY,
    outbox_id       BIGINT NOT NULL REFERENCES sms_outbox(id),
    device_id       VARCHAR(100) NOT NULL,
    event           VARCHAR(20) NOT NULL,          -- 'claimed', 'sent', 'failed'
    detail          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Notes:
- `idempotency_key` at the business-logic layer (set when the withdrawal/adjustment fires) is what actually prevents "duplicate SMS on retry of the withdrawal handler" — separate from the claim-locking below, which prevents duplicate *sends* of the same row.
- `claim_expires_at` is what lets you recover from a phone that claimed a batch and then died before reporting status.

### 2.2 API endpoints

**Auth:** each device gets a long random API key at provisioning time; you store only its hash (bcrypt/argon2) server-side, similar to how you'd store a password. The device sends the raw key in a header; every request also gets an HMAC signature over the body using a per-device secret, to prevent replay if the key ever leaks from a captured request:

```
Authorization: Bearer <device_api_key>
X-Signature: HMAC-SHA256(body, device_secret)
X-Timestamp: <unix_ms>   -- reject if > 60s skew, prevents replay
```

**GET/POST `/api/sms-gateway/pending`**

Claims a batch atomically so two overlapping polls (or two devices) can't grab the same row:

```sql
UPDATE sms_outbox
SET status = 'claimed',
    claimed_by_device = $device_id,
    claimed_at = now(),
    claim_expires_at = now() + interval '3 minutes',
    attempt_count = attempt_count + 1
WHERE id IN (
    SELECT id FROM sms_outbox
    WHERE (status = 'pending')
       OR (status = 'claimed' AND claim_expires_at < now())   -- reclaim stale claims
    AND assigned_provider = 'android_gateway'
    AND attempt_count < max_attempts
    ORDER BY created_at ASC
    LIMIT $batch_size          -- e.g. 10
    FOR UPDATE SKIP LOCKED     -- critical: prevents two concurrent pollers from double-claiming
)
RETURNING id, recipient_msisdn, message_body, idempotency_key;
```

Response:
```json
{
  "jobs": [
    { "id": 4821, "recipient": "+2547XXXXXXXX", "message": "Your withdrawal of KES 2,000 has been processed. - TradeNova", "idempotency_key": "..." }
  ],
  "poll_interval_hint_seconds": 15
}
```

`FOR UPDATE SKIP LOCKED` is the piece that makes concurrent claiming safe without you having to hand-roll locking logic — this is the single most important line in the whole backend spec.

**POST `/api/sms-gateway/status`**

```json
{
  "device_id": "pixel6a-01",
  "results": [
    { "id": 4821, "status": "sent", "sent_at": "2026-08-31T10:15:02Z" },
    { "id": 4822, "status": "failed", "error": "GENERIC_FAILURE", "sent_at": null }
  ],
  "device_health": { "battery_pct": 62, "network_type": "LTE", "sim_present": true }
}
```

Server side: `sent` → status `sent`; `failed` → if `attempt_count >= max_attempts`, mark `dead_letter` and fire an alert (Telegram/email to you); otherwise reset to `pending` for retry with backoff (see below).

**GET `/api/admin/sms-logs`** — paginated view joining `sms_outbox` + `sms_gateway_log` for the dashboard: pending/sent/failed counts, per-device last-seen and battery, oldest pending age (this last one is your real health signal — alert if oldest pending job is >5 min old).

### 2.3 Retry rules

- Exponential backoff between attempts: delay before a `failed` job becomes eligible again = `min(2^attempt_count * 30s, 30min)`. Implement by setting a `next_retry_at` column (add this) and filtering `next_retry_at <= now()` in the claim query.
- `max_attempts = 3` by default, configurable per `event_type` (withdrawal confirmations probably deserve more retries than a marketing blast).
- On terminal failure (`dead_letter`): fire an internal alert immediately. A marketer not getting told their withdrawal succeeded is a support-ticket generator; don't let it fail silently.
- Router-level fallback: if a job sits in `dead_letter` and Telerivet/another provider is available, auto-resubmit through the next provider in the chain rather than requiring manual intervention.

### 2.4 Provider router (pseudocode)

```ts
async function sendNotificationSms(recipient: string, body: string, eventType: string) {
  const providers = getActiveProvidersInPriorityOrder(); // e.g. ['telerivet', 'android_gateway']
  const outboxRow = await createOutboxRow({ recipient, body, eventType, assignedProvider: providers[0] });

  if (providers[0] === 'telerivet') {
    const result = await telerivetClient.send(recipient, body).catch(e => null);
    if (result?.ok) return markSent(outboxRow.id);
    // fall through — reassign to next provider instead of just failing
    await reassignProvider(outboxRow.id, providers[1]);
  }
  // android_gateway and other providers are pulled asynchronously by their own pollers/workers
}
```

Keep the withdrawal/balance-adjustment business logic calling one function — `sendNotificationSms(...)` — so the router change is fully invisible to the rest of the codebase, exactly as you specified.

---

## 3. Android app spec

### 3.1 Delivery mechanism: FCM-triggered pull, not pure polling

Pure polling (fetch every N seconds) is what causes the delay you're trying to escape, *and* it drains battery, which makes OEM battery managers more aggressive about killing you — a bad spiral. Instead:

1. Server sends a **silent/data-only FCM push** the moment a job is created for the `android_gateway` provider.
2. The app's `FirebaseMessagingService.onMessageReceived()` wakes up (FCM data messages get a brief background execution window even under Doze) and immediately calls `/pending`.
3. Keep a **low-frequency fallback poll** (every 5–10 min) via `WorkManager` in case the FCM message is itself delayed or dropped — this is your safety net, not your primary path.

This combination gets you near-real-time dispatch without a battery-draining tight poll loop, and it's the actual fix for problem #2 — not just "control our own app."

### 3.2 Required permissions & manifest

```xml
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

`SEND_SMS` is a dangerous permission requiring runtime request — fine for a sideloaded private APK, but this is exactly why it can never go on the Play Store (Play policy restricts SMS permission apps to default SMS/dialer handlers only).

### 3.3 Sending SMS

```kotlin
val smsManager = context.getSystemService(SmsManager::class.java)
val parts = smsManager.divideMessage(job.message)
val sentIntents = parts.map { PendingIntent.getBroadcast(context, requestCode++, Intent(SMS_SENT_ACTION).putExtra("job_id", job.id), PendingIntent.FLAG_IMMUTABLE) }
smsManager.sendMultipartTextMessage(job.recipient, null, parts, ArrayList(sentIntents), null)
```

Register a `BroadcastReceiver` on `SMS_SENT_ACTION` to capture the actual `resultCode` (`RESULT_OK`, `RESULT_ERROR_GENERIC_FAILURE`, `RESULT_ERROR_NO_SERVICE`, `RESULT_ERROR_RADIO_OFF`) — this is your real delivery signal, not "the API call didn't throw." Batch these results and POST to `/status` every 30s or after each batch completes.

On a dual-SIM device, be explicit about which SIM slot sends (`smsManager.getSmsManagerForSubscriptionId(subId)`) rather than trusting the OS default — otherwise a SIM swap or dual-SIM misconfiguration silently breaks delivery.

### 3.4 Reliability requirements

- Request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` on first launch with a clear explanation screen — without this, MIUI/HiOS/ColorOS will kill the background service within hours regardless of how well you write it.
- `RECEIVE_BOOT_COMPLETED` to restart the foreground service after a phone reboot — a silent reboot (e.g. after an OS update) otherwise takes the whole gateway offline until someone notices.
- A minimal foreground notification ("SMS Gateway active — X pending") is required for a long-running foreground service on Android 8+, and it doubles as your at-a-glance health indicator.
- Queue screen in-app: list of last N jobs with status, a manual "poll now" button, and device health (battery %, network type, SIM presence) — useful for debugging without needing the web dashboard.
- Practically: use a spare device that stays plugged in and on Wi-Fi/always-on data, not your daily driver — this sidesteps a huge fraction of the OEM battery-killing behavior since it's not competing with normal phone usage patterns that make the OS suspicious of background activity.

---

## 4. What I'd actually do first, in order

1. Ship the backend (`SmsOutbox`, router, both endpoints) with `assigned_provider` support for **both** `telerivet` and `android_gateway` from day one — this de-risks the "single point of failure" problem for free.
2. Build the Android app with FCM-push + WorkManager-fallback from the start, not polling-then-optimize-later — retrofitting push after you've already proven the polling delay is expensive to unwind.
3. Run it in shadow mode for 1–2 weeks: send through the gateway, but also send through Telerivet, and compare actual delivery latency logged from both. Prove the fix works before making it primary.
4. Get a real answer (lawyer or at minimum CA's public guidance) on the automated/bulk SMS registration question before this becomes your primary channel at scale — the savings from skipping it now could cost you the number later.
