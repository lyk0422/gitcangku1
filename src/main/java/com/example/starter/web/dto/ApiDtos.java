package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * API 请求/响应模型；所有时刻均为 UTC 的 ISO-8601  instant。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record RegisterTagRequest(@NotBlank String tagCode, @NotBlank String stationCode) {
    }

    public record TagResponse(String tagCode, String holderStationCode, String status, Instant registeredAt) {
    }

    public record AttachRequest(
            @NotBlank String requestId,
            @NotBlank String tagCode,
            @NotBlank String animalCode,
            @NotBlank String stationCode,
            @NotNull Instant startAt) {
    }

    public record DetachRequest(
            @NotBlank String tagCode,
            @NotBlank String stationCode,
            @NotNull Instant endAt) {
    }

    public record AttachmentResponse(Long id, String tagCode, String animalCode, String stationCode,
            Instant startAt, Instant endAt) {
    }

    public record TransferInitRequest(
            @NotBlank String requestId,
            @NotBlank String tagCode,
            @NotBlank String fromStationCode,
            @NotBlank String toStationCode,
            @NotNull Instant initiatedAt) {
    }

    public record TransferConfirmRequest(@NotBlank String stationCode, @NotNull Instant confirmedAt) {
    }

    public record TransferResponse(Long id, String tagCode, String fromStationCode, String toStationCode,
            String status, Instant initiatedAt, Instant confirmedAt) {
    }

    public record RejectionResponse(String operation, String reason, Instant occurredAt) {
    }

    public record TagTimelineResponse(String tagCode, String holderStationCode, String status,
            List<AttachmentResponse> attachments, List<TransferResponse> transfers,
            List<RejectionResponse> rejections) {
    }

    public record AnimalTimelineResponse(String animalCode, List<AttachmentResponse> attachments,
            List<RejectionResponse> rejections) {
    }

    public record ErrorResponse(String code, String message) {
    }
}