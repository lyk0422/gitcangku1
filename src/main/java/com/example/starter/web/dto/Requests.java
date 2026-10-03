package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** 写接口请求体。所有请求携带 requestId 用于幂等；重量单位公斤，时间为 UTC 的 ISO-8601。 */
public final class Requests {

    private Requests() {
    }

    public record CreateTicketRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 32) String transportOrderNo,
            @NotBlank @Size(max = 32) String vehicleNo) {
    }

    public record TareRequest(
            @NotBlank @Size(max = 64) String requestId,
            @Positive int tareKg,
            @NotNull Instant tareTimeUtc) {
    }

    public record GrossRequest(
            @NotBlank @Size(max = 64) String requestId,
            @Positive int grossKg,
            @NotNull Instant grossTimeUtc) {
    }

    public record ReweighSubmitRequest(
            @NotBlank @Size(max = 64) String requestId,
            @Positive int grossKg,
            @NotBlank @Size(max = 256) String reason) {
    }

    public record AdjudicateRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank String adoptedSource,
            Long reweighId,
            @NotBlank @Size(max = 256) String reason,
            @NotBlank @Size(max = 64) String adjudicator) {
    }

    public record FreezeRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotNull List<Long> expectedReweighIds) {
    }

    public record ErrataSubmitRequest(
            @NotBlank @Size(max = 64) String requestId,
            @Positive int newGrossKg,
            @NotBlank @Size(max = 256) String reason) {
    }
}