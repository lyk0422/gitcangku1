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

    /** 提交配水申请命令；申请人由 X-Actor-Id 请求头提供。fieldKey/irrigationUtc 可空，
     *  关联田块时二者必须同时提供，irrigationUtc 须为 UTC 整小时且不早于田块启用时刻。 */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount, String fieldKey, String irrigationUtc) {
    }

    /**
     * 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。
     * amount 为不可改写的原申请水量；heldAmount 为当前持有额度（REQUESTED 为 0，普通批准时等于原水量，
     * 转出时等额扣减，取消时归零；持有额度恰为零的已转出申请仍为 APPROVED）。
     * fieldKey/irrigationUtc 为关联田块与灌溉时刻（可空）；infiltrationBalance 为批准时冻结的
     * 扣后入渗余额（单位立方米），未批准或无田块为 null，冻结后不改写。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String requester, String status,
                                     String createdUtc, String updatedUtc, String fieldKey,
                                     String irrigationUtc, String infiltrationBalance) {
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

    /** 创建田块命令；requestId 为幂等键，expectedVersion 仅允许 0（田块尚未创建），可空。 */
    public record CreateFieldRequest(String requestId, String fieldKey, Long capacity, Long recoveryPerHour,
                                     String enabledAtUtc, Long expectedVersion) {
    }

    /** 田块视图；capacity 单位立方米，recoveryPerHour 单位立方米/小时，farmer 为首次关联合成的农户。 */
    public record FieldResponse(String fieldKey, String farmer, long capacity, long recoveryPerHour,
                                String enabledAtUtc, long version, String createdUtc) {
    }

    /** 入渗核算事件视图（现况重算结果，不改写批准时冻结的历史余额）。 */
    public record InfiltrationEventView(String allocationKey, String irrigationUtc, String deductAmount,
                                        String balanceAfter) {
    }

    /** 田块入渗现况：按当前已批准申请的最新时序重算，单位均为立方米。 */
    public record InfiltrationResponse(String fieldKey, long capacity, long recoveryPerHour,
                                       String enabledAtUtc, List<InfiltrationEventView> events,
                                       String finalBalance) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
