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

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }

    /**
     * 田块绑定配水申请命令（新增写入，幂等键为 requestId）。
     * capacity/recoveryPerHour 为田块不可变配置（单位立方米，正整数），首次关联时生效，
     * 后续关联必须与原配置一致否则 409；enabledUtc 为 UTC 启用时刻，irrigationUtc 必须是不早于
     * 启用时刻的 UTC 整小时；expectedFieldVersion 为期望田块版本，null 表示不校验。
     */
    public record FieldAllocationRequest(String requestId, String allocationKey, Long windowId, String userId,
                                         String amount, String fieldKey, String irrigationUtc, Long capacity,
                                         Long recoveryPerHour, String enabledUtc, Long expectedFieldVersion) {
    }

    /** 田块绑定申请视图；fieldVersion 为提交时田块已批准序列版本。 */
    public record FieldAllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                          String fieldKey, String irrigationUtc, long fieldVersion,
                                          String status, String createdUtc, String updatedUtc) {
    }

    /** 入渗序列事件：按灌溉时刻、申请键字典序排列，balanceAfter 为该申请扣减后的入渗余额。 */
    public record FieldBalanceEvent(String allocationKey, String irrigationUtc, String amount,
                                    String balanceAfter) {
    }

    /**
     * 田块入渗现况视图。current 为按最新已批准序列实时计算的轨迹（只读，不改写历史）；
     * frozen 为最近一次批准时冻结的序列与余额快照，从未批准过为 null。
     */
    public record FieldBalanceResponse(String fieldKey, String ownerUserId, String capacity,
                                       String recoveryPerHour, String enabledUtc, long version,
                                       List<FieldBalanceEvent> current, List<FieldBalanceEvent> frozen) {
    }
}
