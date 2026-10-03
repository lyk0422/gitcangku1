package com.example.starter.repository;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 数据库行记录集合，与 schema.sql 中各表一一对应；时间列均为 UTC。
 */
public final class Rows {

    private Rows() {
    }

    /**
     * curtailment_directive 行；confirmed* 字段未确认时为 null。
     */
    public record DirectiveRow(
            long id,
            String unitId,
            Instant startUtc,
            Instant endUtc,
            int maxPowerKw,
            String status,
            int directiveVersion,
            String confirmRequestId,
            String confirmedBy,
            Instant confirmedUtc) {
    }

    /**
     * directive_sample 行；同一刻度多版本时按 sampleVersion 区分。
     */
    public record SampleRow(
            long id,
            long directiveId,
            Instant tickUtc,
            int sampleVersion,
            int avgPowerKw,
            Instant reportedUtc) {
    }

    /**
     * reconciliation 行。
     */
    public record ReconRow(
            long id,
            long directiveId,
            int reconVersion,
            int directiveVersion,
            String requestId,
            String auditor,
            BigDecimal totalEnergyKwh,
            Instant createdUtc) {
    }

    /**
     * reconciliation_sample 行，对账时固化的刻度快照。
     */
    public record FrozenSampleRow(
            Instant tickUtc,
            int sampleVersion,
            int avgPowerKw,
            BigDecimal energyKwh) {
    }

    /**
     * reconciliation_difference 行，差额链节点。
     */
    public record DifferenceRow(
            long id,
            long reconciliationId,
            long previousReconciliationId,
            BigDecimal deltaKwh,
            Instant createdUtc) {
    }
}