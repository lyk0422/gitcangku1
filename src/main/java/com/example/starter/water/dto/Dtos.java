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

    /** 供水窗口视图；activeCurtailmentVolume 为 null 表示无生效总量限供。 */
    public record WindowResponse(long id, String windowKey, String channelId, String startUtc, String endUtc,
                                 String plannedVolume, String activeCurtailmentVolume, String availableTotal,
                                 String createdUtc) {
    }

    /**
     * 提交配水申请命令；申请人由 X-Actor-Id 请求头提供。
     * priority 缺省为 NORMAL：ESSENTIAL 必保 / NORMAL 一般 / DEFERRABLE 可延后。
     */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount, String priority) {
    }

    /**
     * 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。
     * amount 为不可改写的原申请水量；heldAmount 为当前持有额度（REQUESTED 为 0，普通批准时等于原水量，
     * 旱情削减时按优先级比例下调，转出时等额扣减，取消时归零；持有额度恰为零的已转出申请仍为 APPROVED）。
     * priority 为旱情削减优先级。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String priority,
                                     String amount, String heldAmount, String requester, String status,
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

    /** 窗口当前可用容量视图；approvedTotal 汇总 APPROVED 申请的当前持有额度（旱情削减后随之下降）。 */
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

    /**
     * 旱情分级比例削减声明命令。level 为 NONE/LEVEL1/LEVEL2/LEVEL3；
     * 三级削减百分比为 0~100 整数且须 ESSENTIAL<=NORMAL<=DEFERRABLE（NONE 时必须全为 0）；
     * expectedVersion 为提交时窗口版本，不匹配返回 409。
     */
    public record DroughtDeclarationRequest(String commandKey, String curtailmentKey, String level,
                                            Integer essentialPct, Integer normalPct, Integer deferrablePct,
                                            Long expectedVersion) {
    }

    /** 旱情削减逐笔明细视图，随声明同事务生成，不可变。 */
    public record DroughtDetailResponse(String allocationKey, String priority, String originalHeld,
                                        String targetHeld, String reducedAmount) {
    }

    /** 旱情削减声明视图。状态：ACTIVE 当前生效（含 NONE）/ SUPERSEDED 已被覆盖。 */
    public record DroughtDeclarationResponse(long id, String curtailmentKey, long windowId, String level,
                                             int essentialPct, int normalPct, int deferrablePct,
                                             long expectedVersion, long version, String status,
                                             String createdUtc, String supersededUtc,
                                             List<DroughtDetailResponse> details) {
    }

    /** 窗口当前旱情等级视图；无任何声明时 level=NONE、active=null。 */
    public record DroughtStatusResponse(long windowId, String level, DroughtDeclarationResponse active,
                                        String availableTotal, String approvedTotal, String remaining) {
    }

    /** 窗口旱情声明历史：按生效顺序返回全部声明及其逐笔明细。 */
    public record DroughtHistoryResponse(long windowId, List<DroughtDeclarationResponse> declarations) {
    }

    /** 窗口历史明细：窗口本身 + 全部申请 + 全部总量限供记录。 */
    public record HistoryResponse(WindowResponse window, List<AllocationResponse> allocations,
                                  List<CurtailmentResponse> curtailments) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
