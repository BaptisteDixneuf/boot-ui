package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JournalOverheadBudgetTests {

    @Test
    void theJournalThroughputBudgetIsTenPercent() {
        assertThat(JournalOverheadBenchmarkIT.TARGET_PERCENT).isEqualTo(10);
    }

    @Test
    void overheadAtOrBelowTheBudgetPasses() {
        assertThat(JournalOverheadBenchmarkIT.verdict(0)).isEqualTo("PASS");
        assertThat(JournalOverheadBenchmarkIT.verdict(9.9)).isEqualTo("PASS");
        assertThat(JournalOverheadBenchmarkIT.verdict(10)).isEqualTo("PASS");
    }

    @Test
    void measuredMissesStillFailTheRevisedBudget() {
        assertThat(JournalOverheadBenchmarkIT.verdict(10.1)).isEqualTo("FAIL");
        assertThat(JournalOverheadBenchmarkIT.verdict(14.1)).isEqualTo("FAIL");
    }
}
