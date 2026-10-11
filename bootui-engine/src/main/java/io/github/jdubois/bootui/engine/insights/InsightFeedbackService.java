package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.InsightFeedbackVoteDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Narrow, opt-in egress boundary for community ratings. No call is made by construction, configuration,
 * or a local report read. Only a deliberate aggregate or vote action contacts the configured Azure Function.
 * The HTTP response is decoded by the framework adapter, not by this JSON-free engine.
 */
public final class InsightFeedbackService {
    private static final int MAX_RESPONSE_BYTES = 8192;
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

    public Exchange aggregate(String kind) {
        requireKind(kind);
        return send("GET", "/api/aggregate?kind=" + kind, null);
    }

    public Exchange vote(InsightFeedbackVoteDto vote) {
        Objects.requireNonNull(vote);
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
        return send("POST", "/api/vote", body);
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

    private Exchange send(String method, String path, String body) {
        if (!enabled()) return new Exchange(503, null);
        HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(path))
                .timeout(Duration.ofSeconds(4))
                .header("Accept", "application/json");
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<java.io.InputStream> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) return new Exchange(502, null);
                if (response.statusCode() == 409) return new Exchange(409, null);
                if (response.statusCode() != 200
                        || !response.headers()
                                .firstValue("content-type")
                                .orElse("")
                                .toLowerCase(java.util.Locale.ROOT)
                                .startsWith("application/json")) {
                    return new Exchange(502, null);
                }
                return new Exchange(200, new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return new Exchange(502, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Exchange(502, null);
        }
    }

    public record Exchange(int status, String json) {}
}
