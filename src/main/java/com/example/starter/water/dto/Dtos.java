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

    /** 设置/调整应急储备命令；reserveKey 为幂等指纹键，expectedVersion 须匹配当前窗口储备版本。 */
    public record SetReserveRequest(String reserveKey, String reserveVolume, Integer expectedVersion) {
    }

    /** 储备调整历史快照视图。 */
    public record ReserveHistoryItem(String reserveKey, String actor, String oldVolume, String newVolume,
                                     int version, String createdUtc) {
    }

    /** 常规核销命令。 */
    public record RegularWriteOffRequest(String commandKey, String writeOffKey, String amount) {
    }

    /** 常规核销视图。 */
    public record RegularWriteOffResponse(String writeOffKey, long windowId, String amount, String createdUtc) {
    }

    /** 单笔应急核销命令；emergencyId 同一窗口只能核销一次，approver 为审批人。 */
    public record EmergencyWriteOffRequest(String commandKey, String writeOffKey, String emergencyId,
                                           String approver, String amount) {
    }

    /** 批量应急核销命令；先整体校验再单事务扣减，任一失败整单回滚。 */
    public record EmergencyBatchRequest(String commandKey, String batchKey,
                                        List<EmergencyWriteOffItem> items) {
    }

    /** 批量应急核销明细项。 */
    public record EmergencyWriteOffItem(String emergencyId, String approver, String amount) {
    }

    /** 应急核销视图；batchKey 为 null 表示单笔核销。 */
    public record EmergencyWriteOffResponse(String writeOffKey, long windowId, String emergencyId,
                                            String approver, String amount, String batchKey, String createdUtc) {
    }

    /** 批量应急核销视图。 */
    public record EmergencyBatchResponse(String batchKey, long windowId, String totalAmount,
                                         List<EmergencyWriteOffResponse> items) {
    }

    /**
     * 窗口储备视图：储备量、储备余额、常规可用量、应急核销流水、储备调整历史与阻断原因。
     * windowClosed 为 true 时禁止新建应急核销；blockedReasons 列出当前阻断原因，无阻断为空列表。
     */
    public record ReserveStatusResponse(long windowId, String availableTotal, String reserveVolume,
                                        String reserveUsed, String reserveBalance, String regularUsed,
                                        String regularAvailable, int version, boolean windowClosed,
                                        List<String> blockedReasons,
                                        List<EmergencyWriteOffResponse> emergencyWriteOffs,
                                        List<ReserveHistoryItem> reserveHistory) {
    }

    /** 窗口关闭视图。 */
    public record WindowCloseResponse(long windowId, boolean closed, String closedUtc) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
