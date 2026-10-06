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

    /** 提交配水申请命令；申请人由 X-Actor-Id 请求头提供。plotId 与 concentration 同时提供（绑定田块）或同时省略（旧流程）。 */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount, Long plotId, String concentration) {
    }

    /**
     * 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。
     * amount 为不可改写的原申请水量；heldAmount 为当前持有额度（REQUESTED 为 0，普通批准时等于原水量，
     * 转出时等额扣减，取消时保留已实灌部分；持有额度恰为零的已转出申请仍为 APPROVED）。
     * irrigatedAmount 为不可逆已实灌水量；plotId/concentration 未绑定田块时为 null；
     * version 为乐观锁版本，实灌需携带期望版本。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String requester, String status,
                                     String createdUtc, String updatedUtc,
                                     Long plotId, String concentration, String irrigatedAmount, long version) {
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

    /** 创建农户田块命令；操作人（须为农户本人）由 X-Actor-Id 请求头提供。saltLimitGrams 为不可变正整数（克）。 */
    public record CreatePlotRequest(String requestId, String plotKey, String farmerId, Long saltLimitGrams) {
    }

    /** 田块视图。 */
    public record PlotResponse(long id, String plotKey, String farmerId, long saltLimitGrams, String createdUtc) {
    }

    /** 实灌命令；操作人（申请人本人）由 X-Actor-Id 请求头提供，expectedVersion 为期望版本（乐观锁）。 */
    public record IrrigateRequest(String requestId, String volume, Long expectedVersion) {
    }

    /**
     * 实灌结果视图。saltGrams 为本次实灌盐量（浓度×水量精确乘积），cumulativeSaltGrams 为本笔后田块累计；
     * 未绑定田块的申请不核算盐量，两个字段为 null。
     */
    public record IrrigationResponse(String allocationKey, long windowId, String volume, String irrigatedAmount,
                                     String concentration, String saltGrams, String cumulativeSaltGrams,
                                     long version, String createdUtc) {
    }

    /** 盐负荷账本条目视图，创建后不可变。 */
    public record SaltLedgerEntry(String allocationKey, String concentration, String volume, String saltGrams,
                                  String cumulativeSaltGrams, String actor, String createdUtc) {
    }

    /** 田块盐负荷视图：累计实灌 + 全部预留不得超过 saltLimitGrams。 */
    public record PlotSaltResponse(long plotId, String plotKey, String farmerId, long saltLimitGrams,
                                   String cumulativeSaltGrams, String reservedSaltGrams,
                                   String remainingSaltGrams, List<SaltLedgerEntry> ledger) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
