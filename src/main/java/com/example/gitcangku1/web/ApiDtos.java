package com.example.gitcangku1.web;

import com.example.gitcangku1.domain.MeasureType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** API 请求体。 */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record CreateBatchRequest(
            @NotBlank @Size(max = 64) String batchNo,
            @NotNull Double targetL,
            @NotNull Double targetA,
            @NotNull Double targetB,
            @Min(1) @Max(30) int expectedRolls) {
    }

    public record UpdateTargetRequest(
            @NotNull Double targetL,
            @NotNull Double targetA,
            @NotNull Double targetB) {
    }

    public record MeasurementRequest(
            @NotBlank @Size(max = 64) String requestId,
            @Min(1) @Max(30) int rollNo,
            @NotNull MeasureType type,
            @NotNull Double l,
            @NotNull Double a,
            @NotNull Double b,
            @NotNull Instant measuredAt) {
    }

    public record AdjudicationRequest(
            @NotNull MeasureType decision,
            @NotBlank @Size(max = 64) String inspector) {
    }

    public record ReleaseRequest(
            @NotBlank @Size(max = 64) String requestId,
            Integer expectedTargetVersion) {
    }

    public record ErrataRequest(
            @NotBlank @Size(max = 1024) String note) {
    }
}