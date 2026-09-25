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
                                 String createdUtc, String reserveVolume, long version, String status,
                                 String closedUtc) {
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
                                     String heldAmount, String regularWrittenOff, String requester,
                                     String status, String createdUtc, String updatedUtc) {
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

    /**
     * 窗口当前可用容量视图；approvedTotal 汇总 APPROVED 申请的当前持有额度。
     * reserveVolume 为应急储备量；regularAvailable 为扣除储备后的常规可用总量；
     * reserveRemaining 为储备余额（储备量减应急核销累计）。
     */
    public record CapacityResponse(long windowId, String plannedVolume, String activeCurtailmentVolume,
                                   String availableTotal, String approvedTotal, String remaining,
                                   String reserveVolume, String regularAvailable, String reserveRemaining) {
    }

    /**
     * 同窗口额度转让命令；操作人（源申请人）由 X-Actor-Id 请求头提供。
     * 必须携带窗口 expectedVersion 与 reserveKey 指纹；转让不得使窗口常规可用量低于储备量。
     */
    public record TransferRequest(String commandKey, String transferKey, String sourceAllocationKey,
                                  String targetAllocationKey, String reserveKey, Long expectedVersion) {
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

    /**
     * 调整窗口应急储备量命令；必须携带窗口 expectedVersion。
     * 储备量 0..窗口总配额（限供生效时取限供水量），最多 3 位小数；下调时回查已批准未核销常规转让后态，
     * 若其结算后侵占新储备量则 422，不部分生效。
     */
    public record AdjustReserveRequest(String reserveKey, Long windowId, Long expectedVersion,
                                       String reserveVolume) {
    }

    /** 储备量视图（调整成功快照与查询共用）。 */
    public record ReserveResponse(long windowId, String reserveVolume, String regularAvailable,
                                  String reserveRemaining, long version, String status) {
    }

    /** 常规核销命令；从单个已批准申请的持有额度中核销，不得使窗口常规余额低于储备量。 */
    public record RegularWriteoffRequest(String reserveKey, Long windowId, Long expectedVersion,
                                         String allocationKey, String amount) {
    }

    /** 常规核销流水视图，创建后不可变。 */
    public record RegularWriteoffResponse(String writeoffKey, long windowId, String allocationKey,
                                          String amount, String actor, String regularBalance,
                                          String reserveVolume, String createdUtc) {
    }

    /** 批量应急核销中的单条申请：必须声明应急编号、审批人与核销量。 */
    public record EmergencyWriteoffItem(String emergencyId, String approver, String amount) {
    }

    /** 批量应急核销命令；单事务先全量校验再扣减，任一失败整单回滚。 */
    public record EmergencyWriteoffBatchRequest(String reserveKey, Long windowId, Long expectedVersion,
                                                List<EmergencyWriteoffItem> items) {
    }

    /** 应急核销流水视图，创建后不可变；reserveSnapshot 为核销时储备量快照。 */
    public record EmergencyWriteoffResponse(String writeoffKey, long windowId, String emergencyId,
                                            String approver, String actor, String amount,
                                            String reserveSnapshot, String createdUtc) {
    }

    /** 窗口关闭命令；关闭后不得新建应急核销，历史储备快照保留。 */
    public record WindowCloseRequest(String reserveKey, Long expectedVersion) {
    }

    /** 常规操作被储备隔离阻断时的原因条目。 */
    public record BlockReason(String code, String message, String regularBalance, String reserveVolume) {
    }

    /**
     * 储备余额查询视图：总配额/常规可用/储备余额/应急核销累计，以及当前阻断原因（无阻断为空列表）。
     */
    public record ReserveStatusResponse(long windowId, String windowStatus, long version,
                                        String totalQuota, String reserveVolume, String reserveRemaining,
                                        String regularAvailable, String regularBalance,
                                        String approvedHeld, String regularWrittenOff,
                                        String emergencyWrittenOff,
                                        List<EmergencyWriteoffResponse> emergencyWriteoffs,
                                        List<BlockReason> blockReasons) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
