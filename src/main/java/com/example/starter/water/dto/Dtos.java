package com.example.starter.water.dto;

import java.util.List;

/**
 * 灌区配水 API 请求/响应契约。水量在 API 层一律使用十进制字符串（单位立方米，最多 3 位小数），
 * 时间使用 ISO-8601 UTC 字符串。
 */
public final class Dtos {

    private Dtos() {
    }

    /** 创建供水窗口命令。 */
    public record CreateWindowRequest(String commandKey, String windowKey, String channelId,
                                      String startUtc, String endUtc, String plannedVolume) {
    }

    /** 供水窗口视图；activeCurtailmentVolume 为 null 表示无生效限供。 */
    public record WindowResponse(long id, String windowKey, String channelId, String startUtc, String endUtc,
                                 String plannedVolume, String activeCurtailmentVolume, String availableTotal,
                                 String createdUtc) {
    }

    /** 提交配水申请命令；申请人由 X-Actor-Id 请求头提供。 */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount) {
    }

    /**
     * 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。
     * amount 为不可改写的原申请水量；heldAmount 为当前持有额度（REQUESTED 为 0，普通批准时等于原水量，
     * 转出时等额扣减，取消时归零；持有额度恰为零的已转出申请仍为 APPROVED）。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String requester, String status,
                                     String createdUtc, String updatedUtc) {
    }

    /** 仅含幂等键的命令（批准/取消申请、取消限供）。 */
    public record CommandRequest(String commandKey) {
    }

    /** 创建限供命令。 */
    public record CurtailmentRequest(String commandKey, String volume) {
    }

    /** 限供视图。状态：ACTIVE / CANCELLED；cancelledUtc 未取消时为 null。 */
    public record CurtailmentResponse(long id, long windowId, String volume, String status,
                                      String createdUtc, String cancelledUtc) {
    }

    /** 窗口当前可用容量视图；approvedTotal 汇总 APPROVED 申请的当前持有额度。 */
    public record CapacityResponse(long windowId, String plannedVolume, String activeCurtailmentVolume,
                                   String availableTotal, String approvedTotal, String remaining) {
    }

    /** 同窗口额度转让命令；操作人（源申请人）由 X-Actor-Id 请求头提供。 */
    public record TransferRequest(String commandKey, String transferKey, String sourceAllocationKey,
                                  String targetAllocationKey) {
    }

    /** 转让流水视图，创建后不可变，不提供撤销。 */
    public record TransferResponse(String transferKey, long windowId, String sourceAllocationKey,
                                   String targetAllocationKey, String amount, String actor, String createdUtc) {
    }

    /** 窗口转让流水列表视图。 */
    public record TransferListResponse(long windowId, List<TransferResponse> transfers) {
    }

    /** 窗口历史明细：窗口本身 + 全部申请 + 全部限供记录。 */
    public record HistoryResponse(WindowResponse window, List<AllocationResponse> allocations,
                                  List<CurtailmentResponse> curtailments) {
    }

    /** 渠道计量规则版本配置命令；每次配置产生一个不可变新版本。 */
    public record ChannelConfigRequest(String commandKey, String channelId, String maxFlowRate,
                                       String maxIntervalMinutes, String effectiveFromUtc) {
    }

    /** 规则版本视图，创建后不可变。 */
    public record ChannelConfigResponse(long id, String channelId, int version, String maxFlowRate,
                                        String maxIntervalMinutes, String effectiveFromUtc,
                                        String actor, String createdUtc) {
    }

    /** 单条累计读数输入：readingAtUtc 为采样 UTC 时刻，cumulativeReading 为非负累计读数（立方米）。 */
    public record ReadingInput(String readingAtUtc, String cumulativeReading) {
    }

    /** 批量读数提交命令；整批先完整校验，任一错误整批不写入。 */
    public record ReadingsRequest(String commandKey, String channelId, List<ReadingInput> readings) {
    }

    /** 异常视图（历史证据，字段、排序、时间精度与 null 含义稳定）。 */
    public record AnomalyResponse(long id, String channelId, String type, int ruleVersion,
                                  String windowStartUtc, String windowEndUtc,
                                  String previousReadingAtUtc, String previousValue,
                                  String readingAtUtc, String readingValue,
                                  String actualValue, String requiredValue, String difference,
                                  String metric, String reporter, String status,
                                  Long resolutionId, String createdUtc,
                                  ResolutionResponse resolution) {
    }

    /** 异常解除证据视图；未解除的异常在 anomaly 视图中为 null。 */
    public record ResolutionResponse(long id, long anomalyId, String resolver, String diagnosisNote,
                                     String replacementAtUtc, String replacementValue, String createdUtc) {
    }

    /** 入库读数视图，创建后不可变。 */
    public record ReadingResponse(long id, String channelId, String readingAtUtc, String cumulativeReading,
                                  int ruleVersion, String reporter, String createdUtc) {
    }

    /** 批量读数提交结果：入库读数与本批新生成异常（均按提交顺序）。 */
    public record ReadingsResponse(String channelId, List<ReadingResponse> storedReadings,
                                   List<AnomalyResponse> anomalies) {
    }

    /** 异常解除命令；操作人由 X-Actor-Id 提供且必须不同于异常读数提交人。 */
    public record ResolveAnomalyRequest(String commandKey, String diagnosisNote,
                                        String replacementAtUtc, String replacementValue) {
    }

    /** 实际核销命令；生效时刻由 atUtc 给出，操作人由 X-Actor-Id 提供。 */
    public record WriteoffRequest(String commandKey, String writeoffKey, String allocationKey,
                                  String amount, String atUtc) {
    }

    /** 核销流水视图，创建后不可变。 */
    public record WriteoffResponse(String writeoffKey, long windowId, String channelId,
                                   String allocationKey, String amount, String actor,
                                   String atUtc, String heldAmountAfter, String createdUtc) {
    }

    /** 窗口核销流水列表视图（按提交顺序）。 */
    public record WriteoffListResponse(long windowId, List<WriteoffResponse> writeoffs) {
    }

    /** 渠道读数与异常历史（均不可变，按时间/主键稳定排序）。 */
    public record ChannelHistoryResponse(String channelId, List<ReadingResponse> readings,
                                         List<AnomalyResponse> anomalies,
                                         List<ChannelConfigResponse> ruleVersions) {
    }

    /** 异常诊断明细：异常证据 + 解除证据（未解除时 resolution 为 null）。 */
    public record AnomalyDetailResponse(AnomalyResponse anomaly, ResolutionResponse resolution) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
