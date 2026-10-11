package io.github.jdubois.bootui.core.dto;

import java.util.List;

/** Anonymous, self-reported community feedback, not evidence that a check is correct. */
public record InsightFeedbackAggregateDto(
        String kind, long votes, List<Long> stars, double average, long evaluatedVotes, long reportedFindings) {
    public InsightFeedbackAggregateDto {
        stars = DtoCollections.immutableCopy(stars);
    }
}
