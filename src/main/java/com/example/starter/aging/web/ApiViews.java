package com.example.starter.aging.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 接口响应视图。日期均为 UTC 自然日，时刻均为 UTC 瞬间。
 */
public final class ApiViews {

    private ApiViews() {
    }

    /** 批次视图：status 为 AGING 或 RELEASED。 */
    public record BatchView(long id, String batchCode, Instant entryAt, int targetDays, String status) {
    }

    /** 翻面记录视图。 */
    public record TurnView(long id, long batchId, LocalDate turnDate, String requestId,
                           String operator, String note, Instant createdAt) {
    }

    /** 测温记录视图：status 为 VALID 或 VOID。 */
    public record TempView(long id, long batchId, LocalDate tempDate, BigDecimal celsius,
                           String status, String recordedBy, Instant createdAt) {
    }

    /** 作废结果：voidedId 为被作废记录，replacement 为替代记录（可空）。 */
    public record VoidResultView(long voidedId, TempView replacement) {
    }

    /** 放行快照项视图：固化当日的翻面与温度记录编号。 */
    public record SnapshotItemView(LocalDate dayDate, long turnId, long temperatureId) {
    }

    /** 放行视图，含七天快照。 */
    public record ReleaseView(long releaseId, long batchId, String requestId, Instant releasedAt,
                              List<SnapshotItemView> snapshot) {
    }

    /** 放行引用。 */
    public record ReleaseRef(long releaseId, String requestId, Instant releasedAt) {
    }

    /** 当日翻面引用。 */
    public record TurnRef(long id, String operator, Instant createdAt) {
    }

    /** 当日有效温度引用。 */
    public record TempRef(long id, BigDecimal celsius, String recordedBy, Instant createdAt) {
    }

    /** 日期轴上的一天：含翻面、有效温度与放行快照引用。 */
    public record DayView(LocalDate date, TurnRef turn, List<TempRef> validTemperatures,
                          SnapshotItemView releaseSnapshot) {
    }

    /** 批次看板：日期轴、当日有效温度与放行引用。 */
    public record BoardView(long batchId, String batchCode, Instant entryAt, int targetDays,
                            String status, ReleaseRef release, List<DayView> days) {
    }
}