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

    /** 渠道变更命令；capacity 为 null 表示不限核销容量，expectedVersion 为渠道版本乐观并发校验。 */
    public record ChannelChangeRequest(String commandKey, String capacity, Integer expectedVersion) {
    }

    /** 渠道视图；capacity 为 null 表示不限核销容量。 */
    public record ChannelResponse(String channelId, String capacity, int version, String createdUtc) {
    }

    /**
     * 下达停运窗口命令。outageKey 指纹由渠道版本、规范化受影响申请集合、UTC 时段与操作构成；
     * 同键重放返回首次结果，失败不占键。
     */
    public record CreateOutageRequest(String commandKey, String outageKey, String startUtc, String endUtc,
                                      List<String> affectedAllocationKeys, Integer expectedVersion) {
    }

    /**
     * 停运窗口视图。状态：SCHEDULED 未开始 / ACTIVE 进行中 / RECOVERED 已提前恢复 /
     * ENDED 已结束 / DELETED 已删除；recoveredUtc 未恢复时为 null。
     */
    public record OutageResponse(long id, String outageKey, String channelId, String startUtc, String endUtc,
                                 String status, String recoveredUtc, List<String> affectedAllocationKeys,
                                 String createdUtc) {
    }

    /** 渠道停运窗口列表视图。 */
    public record OutageListResponse(String channelId, List<OutageResponse> outages) {
    }

    /** 停运删除命令（仅未开始的停运窗口可删除）。 */
    public record OutageCommandRequest(String commandKey, Integer expectedVersion) {
    }

    /** 停运提前恢复命令；recoveredAtUtc 不得早于当前时刻。 */
    public record RecoverOutageRequest(String commandKey, Integer expectedVersion, String recoveredAtUtc) {
    }

    /** 停运影响视图：受影响申请及其供水窗口与生效停运区间的相交情况。 */
    public record OutageImpactResponse(String outageKey, String channelId, String startUtc, String endUtc,
                                       String status, List<ImpactedAllocation> allocations) {
    }

    /** 停运影响下的单个申请视图；intersects 表示其供水窗口与生效停运区间是否相交。 */
    public record ImpactedAllocation(String allocationKey, long windowId, String windowStartUtc,
                                     String windowEndUtc, String status, String heldAmount,
                                     boolean intersects) {
    }

    /** 申请供应风险视图；风险记录不可变，风险申请不能作为转出方再次转让。 */
    public record RiskListResponse(String allocationKey, List<RiskItem> risks) {
    }

    /** 单条供应风险。 */
    public record RiskItem(String outageKey, String channelId, String createdUtc) {
    }

    /** 单笔核销命令；从申请持有额度等额扣减并写不可变流水。 */
    public record SettleRequest(String commandKey, String settlementKey, String amount) {
    }

    /** 核销流水视图，创建后不可变；batchKey 为 null 表示单笔核销。 */
    public record SettlementResponse(String settlementKey, String allocationKey, String amount, String actor,
                                     String batchKey, String createdUtc) {
    }

    /** 批量核销命令；任一申请预校验失败则全部水量、余额和流水回滚。 */
    public record BatchSettleRequest(String commandKey, List<BatchSettleItem> items) {
    }

    /** 批量核销单条明细。 */
    public record BatchSettleItem(String allocationKey, String amount) {
    }

    /** 批量核销结果视图。 */
    public record BatchSettleResponse(String batchKey, List<SettlementResponse> settlements) {
    }

    /** 申请核销流水列表视图。 */
    public record SettlementListResponse(String allocationKey, List<SettlementResponse> settlements) {
    }

    /** 核销拒绝原因预览（只读）：allowed 为 false 时 code/message 给出可区分的拒绝原因。 */
    public record SettlementRejectionResponse(String allocationKey, String amount, boolean allowed,
                                              String code, String message) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
