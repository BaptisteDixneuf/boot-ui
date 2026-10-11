package io.github.jdubois.bootui.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.sample.CaptureOverheadBenchmarkTest.Result;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Temporary, standalone admission-only comparison; never invoked by the normal build or acceptance tests. */
public final class JournalPerformanceComparison {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WARM_UP = Duration.ofSeconds(10);
    private static final Duration MEASURE = Duration.ofSeconds(15);
    private static final List<String> JVM = List.of("-XX:ActiveProcessorCount=4", "-Xms512m", "-Xmx512m");
    private static final List<String> ARGUMENTS = List.of(
            "--bootui.runtime-journal.enabled=true",
            "--spring.jmx.enabled=false",
            "--management.tracing.sampling.probability=1.0",
            "--logging.level.root=WARN",
            "--logging.level.io.github.jdubois.bootui=WARN",
            "--logging.level.org.hibernate.SQL=WARN");
    private static final long MAX_DURATION_NANOS = Duration.ofMinutes(50).toNanos();
    private static long beganNanos;

    public static void main(String[] args) throws Exception {
        if (args.length != 3
                || Runtime.version().feature() != 17
                || Runtime.getRuntime().availableProcessors() != 4) {
            throw new IllegalArgumentException(
                    "Requires Java 17, CPU4, baseline jar, candidate jar and output directory");
        }
        Path baseline = Path.of(args[0]).toAbsolutePath();
        Path candidate = Path.of(args[1]).toAbsolutePath();
        Path output = Path.of(args[2]).toAbsolutePath();
        beganNanos = System.nanoTime();
        Files.createDirectories(output);
        List<Map<String, Object>> runs = new ArrayList<>();
        List<Result> baselineResults = new ArrayList<>();
        List<Result> candidateResults = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("baselineJar", baseline.toString());
        report.put("baselineSha256", sha256(baseline));
        report.put("candidateJar", candidate.toString());
        report.put("candidateSha256", sha256(candidate));
        report.put("java", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name"));
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("clients", CaptureOverheadBenchmarkTest.CONCURRENCY);
        report.put("warmUpSeconds", WARM_UP.toSeconds());
        report.put("measuredSeconds", MEASURE.toSeconds());
        report.put("pairs", 15);
        report.put(
                "captureControlMeaning",
                "Status recorded counters count admissions; an empty queue does not prove the taken batch processed");
        report.put("runs", runs);
        double[] ratios = new double[15];
        try {
            run("discarded", baseline, output, runs);
            for (int pair = 1; pair <= 15; pair++) {
                Result base = null;
                Result proposed = null;
                for (boolean on : pair % 2 == 1 ? new boolean[] {false, true} : new boolean[] {true, false}) {
                    Result result =
                            run((on ? "candidate" : "baseline") + "-" + pair, on ? candidate : baseline, output, runs);
                    if (on) {
                        proposed = result;
                        candidateResults.add(result);
                    } else {
                        base = result;
                        baselineResults.add(result);
                    }
                }
                ratios[pair - 1] = proposed.throughput() / base.throughput();
                JSON.writeValue(output.resolve("results.json").toFile(), report);
            }
            double[] sorted = ratios.clone();
            Arrays.sort(sorted);
            double median = sorted[sorted.length / 2];
            double[] interval = AgentOverheadBenchmarkIT.medianInterval(ratios);
            report.put("pairedRatios", ratios);
            report.put("medianThroughputChangePercent", (median - 1) * 100);
            report.put("lowThroughputChangePercent", (interval[0] - 1) * 100);
            report.put("highThroughputChangePercent", (interval[1] - 1) * 100);
            report.put("intervalCoverage", interval[2]);
            report.put(
                    "baselineMedianP99Millis",
                    CaptureOverheadBenchmarkTest.median(baselineResults, r -> r.percentileMillis(99)));
            report.put(
                    "candidateMedianP99Millis",
                    CaptureOverheadBenchmarkTest.median(candidateResults, r -> r.percentileMillis(99)));
            report.put(
                    "baselineMedianP50Millis",
                    CaptureOverheadBenchmarkTest.median(baselineResults, r -> r.percentileMillis(50)));
            report.put(
                    "candidateMedianP50Millis",
                    CaptureOverheadBenchmarkTest.median(candidateResults, r -> r.percentileMillis(50)));
            report.put("allControlsPassed", true);
            double baselineP99 = ((Number) report.get("baselineMedianP99Millis")).doubleValue();
            double candidateP99 = ((Number) report.get("candidateMedianP99Millis")).doubleValue();
            double baselineP50 = ((Number) report.get("baselineMedianP50Millis")).doubleValue();
            double candidateP50 = ((Number) report.get("candidateMedianP50Millis")).doubleValue();
            report.put("baselineMeasuredSqlPerHttp", sqlPerHttp(runs, "baseline-"));
            report.put("candidateMeasuredSqlPerHttp", sqlPerHttp(runs, "candidate-"));
            require(
                    Math.abs(((Number) report.get("baselineMeasuredSqlPerHttp")).doubleValue()
                                    - ((Number) report.get("candidateMeasuredSqlPerHttp")).doubleValue())
                            < 0.001,
                    "SQL capture per HTTP request changed");
            report.put(
                    "verdict",
                    interval[0] > 1 && candidateP99 <= baselineP99 && candidateP50 <= baselineP50
                            ? "POSITIVE_INTERVAL_WITHOUT_MEDIAN_LATENCY_DEGRADATION"
                            : "NOT_PROVEN");
            System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } catch (Exception | AssertionError failure) {
            report.put("allControlsPassed", false);
            report.put("failure", failure.toString());
            throw failure;
        } finally {
            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(output.resolve("results.json").toFile(), report);
        }
    }

    private static double sqlPerHttp(List<Map<String, Object>> runs, String prefix) {
        long sql = 0;
        long http = 0;
        for (Map<String, Object> run : runs) {
            if (((String) run.get("name")).startsWith(prefix)) {
                JsonNode before = (JsonNode) run.get("journalBefore");
                JsonNode after = (JsonNode) run.get("journalAfter");
                sql += after.path("recorded").path("sql").asLong()
                        - before.path("recorded").path("sql").asLong();
                http += ((Number) run.get("requests")).longValue();
            }
        }
        return sql / (double) http;
    }

    private static Result run(String name, Path jar, Path output, List<Map<String, Object>> runs) throws Exception {
        require(System.nanoTime() - beganNanos < MAX_DURATION_NANOS, "Comparison exceeded its runtime envelope");
        System.setProperty("sample.jar", jar.toString());
        try (SampleExecutableJar sample = SampleExecutableJar.start("journal-direct-" + name, JVM, ARGUMENTS)) {
            try {
                JsonNode agent = sample.probe().get("/bootui/api/java-agent").json();
                require("NOT_ATTACHED".equals(agent.path("state").asText()), "Unexpected attached agent: " + agent);
                URI uri = URI.create("http://localhost:" + sample.port() + CaptureOverheadBenchmarkTest.ROUTE);
                HttpClient client = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
                long warmRequests = CaptureOverheadBenchmarkTest.load(client, uri, WARM_UP).stream()
                        .mapToLong(values -> values.length)
                        .sum();
                JsonNode before = admissionStatus(sample, warmRequests);
                long started = System.nanoTime();
                List<long[]> samples = CaptureOverheadBenchmarkTest.load(client, uri, MEASURE);
                double seconds = (System.nanoTime() - started) / 1e9;
                long[] all =
                        samples.stream().flatMapToLong(Arrays::stream).sorted().toArray();
                JsonNode after = admissionStatus(
                        sample, before.path("recorded").path("http").asLong() + all.length);
                require(after.path("enabled").asBoolean(), "Journal disabled");
                require(
                        after.path("droppedEvents").isNumber()
                                && after.path("droppedEvents").asLong() == 0,
                        "Admission loss: " + after);
                require(all.length > 0, "Empty HTTP load");
                for (String source : List.of("http", "sql")) {
                    require(
                            after.path("recorded").path(source).asLong()
                                    > before.path("recorded").path(source).asLong(),
                            "Missing measured " + source + " admissions: " + after);
                }
                require(
                        after.path("recorded").path("http").asLong()
                                        - before.path("recorded").path("http").asLong()
                                == all.length,
                        "HTTP admission delta differs from measured requests: " + after);
                require(
                        after.path("retainedBytes").isNumber()
                                && after.path("maxBytes").isNumber()
                                && after.path("retainedEvents").isNumber()
                                && after.path("maxEvents").isNumber(),
                        "Missing byte bound controls: " + after);
                require(
                        after.path("retainedBytes").asLong()
                                        <= after.path("maxBytes").asLong()
                                && after.path("retainedEvents").asLong()
                                        <= after.path("maxEvents").asLong(),
                        "Retained byte bound exceeded: " + after);
                Result result = new Result(name, all.length, seconds, all);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("jarSha256", sha256(jar));
                row.put("requests", all.length);
                row.put("seconds", seconds);
                row.put("requestsPerSecond", result.throughput());
                row.put("p50Millis", result.percentileMillis(50));
                row.put("p99Millis", result.percentileMillis(99));
                row.put("journalBefore", before);
                row.put("journalAfter", after);
                row.put("agent", agent);
                row.put(
                        "listenerFailureCounter",
                        "not exposed by this status DTO; covered by separate engine quality tests");
                row.put(
                        "orderControl",
                        "covered by separate engine FIFO/sequence quality tests, not inferred from HTTP status");
                row.put("port", sample.port());
                runs.add(row);
                JSON.writeValue(output.resolve(name + ".json").toFile(), row);
                Files.copy(
                        Path.of("target", "executable-jar", "journal-direct-" + name, "sample.log"),
                        output.resolve(name + ".log"));
                System.out.printf(
                        Locale.ROOT,
                        "%s requests=%d rps=%.2f p99=%.2f drops=0%n",
                        name,
                        all.length,
                        result.throughput(),
                        result.percentileMillis(99));
                return result;
            } finally {
                Files.writeString(output.resolve(name + "-application-tail.log"), sample.tail());
            }
        }
    }

    private static JsonNode admissionStatus(SampleExecutableJar sample, long minimumHttpAdmissions) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            JsonNode status = sample.probe().get("/bootui/api/activity/journal").json();
            require(status.path("queueDepth").isNumber(), "Missing queue depth: " + status);
            if (status.path("queueDepth").asInt() == 0
                    && status.path("recorded").path("http").asLong() >= minimumHttpAdmissions) {
                return status;
            }
            require(System.nanoTime() < deadline, "Expected HTTP admissions did not settle: " + status);
            Thread.sleep(10);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new AssertionError(message);
        }
    }
}
