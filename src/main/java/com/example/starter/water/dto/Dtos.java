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

    // ===== 配水计量传感异常与核销（本题新增） =====

    /** 配置渠道传感规则命令；规则版本由系统按渠道递增。 */
    public record SensorRuleRequest(String commandKey, String channelId, String maxFlowRate,
                                    Integer maxGapMinutes) {
    }

    /** 传感规则版本视图，版本一经提交不可变。 */
    public record SensorRuleResponse(long id, String channelId, int ruleVersion, String maxFlowRate,
                                     int maxGapMinutes, String actor, String createdUtc) {
    }

    /** 批次中的单个累计读数；readingUtc 为 UTC 时刻，cumulative 为非负累计读数（立方米）。 */
    public record ReadingItem(String readingUtc, String cumulative) {
    }

    /** 提交一批传感读数命令；批次先完整校验再整体写入，任一错误不写入任何读数。 */
    public record ReadingBatchRequest(String commandKey, String batchKey, String channelId,
                                      List<ReadingItem> readings) {
    }

    /** 单条读数视图，写入后不可变。 */
    public record ReadingResponse(long id, String batchKey, String channelId, int seqNo, String readingUtc,
                                  String cumulative, int ruleVersion, String actor, String createdUtc) {
    }

    /**
     * 读数批次提交结果。anomalies 为本批命中的全部异常（含实际值/要求值/差额），按读数对顺序稳定排序；
     * frozen=true 表示本批使渠道进入冻结（存在未解除异常）。
     */
    public record ReadingBatchResponse(String batchKey, String channelId, List<ReadingResponse> readings,
                                       List<AnomalyResponse> anomalies, boolean frozen, String createdUtc) {
    }

    /** 异常视图；actual* 为实际值，required* 为要求值，差额随字段给出，生成后不可变。 */
    public record AnomalyResponse(long id, String channelId, String batchKey, int ruleVersion,
                                  List<String> reasons, String fromReadingUtc, String toReadingUtc,
                                  String fromCumulative, String toCumulative,
                                  String actualDelta, String actualGapMinutes, String actualFlowRate,
                                  String requiredMaxFlowRate, int requiredMaxGapMinutes,
                                  String flowOverBy, String gapOverByMinutes,
                                  String detectorActor, String createdUtc,
                                  ResolutionResponse resolution, boolean resolved) {
    }

    /** 异常解除视图，不改写原异常或既有核销。 */
    public record ResolutionResponse(long id, long anomalyId, String diagnosisNote,
                                     String replacementCumulative, String replacementReadingUtc,
                                     String resolverActor, String createdUtc) {
    }

    /** 替代读数视图（诊断用，来自不可变解除记录）。 */
    public record ReplacementReadingResponse(long anomalyId, String replacementCumulative,
                                             String replacementReadingUtc, String resolverActor,
                                             String createdUtc) {
    }

    /** 解除异常命令：必须由不同于异常提交人的操作人确认诊断说明与替代读数。 */
    public record ResolveAnomalyRequest(String commandKey, String diagnosisNote,
                                        String replacementCumulative) {
    }

    /** 实际核销命令；atUtc 为核销发生的 UTC 时刻，渠道冻结期间返回 422。 */
    public record WriteOffRequest(String commandKey, String writeOffKey, String allocationKey,
                                  String amount, String atUtc) {
    }

    /** 核销流水视图，写入后不可变。 */
    public record WriteOffResponse(long id, String writeOffKey, String allocationKey, long windowId,
                                   String channelId, String amount, String atUtc, String actor,
                                   String createdUtc) {
    }

    /** 渠道诊断视图：当前规则、冻结状态、全部异常（含解除）与替代读数。 */
    public record ChannelDiagnosticsResponse(String channelId, SensorRuleResponse currentRule,
                                             boolean frozen, List<AnomalyResponse> anomalies,
                                             List<ReplacementReadingResponse> replacementReadings) {
    }

    /** 渠道读数历史视图，按读数时刻升序（同刻按批次提交先后），跨全部批次。 */
    public record ChannelReadingsResponse(String channelId, List<ReadingResponse> readings) {
    }

    /**
     * 申请核销明细视图，按核销发生时刻升序（同刻按提交先后）。
     * availableAmount 为核销基数（当前持有额度 + 已核销总量，单位立方米），
     * writtenOffTotal 为实际已核销总量，remaining 为当前剩余可核销额度。
     */
    public record WriteOffListResponse(String allocationKey, long windowId, String channelId,
                                       String availableAmount, String writtenOffTotal, String remaining,
                                       List<WriteOffResponse> writeOffs) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
