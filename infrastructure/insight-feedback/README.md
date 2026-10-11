# Optional Runtime Insights community feedback service

This opt-in Azure Function records one rating per **server egress IP + check kind + UTC day**,
not per browser or person. The frontend never calls Azure directly. The BootUI server accepts only
an HTTPS `*.azurewebsites.net` origin in `bootui.runtime-insights.feedback-endpoint`; by default
it is unset and BootUI makes **no outbound request**. In the Runtime Insights **Coverage & limits**
tab, selecting **View community ratings** fetches that check's aggregate; choosing stars and
checking the disclosure box explicitly submits a vote. Local evidence and findings remain
available when the service is disabled or fails. A read-only panel can view but not vote.

## Provision

With Azure CLI authenticated, Node 22+, npm, zip, and OpenSSL:

```sh
bash infrastructure/insight-feedback/provision.sh my-new-feedback-rg eastus
```

The script creates a **dedicated resource group**, storage account, serverless Cosmos DB
account, `feedback/votes` container partitioned by `/kind`, and a Linux Node Functions app.
It assigns the app's managed identity the Cosmos DB built-in Data Contributor role, creates
an HMAC key as an app setting, builds the pinned TypeScript Function, and zip-deploys it.
The resource group and Cosmos account incur Azure costs; to remove everything, delete the
dedicated resource group yourself. No provisioning runs during a BootUI build.

The Cosmos container's default TTL must stay enabled (`-1`); each hashed vote document has
a two-day TTL. Aggregates persist as counters. The function stores no raw IP, user-agent,
route, application identifier, evidence, or secrets. It stores a keyed HMAC of the IP seen
at Azure's edge, day, and check kind. Rotate `FEEDBACK_HMAC_KEY` in Azure app settings to
reset deduplication (existing vote IDs cannot be recomputed).

**Privacy and limits:** Azure/App Service normally supplies `X-ARR-ClientIP`
([App Service headers](https://github.com/Azure/app-service-linux-docs/blob/master/Things_You_Should_Know/headers.md)).
This value may be absent, altered by a front door or proxy configuration, or spoofed in
nonstandard deployments. The function rejects missing or invalid addresses rather than
trusting a browser-supplied XFF. It sees the **BootUI server's** egress or proxy address,
not necessarily the browser's. All users behind one NAT may share a single daily vote,
and anyone able to change egress IP or bypass the service can evade this best-effort
deduplication. It is **not an authentication, rate-limit, or security barrier**.
Azure's infrastructure may log request IPs under its own retention policy, even though
the Cosmos records do not store raw addresses. Use Azure networking/access restrictions
if deployment requires stronger protection. Do not log vote requests or enable shell
tracing while provisioning: Azure CLI app-setting arguments contain the generated HMAC key.

Only a check kind, stars (1–5), and `findings` (integer including **zero** when the local
check was evaluated; `null` otherwise) leave the BootUI server on a vote. Aggregates
contain the five star counts, weighted average, evaluated vote count, and sum of
self-reported findings; they say nothing about diagnostic correctness. A duplicate
returns HTTP 409 and never increments counters. The anonymous Function is deliberately
abuse-tolerant rather than abuse-proof.
