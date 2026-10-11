package io.github.jdubois.bootui.engine.journal;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Temporary identical fresh pending-frame/payload allocation workload; not StackWalker or HTTP accounting. */
public final class DispatchAllocationProbe {
    private static final com.sun.management.ThreadMXBean ALLOCATION =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final StackWalker.StackFrame[] FRAMES = {
        new Frame("com.example.Repository", "find", "Repository.java", 42),
        new Frame("com.example.Service", "search", "Service.java", 24),
        new Frame("com.example.Controller", "list", "Controller.java", 12)
    };
    private static volatile SqlPayload retainedSql;
    private static volatile HttpPayload retainedHttp;

    public static void main(String[] args) {
        if (Runtime.version().feature() != 17
                || !ALLOCATION.isThreadAllocatedMemorySupported()
                || !ALLOCATION.isCurrentThreadCpuTimeSupported()) {
            throw new IllegalStateException("Java17 CPU and allocation readings are required");
        }
        ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        ALLOCATION.setThreadCpuTimeEnabled(true);
        JournalDictionary dictionary = new JournalDictionary(100, 100_000);
        for (int i = 0; i < 20_000; i++) {
            operation(dictionary, i);
        }
        long duration = 1_000_000_000L;
        long cpu = ALLOCATION.getCurrentThreadCpuTime();
        long allocated = ALLOCATION.getCurrentThreadAllocatedBytes();
        if (cpu < 0 || allocated < 0) {
            throw new IllegalStateException("Negative starting CPU or allocation counter");
        }
        long started = System.nanoTime();
        int operations = 0;
        while (System.nanoTime() - started < duration) {
            for (int i = 0; i < 256; i++) {
                operation(dictionary, operations++);
            }
        }
        double elapsed = (System.nanoTime() - started) / 1e9;
        long endAllocated = ALLOCATION.getCurrentThreadAllocatedBytes();
        long endCpu = ALLOCATION.getCurrentThreadCpuTime();
        long bytes = endAllocated - allocated;
        long nanos = endCpu - cpu;
        if (endAllocated < 0
                || endCpu < 0
                || bytes <= 0
                || nanos <= 0
                || operations <= 0
                || operations % 256 != 0
                || !Double.isFinite(elapsed)
                || elapsed <= 0) {
            throw new IllegalStateException("Invalid measured counters or operation count");
        }
        if (!retainedSql
                        .frames()
                        .frames()
                        .equals(List.of(
                                "com.example.Repository.find(Repository.java:42)",
                                "com.example.Service.search(Service.java:24)",
                                "com.example.Controller.list(Controller.java:12)"))
                || retainedSql.estimatedBytes(dictionary) <= 0
                || !retainedHttp.path().equals("/orders/" + ((operations - 1) & 255))
                || dictionary.size() != 7
                || dictionary.bytes() != 817) {
            throw new AssertionError("Payload/dictionary output differs");
        }
        try {
            retainedSql.frames().frames().add("mutated");
            throw new AssertionError("Mutable retained frames");
        } catch (UnsupportedOperationException expected) {
            // Check outside the measured allocation region.
        }
        System.out.printf(
                Locale.ROOT,
                "label=%s operations=%d allocated_bytes=%d cpu_nanos=%d bytes_per_operation=%.6f"
                        + " cpu_ns_per_operation=%.6f wall_seconds=%.6f dictionary_entries=%d dictionary_bytes=%d java=%s"
                        + " processors=%d%n",
                args[0],
                operations,
                bytes,
                nanos,
                bytes / (double) operations,
                nanos / (double) operations,
                elapsed,
                dictionary.size(),
                dictionary.bytes(),
                System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors());
    }

    private static void operation(JournalDictionary dictionary, int i) {
        ApplicationFrames captured = ApplicationFrames.select(Stream.of(FRAMES));
        SqlPayload sql = new SqlPayload("select id from orders where customer_id = ?", null, "orders", false, captured);
        HttpPayload http = new HttpPayload("GET", "/orders/" + (i & 255), "/orders/{id}", null, 200);
        retainedSql = (SqlPayload) sql.interned(dictionary);
        retainedHttp = (HttpPayload) http.interned(dictionary);
    }

    private record Frame(String className, String methodName, String fileName, int lineNumber)
            implements StackWalker.StackFrame {
        public String getClassName() {
            return className;
        }

        public String getMethodName() {
            return methodName;
        }

        public Class<?> getDeclaringClass() {
            throw new UnsupportedOperationException();
        }

        public int getByteCodeIndex() {
            return 0;
        }

        public String getFileName() {
            return fileName;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public boolean isNativeMethod() {
            return false;
        }

        public StackTraceElement toStackTraceElement() {
            return new StackTraceElement(className, methodName, fileName, lineNumber);
        }
    }
}
