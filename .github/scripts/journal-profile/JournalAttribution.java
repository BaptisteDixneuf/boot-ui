package io.github.jdubois.bootui.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Temporary baseline-only JFR attribution; not a throughput or overhead comparison. */
public final class JournalAttribution {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RECORDING = "journal-attribution";
    private static final List<String> JVM = List.of("-XX:ActiveProcessorCount=4", "-Xms512m", "-Xmx512m");
    private static final List<String> ARGUMENTS = List.of(
            "--bootui.runtime-journal.enabled=true",
            "--spring.jmx.enabled=false",
            "--management.tracing.sampling.probability=1.0",
            "--logging.level.root=WARN",
            "--logging.level.io.github.jdubois.bootui=WARN",
            "--logging.level.org.hibernate.SQL=WARN");

    public static void main(String[] args) throws Exception {
        if (args.length != 2
                || Runtime.version().feature() != 17
                || Runtime.getRuntime().availableProcessors() != 4) {
            throw new IllegalArgumentException("Requires Java17/CPU4, immutable baseline jar and output directory");
        }
        Path jar = Path.of(args[0]).toAbsolutePath();
        Path output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output);
        Path publishedRecording = output.resolve("runtime.jfr");
        require(!Files.exists(publishedRecording), "Published recording already exists");
        Path settings = output.resolve("safe-profile.jfc");
        require(Files.isRegularFile(settings), "Privacy-filtered profile settings are required");
        System.setProperty("sample.jar", jar.toString());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "baseline-only diagnostic sampling, not an overhead or budget measurement");
        result.put("source", "c9282079aee3c0e2458e651b93ae9c58c3a255b7");
        result.put("jarSha256", sha256(jar));
        result.put("java", System.getProperty("java.version"));
        result.put("host", System.getProperty("os.name"));
        result.put("processors", Runtime.getRuntime().availableProcessors());
        result.put("clients", CaptureOverheadBenchmarkTest.CONCURRENCY);
        result.put("controlsPassed", false);
        result.put("captureMeaning", "recorded counters count admissions, not completed dispatch or listener delivery");
        Path privateDirectory = Files.createTempDirectory("journal-profile-private-");
        Path recording = privateDirectory.resolve("runtime.jfr");
        ProcessHandle ownedApplication = null;
        try {
            try (SampleExecutableJar sample = SampleExecutableJar.start("journal-profile-baseline", JVM, ARGUMENTS)) {
                try {
                    JsonNode agent =
                            sample.probe().get("/bootui/api/java-agent").json();
                    require("NOT_ATTACHED".equals(agent.path("state").asText()), "Unexpected agent: " + agent);
                    URI uri = URI.create("http://localhost:" + sample.port() + CaptureOverheadBenchmarkTest.ROUTE);
                    HttpClient client = HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .build();
                    long warmRequests = CaptureOverheadBenchmarkTest.load(client, uri, Duration.ofSeconds(10)).stream()
                            .mapToLong(values -> values.length)
                            .sum();
                    JsonNode before = status(sample, warmRequests);
                    ProcessHandle child = ownApplication(jar);
                    ownedApplication = child;
                    long pid = child.pid();
                    result.put("pid", pid);
                    result.put("processCommand", child.info().command().orElseThrow());
                    result.put("processArguments", child.info().arguments().orElseThrow());
                    result.put("warmRequests", warmRequests);
                    String startResponse = command(
                            output,
                            "jfr-start",
                            "jcmd",
                            Long.toString(pid),
                            "JFR.start",
                            "name=" + RECORDING,
                            "settings=" + settings,
                            "duration=15s",
                            "maxsize=64m",
                            "filename=" + recording,
                            "dumponexit=true");
                    require(startResponse.contains("Started recording"), "Recording start was not acknowledged");
                    long started = System.nanoTime();
                    List<long[]> samples = CaptureOverheadBenchmarkTest.load(client, uri, Duration.ofSeconds(15));
                    double seconds = (System.nanoTime() - started) / 1e9;
                    long requests =
                            samples.stream().mapToLong(values -> values.length).sum();
                    require(requests > 0, "No profiled requests");
                    JsonNode after =
                            status(sample, before.path("recorded").path("http").asLong() + requests);
                    require(
                            after.path("enabled").asBoolean()
                                    && after.path("droppedEvents").isNumber()
                                    && after.path("droppedEvents").asLong() == 0,
                            "Disabled or lossy capture: " + after);
                    require(
                            after.path("recorded").path("http").asLong()
                                            - before.path("recorded")
                                                    .path("http")
                                                    .asLong()
                                    == requests,
                            "HTTP admissions differ from generated requests");
                    require(
                            after.path("recorded").path("sql").asLong()
                                    > before.path("recorded").path("sql").asLong(),
                            "No profiled SQL admissions");
                    require(
                            after.path("retainedBytes").isNumber()
                                    && after.path("maxBytes").isNumber()
                                    && after.path("retainedBytes").asLong()
                                            <= after.path("maxBytes").asLong()
                                    && after.path("retainedEvents").asLong()
                                            <= after.path("maxEvents").asLong(),
                            "Missing or exceeded retention bounds: " + after);
                    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                    int check = 0;
                    while (true) {
                        require(child.isAlive(), "Application exited during JFR completion");
                        String state = command(output, "jfr-check-" + check++, "jcmd", Long.toString(pid), "JFR.check");
                        if (!state.contains("name=" + RECORDING)) {
                            break;
                        }
                        require(System.nanoTime() < deadline, "Recording did not complete within 20 seconds");
                        Thread.sleep(250);
                    }
                    require(
                            Files.isRegularFile(recording)
                                    && Files.size(recording) > 0
                                    && Files.size(recording) <= 64L * 1024 * 1024,
                            "Missing or oversized recording");
                    String summary = command(output, "jfr-summary", "jfr", "summary", recording.toString());
                    Map<String, Object> privacyCounts = new LinkedHashMap<>();
                    for (String event : List.of(
                            "jdk.InitialEnvironmentVariable",
                            "jdk.InitialSystemProperty",
                            "jdk.SystemProcess",
                            "jdk.InitialSecurityProperty",
                            "jdk.DataLoss")) {
                        var match = Pattern.compile("(?m)^\\s*" + Pattern.quote(event) + "\\s+(\\d+)")
                                .matcher(summary);
                        if (!match.find()) {
                            require(
                                    event.equals("jdk.InitialSecurityProperty"),
                                    "Required summary row missing: " + event);
                            privacyCounts.put(event, "not present in recording metadata");
                        } else {
                            long count = Long.parseLong(match.group(1));
                            require(count == 0, "Forbidden metadata or recording loss count: " + event + "=" + count);
                            privacyCounts.put(event, count);
                        }
                    }
                    result.put("privacyAndRecordingLossCounts", privacyCounts);
                    result.put("requests", requests);
                    result.put("profiledLoadSeconds", seconds);
                    result.put("journalBefore", before);
                    result.put("journalAfter", after);
                    result.put("recordingSha256", sha256(recording));
                    result.put("recordingBytes", Files.size(recording));
                    Files.copy(recording, publishedRecording);
                    result.put("controlsPassed", true);
                    System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                } catch (Exception | AssertionError failure) {
                    result.put("failure", failure.toString());
                    throw failure;
                } finally {
                    Files.writeString(output.resolve("application-tail.log"), sample.tail());
                    JSON.writerWithDefaultPrettyPrinter()
                            .writeValue(output.resolve("controls.json").toFile(), result);
                }
            }
        } catch (Exception | AssertionError failure) {
            result.put("controlsPassed", false);
            result.put("failure", failure.toString());
            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(output.resolve("controls.json").toFile(), result);
            throw failure;
        } finally {
            // The owned sample has closed before deletion; dumponexit cannot recreate an uploadable file.
            if (ownedApplication != null && ownedApplication.isAlive()) {
                ownedApplication.destroyForcibly();
                ownedApplication.onExit().get(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(recording);
            Files.deleteIfExists(privateDirectory);
        }
    }

    private static ProcessHandle ownApplication(Path jar) {
        List<ProcessHandle> matches = ProcessHandle.current()
                .children()
                .filter(child -> {
                    String command = child.info().command().orElse("");
                    String[] arguments = child.info().arguments().orElse(new String[0]);
                    return Path.of(command).getFileName() != null
                            && Path.of(command).getFileName().toString().equals("java")
                            && Arrays.asList(arguments).contains("-jar")
                            && Arrays.asList(arguments).contains(jar.toString());
                })
                .toList();
        require(matches.size() == 1, "Expected exactly one matching own application child, found " + matches.size());
        return matches.get(0);
    }

    private static String command(Path output, String name, String executable, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.addAll(List.of(args));
        Path log = output.resolve(name + ".log");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            require(process.waitFor(5, TimeUnit.SECONDS), "Could not stop timed-out diagnostic command");
            throw new IllegalStateException("Diagnostic command timed out: " + name);
        }
        String text = Files.readString(log);
        require(process.exitValue() == 0, name + " failed: " + text);
        return text;
    }

    private static JsonNode status(SampleExecutableJar sample, long minimumHttp) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            JsonNode status = sample.probe().get("/bootui/api/activity/journal").json();
            require(status.path("queueDepth").isNumber(), "Missing queue depth");
            if (status.path("queueDepth").asInt() == 0
                    && status.path("recorded").path("http").asLong() >= minimumHttp) {
                return status;
            }
            require(System.nanoTime() < deadline, "Admissions did not settle: " + status);
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
