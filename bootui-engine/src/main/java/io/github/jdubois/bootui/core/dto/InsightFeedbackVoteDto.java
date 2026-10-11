package io.github.jdubois.bootui.core.dto;

/** An explicitly submitted rating; the backend derives the finding count from its current local check. */
public record InsightFeedbackVoteDto(String kind, int stars) {}
