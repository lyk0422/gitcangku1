package com.example.starter.web.dto;

import com.example.starter.service.ReconcileIssue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 限发接口响应 DTO 集合；时间字段一律为 UTC，电量单位 kWh，功率单位 kW。
 */
public final class Responses {

    private Responses() {
    }

    /**
     * 指令视图。
     *
     * @param directiveVersion 指令版本号，对账时固化
     * @param confirmedBy      确认人；未确认为 null
     * @param confirmedUtc     确认时间（UTC）；未确认为 null
     */
    public record DirectiveResponse(
            long id,
            String unitId,
            Instant startUtc,
            Instant endUtc,
            int maxPowerKw,
            String status,
            int directiveVersion,
            String confirmedBy,
            Instant confirmedUtc) {
    }

    /**
     * 采样视图（每刻度取最新版本）。
     */
    public record ReadingResponse(
            Instant tickUtc,
            int sampleVersion,
            int avgPowerKw,
            Instant reportedUtc) {
    }

    /**
     * 对账固化的单个采样刻度。
     */
    public record FrozenSampleView(
            Instant tickUtc,
            int sampleVersion,
            int avgPowerKw,
            BigDecimal energyKwh) {
    }

    /**
     * 对账单视图。
     *
     * @param reconVersion     同一指令内的对账版本
     * @param directiveVersion 固化的指令版本
     * @param totalEnergyKwh   累计电量，单位 kWh
     */
    public record ReconciliationResponse(
            long id,
            long directiveId,
            int reconVersion,
            int directiveVersion,
            String auditor,
            BigDecimal totalEnergyKwh,
            Instant createdUtc,
            List<FrozenSampleView> samples) {
    }

    /**
     * 差额链记录视图。
     */
    public record DifferenceResponse(
            long id,
            long reconciliationId,
            long previousReconciliationId,
            BigDecimal deltaKwh,
            Instant createdUtc) {
    }

    /**
     * 统一错误响应；对账拒绝时 issues 携带问题刻度，且不生成部分结果。
     */
    public record ErrorResponse(
            String message,
            List<ReconcileIssue> issues) {
    }
}