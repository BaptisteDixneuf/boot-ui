package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import java.util.List;
import org.junit.jupiter.api.Test;

class SampleExecutableJarReadinessTests {

    @Test
    void answeringBootUiDoesNotMeanStartupRunnersFinished() {
        BootUiHttpProbe probe = mock(BootUiHttpProbe.class);
        when(probe.get("/bootui/api/java-agent")).thenReturn(response(200, "{}"));
        when(probe.get("/actuator/health/readiness"))
                .thenReturn(response(503, "{\"status\":\"OUT_OF_SERVICE\"}"))
                .thenReturn(response(200, "{\"status\":\"UP\"}"));

        assertThat(SampleExecutableJar.isReady(probe)).isFalse();
        assertThat(SampleExecutableJar.isReady(probe)).isTrue();
    }

    @Test
    void aReadyApplicationAlsoNeedsItsBootUiEndpoint() {
        BootUiHttpProbe probe = mock(BootUiHttpProbe.class);
        when(probe.get("/bootui/api/java-agent")).thenReturn(response(404, "{}"));

        assertThat(SampleExecutableJar.isReady(probe)).isFalse();
        verify(probe, never()).get("/actuator/health/readiness");
    }

    @Test
    void readinessRequiresAnUpStatusRatherThanJustHttpSuccess() {
        BootUiHttpProbe probe = mock(BootUiHttpProbe.class);
        when(probe.get("/bootui/api/java-agent")).thenReturn(response(200, "{}"));
        for (String body : List.of(
                "",
                "null",
                "[]",
                "\"UP\"",
                "{}",
                "{\"status\":true}",
                "{\"status\":200}",
                "{\"status\":null}",
                "{\"status\":\"DOWN\"}",
                "{\"status\":\"OUT_OF_SERVICE\"}",
                "{\"status\":\"up\"}")) {
            when(probe.get("/actuator/health/readiness")).thenReturn(response(200, body));
            assertThat(SampleExecutableJar.isReady(probe)).as(body).isFalse();
        }
    }

    @Test
    void aMissingReadinessProbeIsNotAReadyApplication() {
        BootUiHttpProbe probe = mock(BootUiHttpProbe.class);
        when(probe.get("/bootui/api/java-agent")).thenReturn(response(200, "{}"));
        when(probe.get("/actuator/health/readiness")).thenReturn(response(404, "{}"));

        assertThat(SampleExecutableJar.isReady(probe)).isFalse();
    }

    @Test
    void malformedReadinessJsonIsAnExplicitProbeFailure() {
        BootUiHttpProbe probe = mock(BootUiHttpProbe.class);
        when(probe.get("/bootui/api/java-agent")).thenReturn(response(200, "{}"));
        when(probe.get("/actuator/health/readiness")).thenReturn(response(200, "not-json"));

        assertThatThrownBy(() -> SampleExecutableJar.isReady(probe))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Response body is not valid JSON");
    }

    private static BootUiHttpProbe.Response response(int status, String body) {
        return new BootUiHttpProbe.Response(status, "application/json", body);
    }
}
