package com.example.starter.baggage;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 联程行李滞留监控 API 的请求与响应 DTO。
 * 所有时刻字段均为 UTC（{@code java.time.Instant} 序列化字符串），滞留区间按左闭右开解释。
 * 涉及数量、时长的响应一律同时给出实际值、要求值或差额，null 语义见各字段注释。
 */
public final class DwellDtos {

    private DwellDtos() {
    }

    /** 中转点滞留配置请求：maxDwellMinutes 为该中转点统一滞留上限（要求值，分钟）。 */
    public record TransitPointConfigRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "stationCode 不能为空") String stationCode,
            @NotNull(message = "maxDwellMinutes 不能为空")
            @Positive(message = "maxDwellMinutes 必须为正整数") Integer maxDwellMinutes) {
    }

    /** 航段装载约束请求：capacity/checkinDeadline 可同时或单独为 null，null 表示该项不限制。 */
    public record LegConstraintConfigRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "legId 不能为空") String legId,
            @Positive(message = "capacity 必须为正整数") Integer capacity,
            String checkinDeadline) {
    }

    /** 批量到达核对中的单件：bagTag 与到达中转点时刻（UTC 字符串，缺失/非法由业务校验返回 422）。 */
    public record TransitArrivalItem(
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            String arrivedAt) {
    }

    /** 批量到达核对请求：任一袋号状态非法则整次回滚。operator 为登记人工号。 */
    public record TransitArrivalRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "stationCode 不能为空") String stationCode,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotNull(message = "items 不能为空")
            @Size(min = 1, max = 20, message = "批量到达数量必须为 1~20")
            List<@NotNull(message = "到达项不能为空") @Valid TransitArrivalItem> items) {
    }

    /** 超时判定请求：bagTags 为空表示判定该中转点全部在滞行李；operator 为裁决人工号。 */
    public record DwellCheckRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "stationCode 不能为空") String stationCode,
            @NotBlank(message = "operator 不能为空") String operator,
            @Size(max = 20, message = "单次超时判定数量不能超过 20") List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /** 解除阻断请求：复核人必须指定下一航段，服务重新校验截单（左闭右开）与容量。 */
    public record DwellReleaseRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotBlank(message = "nextLegId 不能为空") String nextLegId,
            @NotBlank(message = "reviewer 不能为空") String reviewer,
            @NotBlank(message = "conclusion 不能为空") String conclusion) {
    }

    /** 改派请求：为已解除阻断的行李登记下一航段变更；不改写既有超时证据。 */
    public record ReassignRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotBlank(message = "nextLegId 不能为空") String nextLegId,
            @NotBlank(message = "operator 不能为空") String operator) {
    }

    /** 中转点配置响应。 */
    public record TransitPointConfigResponse(String stationCode, int maxDwellMinutes) {
    }

    /** 航段约束响应：capacity/checkinDeadline 为 null 表示该项未配置（不限制）。 */
    public record LegConstraintResponse(String legId, Integer capacity, String checkinDeadline) {
    }

    /** 单件到达登记结果。 */
    public record TransitArrivalItemResult(String bagTag, String station, String arrivedAt,
                                          int maxDwellMinutes, String deadlineAt) {
    }

    /** 批量到达核对响应：items 按 bagTag 稳定排序。 */
    public record TransitArrivalResponse(String stationCode, int count,
                                        List<TransitArrivalItemResult> items) {
    }

    /** 滞留诊断项：actual 为实际滞留分钟，max 为要求值，difference 为差额（超正余负口径见字段）。 */
    public record DwellItem(String bagTag, String station, String dwellStatus, boolean blocked,
                           String arrivedAt, String deadlineAt, Long maxDwellMinutes,
                           long actualDwellMinutes, Long differenceMinutes, String checkedAt) {
    }

    /** 超时判定响应：results 按 bagTag 稳定排序。 */
    public record DwellCheckResponse(String stationCode, String checkedAt,
                                     int exceededCount, List<DwellItem> results) {
    }

    /** 解除阻断响应：含实际滞留、要求上限、差额及下一航段容量实际值/要求值/剩余差额。 */
    public record DwellReleaseResponse(String bagTag, String status, String station,
                                      String nextLegId, String reviewer, String releasedAt,
                                      String arrivedAt, long actualDwellMinutes,
                                      int maxDwellMinutes, long differenceMinutes,
                                      Integer legCapacity, int legLoadedCount,
                                      Integer legRemainingCapacity, String checkinDeadline) {
    }

    /** 改派响应。 */
    public record ReassignResponse(String bagTag, String nextLegId, String operator,
                                   String reassignedAt) {
    }

    /** 不可变滞留历史记录项：字段顺序与 null 语义长期稳定。 */
    public record DwellRecordDto(int seq, String recordType, String station, String arrivedAt,
                                 String eventTime, Long maxDwellMinutes, Long actualDwellMinutes,
                                 Long differenceMinutes, String deadlineAt, String nextLegId,
                                 String reviewer, String conclusion, String operator,
                                 Integer legCapacity, Integer legLoadedCount,
                                 Integer legRemainingCapacity, String checkinDeadline) {
    }

    /** 行李滞留明细：current 为当前开放周期（无则 null），history 按 seq 升序不可变。 */
    public record DwellDetailResponse(String bagTag, DwellItem current,
                                      List<DwellRecordDto> history) {
    }

    /** 滞留诊断列表响应：items 按 (station, bagTag) 稳定排序。 */
    public record DwellDiagnosticsResponse(String checkedAt, List<DwellItem> items) {
    }
}
