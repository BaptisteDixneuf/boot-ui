package io.github.jdubois.bootui.engine.feedback;

import io.github.jdubois.bootui.core.dto.InsightFeedbackAggregateDto;
import io.github.jdubois.bootui.core.dto.InsightFeedbackVoteDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.engine.web.BoundedBodyReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Narrow, opt-in egress boundary for community ratings. No call is made by construction, configuration,
 * or a local report read. Only a deliberate aggregate or vote action contacts the configured Azure Function.
 * The HTTP response is decoded by the framework adapter, not by this JSON-free engine.
 */
public final class InsightFeedbackService {
    private static final int MAX_RESPONSE_BYTES = 8192;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final System.Logger LOG = System.getLogger(InsightFeedbackService.class.getName());
    private final URI origin;
    private final Supplier<List<RuntimeInsightCheckDto>> checks;
    private final HttpClient client;

    public InsightFeedbackService(String endpoint, Supplier<List<RuntimeInsightCheckDto>> checks) {
        this(
                endpoint,
                checks,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    InsightFeedbackService(String endpoint, Supplier<List<RuntimeInsightCheckDto>> checks, HttpClient client) {
        this.checks = Objects.requireNonNull(checks);
        this.client = Objects.requireNonNull(client);
        if (endpoint == null || endpoint.isBlank()) {
            origin = null;
        } else {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme())
                    || host == null
                    || !host.matches("(?i)[a-z0-9-]+\\.azurewebsites\\.net")
                    || uri.getPort() != -1
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
                throw new IllegalArgumentException("Feedback endpoint must be an HTTPS Azure Functions origin.");
            }
            origin = URI.create("https://" + host.toLowerCase(java.util.Locale.ROOT));
        }
    }

    public boolean enabled() {
        return origin != null;
    }

    public CompletableFuture<Exchange> aggregate(String kind) {
        requireKind(kind);
        return send("/api/aggregate?kind=" + kind, null);
    }

    public CompletableFuture<Exchange> vote(InsightFeedbackVoteDto vote) {
        if (vote == null) throw new IllegalArgumentException("Invalid insight feedback.");
        RuntimeInsightCheckDto check = requireKind(vote.kind());
        if (vote.stars() < 1 || vote.stars() > 5) {
            throw new IllegalArgumentException("Invalid feedback rating.");
        }
        Integer findings = "EVALUATED".equals(check.status()) ? check.findings() : null;
        if (findings != null && (findings < 0 || findings > 1000)) {
            throw new IllegalArgumentException("Invalid check findings.");
        }
        String body = "{\"kind\":\"" + vote.kind() + "\",\"stars\":" + vote.stars() + ",\"findings\":"
                + (findings == null ? "null" : findings) + "}";
        return send("/api/vote", body);
    }

    /** Validate the remote counters after an adapter decodes JSON, before they reach the shared UI. */
    public static InsightFeedbackAggregateDto validateAggregate(InsightFeedbackAggregateDto value, String kind) {
        if (value == null
                || !Objects.equals(kind, value.kind())
                || value.votes() < 0
                || value.votes() > MAX_SAFE_INTEGER
                || value.evaluatedVotes() < 0
                || value.evaluatedVotes() > value.votes()
                || value.reportedFindings() < 0
                || value.reportedFindings() > MAX_SAFE_INTEGER
                || value.reportedFindings() > value.evaluatedVotes() * 1000
                || value.stars().size() != 5) {
            throw new IllegalArgumentException("Invalid community feedback aggregate.");
        }
        long total = 0;
        long weighted = 0;
        for (int index = 0; index < 5; index++) {
            Long count = value.stars().get(index);
            if (count == null || count < 0 || count > value.votes()) {
                throw new IllegalArgumentException("Invalid community feedback aggregate.");
            }
            total += count;
            weighted += count * (index + 1);
        }
        double average = total == 0 ? 0 : (double) weighted / total;
        if (total != value.votes() || !Double.isFinite(value.average()) || Math.abs(average - value.average()) > 1e-9) {
            throw new IllegalArgumentException("Invalid community feedback aggregate.");
        }
        return value;
    }

    public static String errorMessage(int status) {
        return switch (status) {
            case 409 -> "Already rated this check today from this network.";
            case 503 -> "Community feedback is not configured.";
            default -> "Community feedback is temporarily unavailable.";
        };
    }

    private RuntimeInsightCheckDto requireKind(String kind) {
        if (kind == null || !kind.matches("[a-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Unknown insight check.");
        }
        return checks.get().stream()
                .filter(check -> kind.equals(check.kind()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown insight check."));
    }

    private CompletableFuture<Exchange> send(String path, String body) {
        if (!enabled()) return CompletableFuture.completedFuture(new Exchange(503, null));
        HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(path))
                .timeout(Duration.ofSeconds(4))
                .header("Accept", "application/json");
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return client.sendAsync(request.build(), BoundedBodyReader.strictBodyHandler(MAX_RESPONSE_BYTES))
                .handle((response, failure) -> {
                    if (failure != null) {
                        LOG.log(System.Logger.Level.WARNING, "Community feedback request failed.");
                        return new Exchange(502, null);
                    }
                    if (body != null && response.statusCode() == 409) return new Exchange(409, null);
                    String contentType = response.headers()
                            .firstValue("content-type")
                            .orElse("")
                            .split(";", 2)[0]
                            .trim();
                    if (response.statusCode() != 200 || !"application/json".equalsIgnoreCase(contentType)) {
                        LOG.log(
                                System.Logger.Level.WARNING,
                                "Community feedback returned an unexpected HTTP response.");
                        return new Exchange(502, null);
                    }
                    return new Exchange(200, response.body());
                });
    }

    public record Exchange(int status, String json) {}
}
