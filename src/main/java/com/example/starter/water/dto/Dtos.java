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

    /** 提交配水申请命令；申请人由 X-Actor-Id 请求头提供。fieldId 与 concentration 必须同时提供或同时缺省。 */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount, Long fieldId, String concentration) {
    }

    /**
     * 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。
     * amount 为不可改写的原申请水量；heldAmount 为当前持有额度（REQUESTED 为 0，普通批准时等于原水量，
     * 转出时等额扣减，取消时保留已灌部分；持有额度恰为零的已转出申请仍为 APPROVED）。
     * irrigatedAmount 为已实灌水量（不可逆，不得转让或释放）；fieldId/concentration 未绑定田块时为 null。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String irrigatedAmount, Long fieldId,
                                     String concentration, String requester, String status,
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

    /** 创建农户田块命令；操作人由 X-Actor-Id 请求头提供（非空）。saltCapGrams 为正整数克，创建后不可变。 */
    public record CreateFieldRequest(String commandKey, String fieldKey, String farmerId,
                                     String saltCapGrams, Long expectedVersion) {
    }

    /** 田块视图；saltCapGrams 为不可变盐量上限（克）。 */
    public record FieldResponse(long id, String fieldKey, String farmerId, String saltCapGrams,
                                String createdUtc) {
    }

    /** 实际灌入命令；操作人（申请人本人）由 X-Actor-Id 请求头提供。amount 精度沿原水量（最多 3 位小数）。 */
    public record IrrigateRequest(String commandKey, String amount, Long expectedVersion) {
    }

    /**
     * 实灌结果视图。绑定田块时冻结本次浓度、水量、盐量与累计盐（saltGrams/cumulativeSaltGrams）；
     * 未绑定田块的旧流程申请不记盐账，相关盐字段为 null。
     */
    public record IrrigationResponse(String allocationKey, long windowId, Long fieldId, String amount,
                                     String irrigatedAmount, String concentration, String saltGrams,
                                     String cumulativeSaltGrams, String actor, String createdUtc) {
    }

    /** 盐负荷账本条目视图，创建后不可变。 */
    public record SaltLedgerEntryResponse(long id, String allocationKey, long windowId, String concentration,
                                          String waterAmount, String saltGrams, String cumulativeSaltGrams,
                                          String actor, String createdUtc) {
    }

    /**
     * 田块盐负荷视图。irrigatedSalt 为累计实灌盐（克，不可逆）；reservedSalt 为全部 APPROVED 绑定申请
     * 未灌持有水量乘浓度的精确预留盐（克）；remainingSalt 为上限扣除二者后的余量。
     */
    public record FieldSaltResponse(long fieldId, String fieldKey, String farmerId, String saltCapGrams,
                                    String irrigatedSalt, String reservedSalt, String remainingSalt,
                                    List<SaltLedgerEntryResponse> entries) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
