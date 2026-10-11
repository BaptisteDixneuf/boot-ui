package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.InsightFeedbackVoteDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class InsightFeedbackServiceTests {
    private final HttpClient client = mock(HttpClient.class);
    private final Supplier<List<RuntimeInsightCheckDto>> checks = () -> List.of(
            new RuntimeInsightCheckDto("route-errors", "Route errors", "EVALUATED", 1, 0, null),
            new RuntimeInsightCheckDto("sql-work", "SQL work", "UNAVAILABLE", 0, 0, "No JDBC"));

    @Test
    void defaultsOffAndNeverCallsNetwork() throws Exception {
        InsightFeedbackService service = new InsightFeedbackService(null, checks, client);
        assertThat(service.enabled()).isFalse();
        assertThat(service.aggregate("route-errors").status()).isEqualTo(503);
        verify(client, never()).send(any(), any());
    }

    @Test
    void disallowsUnsafeProxyDestinations() {
        for (String endpoint : List.of(
                "http://feedback.azurewebsites.net",
                "https://localhost",
                "https://feedback.azurewebsites.net.evil.test",
                "https://feedback.azurewebsites.net:444",
                "https://feedback.azurewebsites.net/redirect",
                "https://feedback.azurewebsites.net@evil.test")) {
            assertThatThrownBy(() -> new InsightFeedbackService(endpoint, checks, client))
                    .as(endpoint)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void acceptsEvaluatedZeroButNotInvalidStarsOrUnknownKinds() throws Exception {
        InsightFeedbackService service =
                new InsightFeedbackService("https://feedback.azurewebsites.net", checks, client);
        assertThatThrownBy(() -> service.vote(new InsightFeedbackVoteDto("route-errors", 6)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.vote(new InsightFeedbackVoteDto("unknown", 5)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(client, never()).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsOnlyNarrowPayloadOnActionAndBoundsReturnedData() throws Exception {
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers())
                .thenReturn(HttpHeaders.of(Map.of("content-type", List.of("application/json")), (a, b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream("{\"kind\":\"route-errors\"}".getBytes()));
        doReturn(response).when(client).send(any(), any());
        InsightFeedbackService service =
                new InsightFeedbackService("https://feedback.azurewebsites.net", checks, client);
        assertThat(service.vote(new InsightFeedbackVoteDto("route-errors", 3)).status())
                .isEqualTo(200);
        org.mockito.ArgumentCaptor<HttpRequest> request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any());
        assertThat(request.getValue().uri().toString()).isEqualTo("https://feedback.azurewebsites.net/api/vote");
        assertThat(request.getValue().bodyPublisher().orElseThrow().contentLength())
                .isLessThan(80);
    }
}
