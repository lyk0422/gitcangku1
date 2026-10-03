package com.example.starter.kiln;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 窑炉批次接口出入参。时间字段一律为 UTC 的 ISO-8601 时刻，温度单位为摄氏度。
 */
public final class KilnDtos {

    private KilnDtos() {
    }

    /** 程序段入参：startUtc（含）到 endUtc（不含），段间必须首尾相接。 */
    public record SegmentInput(@NotNull Instant startUtc, @NotNull Instant endUtc,
                               @NotNull Double targetTemp, @NotNull Double tolerance) {
    }

    /** 创建升温程序版本请求。 */
    public record CreateProgramRequest(@NotBlank String name, @NotBlank String version,
                                       @NotEmpty List<@Valid SegmentInput> segments) {
    }

    /** 创建批次（入炉）请求，programId 指向的程序版本将被锁定。 */
    public record CreateBatchRequest(@NotBlank String batchNo, @NotNull Long programId) {
    }

    /** 单个测温点入参。 */
    public record PointInput(@NotNull Instant measuredAt, @NotNull Double temperature) {
    }

    /** 测温上传请求：requestId 幂等，同参重放返回原结果，异参 409。 */
    public record UploadPointsRequest(@NotBlank String requestId,
                                      @NotEmpty List<@Valid PointInput> points) {
    }

    /** 异常标记请求。 */
    public record MarkAnomalyRequest(@NotBlank String reason, @NotBlank String markedBy) {
    }

    /** 质检判定请求：decision 取 RELEASE 或 REJECT。 */
    public record ReleaseRequest(@NotBlank String decision, @NotBlank String inspector) {
    }

    /** 测温上传结果：replayed=true 表示幂等重放，未重复写入。 */
    public record UploadResult(boolean replayed, int inserted) {
    }

    /** 轨迹校核违规项：给出具体段号与异常时刻。 */
    public record Violation(String type, Integer segmentSeq, Instant time, String message) {
    }

    /** 单段偏差统计。 */
    public record SegmentDeviation(int segmentSeq, double worstDeviation, int sampleCount) {
    }

    /** 放行/驳回结果。 */
    public record ReleaseResult(long recordId, long batchId, String decision, String programVersion,
                                int pointsLimit, List<SegmentDeviation> deviations,
                                String inspector, Instant decidedAt, boolean replayed) {
    }

    /** 批次详情中的程序段视图。 */
    public record SegmentView(int seq, Instant startUtc, Instant endUtc, double targetTemp,
                              double tolerance, Double worstDeviation, int sampleCount) {
    }

    /** 异常标记视图。 */
    public record AnomalyView(long id, String reason, String markedBy, Instant markedAt) {
    }

    /** 批次详情：程序版本、各段最坏偏差与放行依据。 */
    public record BatchDetail(long batchId, String batchNo, String status, long programId,
                              String programVersion, Long refireOf, List<SegmentView> segments,
                              List<AnomalyView> anomalies, ReleaseResult release) {
    }
}