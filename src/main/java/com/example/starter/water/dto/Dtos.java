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

    /** 提交配水申请命令；申请人由 X-Actor-Id 请求头提供；maxSalinityMgPerL 可空，表示不声明盐度上限。 */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount, String maxSalinityMgPerL) {
    }

    /**
     * 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。
     * amount 为不可改写的原申请水量；heldAmount 为当前持有额度即申请剩余额度（REQUESTED 为 0，普通批准时
     * 等于原水量，转出与掺配核销时等额扣减，取消时归零；持有额度恰为零的已转出申请仍为 APPROVED）。
     * maxSalinityMgPerL 为申报的掺配盐度上限（毫克每升），null 表示未声明；version 为申请版本号，
     * 每次批准/取消/转让/核销变更加 1，掺配核销请求须携带该版本。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String maxSalinityMgPerL, long version,
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

    /** 创建水源命令；availableAmount 单位立方米，salinityMgPerL 单位毫克每升，均最多 3 位小数。 */
    public record CreateSourceRequest(String commandKey, String sourceId, String availableAmount,
                                      String salinityMgPerL) {
    }

    /** 水源视图；version 为乐观版本号，盐度修改须携带 expectedVersion。 */
    public record SourceResponse(String sourceId, String availableAmount, String salinityMgPerL, long version,
                                 String createdUtc, String updatedUtc) {
    }

    /** 修改水源盐度命令；expectedVersion 须等于当前版本，否则 409；修改只影响后续核销。 */
    public record UpdateSalinityRequest(String commandKey, Long expectedVersion, String salinityMgPerL) {
    }

    /** 掺配核销的单个水源取水项；amount 单位立方米，最多 3 位小数。 */
    public record BlendSourceItem(String sourceId, String amount) {
    }

    /**
     * 掺配核销命令；操作人由 X-Actor-Id 请求头提供。
     * sources 为 1 至 5 个不重复水源，换序视为同参；各取水量之和必须等于 settleAmount（申请核销量）；
     * allocationVersion 须等于申请当前版本。blendKey 指纹含操作者、申请版本、规范化水源集合与数量，
     * 同键同参重放首次完整结果，失败不占键。
     */
    public record BlendRequest(String blendKey, String allocationKey, Long allocationVersion,
                               String settleAmount, List<BlendSourceItem> sources) {
    }

    /** 掺配快照水源明细视图，冻结核销时刻的取水量、盐度与水源版本。 */
    public record BlendLineResponse(String sourceId, long sourceVersion, String amount,
                                    String salinityMgPerL) {
    }

    /** 掺配快照视图，创建后不可变；weightedSalinityMgPerL 为加权平均盐度（6 位小数）。 */
    public record BlendResponse(String blendKey, String allocationKey, long allocationVersion,
                                String operator, String settleAmount, String weightedSalinityMgPerL,
                                List<BlendLineResponse> sources, String createdUtc) {
    }

    /**
     * 申请累计盐度视图：settledTotal 为累计核销量（立方米），cumulativeSalinityMgPerL 为全部核销
     * 按核销量加权的累计平均盐度（毫克每升，6 位小数），无核销记录时为 null。
     */
    public record AllocationSalinityResponse(String allocationKey, String settledTotal,
                                             String cumulativeSalinityMgPerL, int blendCount) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
