package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 出参 DTO 集合。
 */
public final class Responses {

    private Responses() {
    }

    /**
     * 层位视图。
     */
    public record StratumResponse(long id, String siteCode, String stratumNo, String description,
            Instant createdAt) {
    }

    /**
     * 封签状态记录视图。
     */
    public record SealEventView(long id, String eventType, String sealNo, String note, Instant recordedAt) {
    }

    /**
     * 样本视图：展示层位、坐标格、所在包版本与每次封签状态。
     */
    public record SampleResponse(long id, String sampleNo, String siteCode, String stratumNo, String gridRef,
            Instant collectedAt, String status, String packageNo, Integer packageVersion, String packageStatus,
            String sealNo, List<SealEventView> sealEvents) {
    }

    /**
     * 封存包视图：展示版本、样本清单与每次封签状态。
     */
    public record PackageResponse(long id, String packageNo, String siteCode, String stratumNo, String status,
            String sealNo, int version, List<String> items, List<SealEventView> sealEvents,
            Long supersedesPackageId, Long sourceEventId, Instant createdAt, Instant handedOverAt) {
    }
}