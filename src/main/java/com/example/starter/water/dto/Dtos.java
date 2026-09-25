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

    /** 提交配水申请命令；申请人由 X-Actor-Id 请求头提供；salinityLimit 可选，单位 mg/L。 */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount, String salinityLimit) {
    }

    /**
     * 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。
     * amount 为不可改写的原申请水量；heldAmount 为当前持有额度（REQUESTED 为 0，普通批准时等于原水量，
     * 转出或掺配核销时等额扣减，取消时归零；持有额度恰为零的已转出/已核销申请仍为 APPROVED）。
     * salinityLimit 为申请声明的盐度上限（mg/L），null 表示不限制；version 为乐观版本，每次扣减自增。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String salinityLimit, long version,
                                     String requester, String status,
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

    // ------------------------------------------------------------------
    // 水源水质掺配
    // ------------------------------------------------------------------

    /** 注册水源命令；availableAmount 单位立方米（最多 3 位小数），salinity 单位 mg/L（非负）。 */
    public record CreateSourceRequest(String commandKey, String sourceKey, String availableAmount,
                                      String salinity) {
    }

    /**
     * 修改水源盐度命令；必须携带 expectedVersion 做乐观裁决，版本不符返回 409。
     * 修改只影响后续核销，不改写历史掺配快照。
     */
    public record UpdateSourceSalinityRequest(String commandKey, Long expectedVersion, String salinity) {
    }

    /** 水源视图；availableAmount 为当前可用余量，version 为当前乐观版本（创建为 0）。 */
    public record SourceResponse(long id, String sourceKey, String availableAmount, String salinity,
                                 long version, String createdUtc, String updatedUtc) {
    }

    /** 掺配核销中的一项水源取水；amount 单位立方米，最多 3 位小数，必须为正。 */
    public record BlendItemRequest(String sourceKey, String amount) {
    }

    /**
     * 水质掺配核销命令：对申请做一次核销，选取 1 至 5 个水源及各自取水量。
     * 水源集合换序视为同参；writeOffAmount 为本次核销量（从申请剩余额度扣减）；
     * allocationVersion 为客户端看到的申请乐观版本，核销时必须与当前版本一致，否则按并发冲突 409。
     */
    public record BlendWriteOffRequest(String commandKey, String blendKey, String allocationKey,
                                       Long allocationVersion, String writeOffAmount,
                                       List<BlendItemRequest> items) {
    }

    /** 掺配快照明细视图，冻结核销时水源盐度。 */
    public record BlendItemResponse(String sourceKey, String amount, String salinity, int ordinal) {
    }

    /** 掺配核销快照视图，创建后不可变；weightedSalinity 为加权平均盐度计算值（mg/L）。 */
    public record BlendSnapshotResponse(String blendKey, String allocationKey, String actor,
                                        String totalAmount, String weightedSalinity, String salinityLimit,
                                        long allocationVersion, String createdUtc,
                                        List<BlendItemResponse> items) {
    }

    /** 申请掺配快照列表视图。 */
    public record BlendSnapshotListResponse(String allocationKey, List<BlendSnapshotResponse> snapshots) {
    }

    /**
     * 申请累计水质视图：totalWrittenOff 为累计核销量，remainingHeld 为申请剩余额度，
     * cumulativeWeightedSalinity 为全部核销按核销量加权的累计平均盐度（mg/L），无核销时为 null。
     */
    public record AllocationBlendSummaryResponse(String allocationKey, String status, String amount,
                                                 String heldAmount, String totalWrittenOff,
                                                 String remainingHeld, String salinityLimit,
                                                 String cumulativeWeightedSalinity,
                                                 List<BlendSnapshotResponse> snapshots) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
