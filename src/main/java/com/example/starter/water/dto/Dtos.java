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

    /** 下达停运窗口命令；expectedVersion 为渠道版本乐观锁，allocationKeys 为受影响申请集合。 */
    public record CreateOutageRequest(String commandKey, String outageKey, Long expectedVersion,
                                      String startUtc, String endUtc, List<String> allocationKeys) {
    }

    /** 停运窗口变更命令（删除）；expectedVersion 为渠道版本乐观锁。 */
    public record OutageCommandRequest(String commandKey, Long expectedVersion) {
    }

    /** 记录提前恢复命令；recoveredUtc 不得早于当前时刻。 */
    public record RecoverOutageRequest(String commandKey, Long expectedVersion, String recoveredUtc) {
    }

    /**
     * 停运窗口视图。状态：SCHEDULED 生效中 / DELETED 已删除；recoveredUtc 未恢复时为 null；
     * channelVersion 为变更后的渠道版本。
     */
    public record OutageResponse(long id, String outageKey, String channelId, String startUtc, String endUtc,
                                 String status, String recoveredUtc, long channelVersion,
                                 List<String> allocationKeys, String createdUtc) {
    }

    /** 供应风险视图，创建后不可变。 */
    public record RiskResponse(String allocationKey, String outageKey, String createdUtc) {
    }

    /** 停运影响视图：停运窗口 + 受影响申请当前状态 + 已写入的供应风险。 */
    public record OutageImpactResponse(OutageResponse outage, List<AllocationResponse> affectedAllocations,
                                       List<RiskResponse> risks) {
    }

    /** 申请供应风险列表视图。 */
    public record RiskListResponse(String allocationKey, List<RiskResponse> risks) {
    }

    /** 单笔核销命令。 */
    public record SettleRequest(String commandKey, String settlementKey, String amount) {
    }

    /** 核销流水视图，创建后不可变；batchKey 为 null 表示单笔核销。 */
    public record SettlementResponse(String settlementKey, String batchKey, String allocationKey,
                                     long windowId, String amount, String createdUtc) {
    }

    /** 批量核销项。 */
    public record BatchSettleItem(String allocationKey, String amount) {
    }

    /** 批量核销命令：先按最终渠道容量、申请余额和停运后态预校验，任一失败全部回滚。 */
    public record BatchSettleRequest(String commandKey, String batchKey, List<BatchSettleItem> items) {
    }

    /** 批量核销视图。 */
    public record BatchSettleResponse(String batchKey, List<SettlementResponse> settlements) {
    }

    /** 核销可行性检查视图：settleable 为 false 时 rejectCode/rejectReason 给出可区分拒绝原因。 */
    public record SettlementCheckResponse(String allocationKey, boolean settleable, String rejectCode,
                                          String rejectReason) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
