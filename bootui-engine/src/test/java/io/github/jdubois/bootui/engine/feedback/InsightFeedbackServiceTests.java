package io.github.jdubois.bootui.engine.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.InsightFeedbackAggregateDto;
import io.github.jdubois.bootui.core.dto.InsightFeedbackVoteDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InsightFeedbackServiceTests {
    private final HttpClient client = mock(HttpClient.class);
    private final Supplier<List<RuntimeInsightCheckDto>> checks = () -> List.of(
            new RuntimeInsightCheckDto("route-errors", "Route errors", "EVALUATED", 1, 0, null),
            new RuntimeInsightCheckDto("sql-work", "SQL work", "UNAVAILABLE", 0, 0, "No JDBC"));

    @Test
    void defaultsOffAndNeverCallsNetwork() {
        InsightFeedbackService service = new InsightFeedbackService(null, checks, client);
        assertThat(service.enabled()).isFalse();
        assertThat(service.aggregate("route-errors").join().status()).isEqualTo(503);
        assertThat(service.vote(new InsightFeedbackVoteDto("route-errors", 3))
                        .join()
                        .status())
                .isEqualTo(503);
        verify(client, never()).sendAsync(any(), any());
    }

    @Test
    void disallowsUnsafeProxyDestinations() {
        for (String endpoint : List.of(
                "http://feedback.azurewebsites.net", "https://localhost",
                "https://feedback.azurewebsites.net.evil.test", "https://feedback.azurewebsites.net:444",
                "https://feedback.azurewebsites.net/redirect", "https://feedback.azurewebsites.net@evil.test",
                "https://feedback.azurewebsites.net?key=value", "https://feedback.azurewebsites.net#fragment")) {
            assertThatThrownBy(() -> new InsightFeedbackService(endpoint, checks, client))
                    .as(endpoint)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsInvalidStarsUnknownKindsAndNullVotesBeforeNetwork() {
        InsightFeedbackService service = service();
        for (int stars : List.of(0, 6)) {
            assertThatThrownBy(() -> service.vote(new InsightFeedbackVoteDto("route-errors", stars)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.vote(new InsightFeedbackVoteDto("unknown", 5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.vote(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.aggregate(null)).isInstanceOf(IllegalArgumentException.class);
        verify(client, never()).sendAsync(any(), any());
    }

    @Test
    void sendsOnlyNarrowPayloadIncludingEvaluatedZeroOrNullAfterAnAction() {
        for (String kind : List.of("route-errors", "sql-work")) {
            HttpClient http = mock(HttpClient.class);
            doReturn(CompletableFuture.completedFuture(response(200, "application/json", "{}")))
                    .when(http)
                    .sendAsync(any(), any());
            InsightFeedbackService service =
                    new InsightFeedbackService("https://feedback.azurewebsites.net", checks, http);
            assertThat(service.vote(new InsightFeedbackVoteDto(kind, 3)).join().status())
                    .isEqualTo(200);
            ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http).sendAsync(request.capture(), any());
            assertThat(request.getValue().uri().toString()).isEqualTo("https://feedback.azurewebsites.net/api/vote");
            assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(4));
            assertThat(body(request.getValue()))
                    .isEqualTo("{\"kind\":\"" + kind + "\",\"stars\":3,\"findings\":"
                            + ("route-errors".equals(kind) ? "0" : "null") + "}");
        }
    }

    @Test
    void returnsWithoutWaitingForRemoteHeadersOrBodyAndReportsTransportFailure() {
        CompletableFuture<HttpResponse<String>> pending = new CompletableFuture<>();
        doReturn(pending).when(client).sendAsync(any(), any());
        CompletableFuture<InsightFeedbackService.Exchange> result = service().aggregate("route-errors");
        assertThat(result).isNotDone();
        pending.completeExceptionally(new IOException("remote failed"));
        assertThat(result.join()).isEqualTo(new InsightFeedbackService.Exchange(502, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void responseHandlerRejectsOversizedBodyBeforeCompletion() {
        doReturn(CompletableFuture.completedFuture(response(200, "application/json", "{}")))
                .when(client)
                .sendAsync(any(), any());
        service().aggregate("route-errors").join();
        ArgumentCaptor<HttpResponse.BodyHandler<String>> handler =
                ArgumentCaptor.forClass(HttpResponse.BodyHandler.class);
        verify(client).sendAsync(any(), handler.capture());
        HttpResponse.BodySubscriber<String> subscriber =
                handler.getValue().apply(mock(HttpResponse.ResponseInfo.class));
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[8193])));
        verify(subscription).cancel();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
                .hasRootCauseInstanceOf(IOException.class);
    }

    @Test
    void rejectsRedirectsNonJsonAndNonSuccessfulResponses() {
        for (int status : List.of(302, 409, 503)) {
            doReturn(CompletableFuture.completedFuture(response(status, "application/json", "{}")))
                    .when(client)
                    .sendAsync(any(), any());
            assertThat(service().aggregate("route-errors").join().status()).isEqualTo(502);
        }
        for (String contentType : List.of("text/html", "application/json-invalid")) {
            doReturn(CompletableFuture.completedFuture(response(200, contentType, "{}")))
                    .when(client)
                    .sendAsync(any(), any());
            assertThat(service().aggregate("route-errors").join().status()).isEqualTo(502);
        }
        doReturn(CompletableFuture.completedFuture(response(409, "application/json", "{}")))
                .when(client)
                .sendAsync(any(), any());
        assertThat(service()
                        .vote(new InsightFeedbackVoteDto("route-errors", 5))
                        .join()
                        .status())
                .isEqualTo(409);
    }

    @Test
    void validatesAggregateCountersKindAndAverage() {
        InsightFeedbackAggregateDto valid =
                new InsightFeedbackAggregateDto("route-errors", 2, List.of(0L, 0L, 0L, 1L, 1L), 4.5, 1, 0);
        assertThat(InsightFeedbackService.validateAggregate(valid, "route-errors"))
                .isSameAs(valid);
        for (InsightFeedbackAggregateDto invalid : List.of(
                new InsightFeedbackAggregateDto("other", 2, valid.stars(), 4.5, 1, 0),
                new InsightFeedbackAggregateDto("route-errors", 2, List.of(2L), 4.5, 1, 0),
                new InsightFeedbackAggregateDto("route-errors", 3, valid.stars(), 4.5, 1, 0),
                new InsightFeedbackAggregateDto("route-errors", 2, valid.stars(), 5, 1, 0),
                new InsightFeedbackAggregateDto("route-errors", 2, valid.stars(), Double.NaN, 1, 0),
                new InsightFeedbackAggregateDto("route-errors", 2, valid.stars(), 4.5, 3, 0),
                new InsightFeedbackAggregateDto("route-errors", 2, valid.stars(), 4.5, 0, 1))) {
            assertThatThrownBy(() -> InsightFeedbackService.validateAggregate(invalid, "route-errors"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private InsightFeedbackService service() {
        return new InsightFeedbackService("https://feedback.azurewebsites.net", checks, client);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String contentType, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers())
                .thenReturn(HttpHeaders.of(Map.of("content-type", List.of(contentType)), (a, b) -> true));
        when(response.body()).thenReturn(body);
        return response;
    }

    private static String body(HttpRequest request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer buffer) {
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }

            @Override
            public void onError(Throwable error) {
                throw new AssertionError(error);
            }

            @Override
            public void onComplete() {}
        });
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
