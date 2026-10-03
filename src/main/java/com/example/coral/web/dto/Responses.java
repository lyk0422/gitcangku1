package com.example.coral.web.dto;

import java.time.Instant;
import java.util.List;

import com.example.coral.domain.PlantStatus;
import com.example.coral.domain.QuadratStatus;

/**
 * 响应体DTO：查询展示来源苗圃、当前样方、复核链与封存数量。
 */
public final class Responses {

    private Responses() {
    }

    /** 复核链中的一条复核记录。 */
    public record ReviewEntry(String requestId, PlantStatus result, String note,
            Instant reviewedAt) {
    }

    /** 单株详情：来源苗圃、当前样方、当前状态与完整复核链。 */
    public record TransplantDetail(String plantCode, String nursery, String quadratCode,
            PlantStatus status, Instant transplantedAt, List<ReviewEntry> reviews) {
    }

    /** 复核操作响应。 */
    public record ReviewResult(String requestId, String plantCode, PlantStatus result,
            Instant reviewedAt) {
    }

    /** 封存快照：各状态数量与植株清单（含死亡）。 */
    public record SealView(String quadratCode, int aliveCount, int deadCount, int pendingCount,
            List<String> plantCodes, Instant sealedAt) {
    }

    /** 样方详情：状态、活跃数、各状态数量、植株清单与封存快照。 */
    public record QuadratDetail(String quadratCode, String name, QuadratStatus status,
            int activeCount, int aliveCount, int deadCount, int pendingCount,
            List<TransplantDetail> plants, SealView seal) {
    }

    /** 错误响应。 */
    public record ErrorBody(String error, String message) {
    }
}