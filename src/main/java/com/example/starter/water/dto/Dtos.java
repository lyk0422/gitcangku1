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

    // ------------------------------------------------------------------
    // 配水传感计量
    // ------------------------------------------------------------------

    /**
     * 配置渠道计量点异常规则命令；首次调用注册渠道并生成版本 1，之后每次调用只追加新版本。
     * 操作人由 X-Actor-Id 请求头提供。
     */
    public record ConfigureChannelRequest(String commandKey, String maxFlowRate, Integer maxIntervalMinutes) {
    }

    /** 单个规则版本视图，只追加不改写；时间为该版本生效时刻（UTC）。 */
    public record RuleVersionResponse(int version, String maxFlowRate, int maxIntervalMinutes,
                                      String createdBy, String createdUtc) {
    }

    /**
     * 渠道计量点当前视图。frozen 为派生状态：存在未解除异常即 true；
     * activeAnomalyCount 为未解除异常数量；currentRuleVersion 为最新规则版本号。
     */
    public record ChannelResponse(String channelId, boolean frozen, int activeAnomalyCount,
                                  Integer currentRuleVersion, String maxFlowRate, Integer maxIntervalMinutes,
                                  String createdBy, String createdUtc, List<RuleVersionResponse> versions) {
    }

    /** 单个累计读数；时间为测量时刻（UTC），cumulativeVolume 单位立方米。 */
    public record ReadingItem(String readingUtc, String cumulativeVolume) {
    }

    /** 批量提交读数命令；操作人由 X-Actor-Id 请求头提供。 */
    public record SubmitReadingsRequest(String commandKey, List<ReadingItem> readings) {
    }

    /**
     * 读数视图，提交后不可变。readingUtc 为测量时刻（UTC 纳秒精度）；
     * cumulativeVolume 为不可改写的原始累计值；ruleVersion 为提交时规则版本快照。
     */
    public record ReadingResponse(long readingId, String channelId, String readingUtc, String cumulativeVolume,
                                  int batchSeq, String submitter, int ruleVersion, String commandKey,
                                  String createdUtc) {
    }

    /**
     * 异常解除（诊断）视图；异常未解除时 resolution 为 null，解除后字段不可变。
     * replacementReadingUtc/replacementCumulativeVolume 为不同操作人确认的替代读数。
     */
    public record ResolutionResponse(long anomalyId, String resolver, String diagnosisNote,
                                     String replacementReadingUtc, String replacementCumulativeVolume,
                                     String commandKey, String createdUtc) {
    }

    /**
     * 异常证据视图，生成后不可变。
     * 类型：REGRESSION 倒退 / FLOW_RATE 瞬时流量超上限 / INTERVAL_GAP 读数间隔超上限。
     * volumeDelta 为实际增量（立方米，倒退为负）；durationMinutes 为实际时长（分钟）；
     * actualFlowRate 为实际瞬时流量（立方米/分钟）；required* 为要求值（规则快照）；
     * regressionAmount/flowRateExcess/intervalExcessMinutes 为对应类型的差额，其他类型时为 null。
     * resolution 为 null 表示异常未解除（渠道因此冻结）。
     */
    public record AnomalyResponse(long anomalyId, String channelId, String anomalyType, int ruleVersion,
                                  long previousReadingId, long readingId,
                                  String previousReadingUtc, String readingUtc,
                                  String previousCumulative, String readingCumulative,
                                  String volumeDelta, String durationMinutes, String actualFlowRate,
                                  String requiredMaxFlowRate, int requiredMaxIntervalMinutes,
                                  String regressionAmount, String flowRateExcess, String intervalExcessMinutes,
                                  String submitter, String commandKey, String createdUtc,
                                  ResolutionResponse resolution) {
    }

    /** 批量读数提交结果：本批全部读数与本批生成的异常（稳定排序）及提交后冻结派生状态。 */
    public record SubmitReadingsResponse(String channelId, int ruleVersion, boolean frozen,
                                         List<ReadingResponse> readings, List<AnomalyResponse> anomalies) {
    }

    /** 异常解除命令；解除人由 X-Actor-Id 请求头提供，必须不同于异常提交人。 */
    public record ResolveAnomalyRequest(String commandKey, String diagnosisNote,
                                        String replacementReadingUtc, String replacementCumulativeVolume) {
    }

    /** 渠道读数明细列表，按测量时刻升序、同刻按主键升序。 */
    public record ReadingListResponse(String channelId, List<ReadingResponse> readings) {
    }

    /** 渠道异常历史列表（含诊断解除），按触发读数时刻、主键、类型顺序稳定排序。 */
    public record AnomalyListResponse(String channelId, List<AnomalyResponse> anomalies) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
