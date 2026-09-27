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

    /** 配置渠道异常判定规则命令；操作人由 X-Actor-Id 请求头提供，每次成功配置生成新的不可变版本。 */
    public record ChannelRuleRequest(String commandKey, String maxFlowRate, Long maxIntervalMinutes) {
    }

    /**
     * 规则版本视图。maxFlowRate 单位立方米/秒；maxIntervalMinutes 单位分钟。
     * 创建后不可变，读数判定使用判定时刻的最新版本。
     */
    public record RuleVersionResponse(long id, int version, String maxFlowRate, long maxIntervalMinutes,
                                      String actor, String createdUtc) {
    }

    /** 渠道规则版本历史；currentVersion 为 null 表示尚未配置。 */
    public record ChannelRuleListResponse(String channelId, Integer currentVersion,
                                          List<RuleVersionResponse> versions) {
    }

    /** 单条累计读数项；observedUtc 为 ISO-8601 UTC 时刻，cumulativeValue 单位立方米。 */
    public record ReadingItem(String observedUtc, String cumulativeValue) {
    }

    /** 批量提交读数命令；先校验完整序列，任一错误不写入。 */
    public record SubmitReadingsRequest(String commandKey, List<ReadingItem> readings) {
    }

    /** 读数视图，创建后不可变。 */
    public record ReadingResponse(long id, String observedUtc, String cumulativeValue, String actor,
                                  String createdUtc) {
    }

    /**
     * 计量异常视图。类型：REGRESSION 相邻读数倒退 / FLOW_EXCEEDED 瞬时流量超限 / INTERVAL_EXCEEDED 间隔超限。
     * actualValue/requiredValue/deltaValue 按类型分别表示读数（立方米）、流量（立方米/秒）或间隔（分钟）；
     * released 表示是否已解除（解除记录不改写本异常）。
     */
    public record AnomalyResponse(long id, String channelId, int ruleVersion, long readingId, String type,
                                  String actualValue, String requiredValue, String deltaValue,
                                  String prevObservedUtc, String prevValue, String readingObservedUtc,
                                  String readingValue, String readingActor, boolean released,
                                  String createdUtc) {
    }

    /** 批量读数提交结果；frozen 为提交后渠道是否处于冻结状态。 */
    public record SubmitReadingsResponse(String channelId, int submitted, boolean frozen,
                                         List<ReadingResponse> readings, List<AnomalyResponse> anomalies) {
    }

    /** 渠道读数历史，按读数时刻与主键稳定排序。 */
    public record ReadingListResponse(String channelId, List<ReadingResponse> readings) {
    }

    /** 异常解除命令；操作人（X-Actor-Id）必须与触发读数提交人不同。 */
    public record ReleaseRequest(String commandKey, String diagnosis, String substituteValue,
                                 String substituteObservedUtc) {
    }

    /** 解除记录视图；channelFrozen 为解除后渠道是否仍有其他未解除冻结。 */
    public record ReleaseResponse(long id, long anomalyId, String channelId, String diagnosis,
                                  String substituteValue, String substituteObservedUtc, String actor,
                                  boolean channelFrozen, String createdUtc) {
    }

    /** 异常诊断明细：异常本身 + 解除记录（未解除为 null）。 */
    public record AnomalyDetailResponse(AnomalyResponse anomaly, ReleaseResponse release) {
    }

    /** 渠道异常与诊断查询结果，按异常主键稳定排序。 */
    public record AnomalyListResponse(String channelId, List<AnomalyDetailResponse> anomalies) {
    }

    /** 渠道状态视图；openAnomalyCount 为未解除异常数，currentRuleVersion 为 null 表示未配置规则。 */
    public record ChannelStatusResponse(String channelId, boolean frozen, int openAnomalyCount,
                                        Integer currentRuleVersion) {
    }

    /** 实际核销命令；操作人由 X-Actor-Id 请求头提供。 */
    public record SettlementRequest(String commandKey, String settlementKey, String allocationKey,
                                    String amount) {
    }

    /**
     * 核销流水视图，创建后不可变；remainingHeld 为核销后申请剩余持有额度（实际值），
     * 仅在核销创建响应（含幂等重放快照）中返回，历史查询中为 null。
     */
    public record SettlementResponse(String settlementKey, String allocationKey, long windowId, String channelId,
                                     String amount, String remainingHeld, String actor, String createdUtc) {
    }

    /** 申请核销历史，按主键稳定排序。 */
    public record SettlementListResponse(String allocationKey, List<SettlementResponse> settlements) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
