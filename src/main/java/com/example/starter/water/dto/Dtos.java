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

    /** 供水窗口视图；activeCurtailmentVolume 为 null 表示无生效限供。status：OPEN / CLOSED。 */
    public record WindowResponse(long id, String windowKey, String channelId, String startUtc, String endUtc,
                                 String plannedVolume, String activeCurtailmentVolume, String availableTotal,
                                 String status, String createdUtc) {
    }

    /** 提交配水申请命令；申请人由 X-Actor-Id 请求头提供。窗口已配置水源时 sourceId 必填。 */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount, String sourceId) {
    }

    /**
     * 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。
     * amount 为不可改写的原申请水量；heldAmount 为当前持有额度（REQUESTED 为 0，普通批准时等于原水量，
     * 转出时等额扣减，取消时归零；持有额度恰为零的已转出申请仍为 APPROVED）。
     * sourceId 为初始绑定水源（窗口未配置水源时为 null）；version 为乐观锁版本，额度或核销量变更时递增。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String requester, String status,
                                     String sourceId, long version,
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

    /** 单个水源配置项：水源 ID 与供给上限（十进制字符串，单位立方米，最多 3 位小数）。 */
    public record SourceCapRequest(String sourceId, String supplyCap) {
    }

    /** 配置/整体替换窗口水源命令；sources 1~10 条，sourceId 不得重复。 */
    public record ConfigureSourcesRequest(String commandKey, List<SourceCapRequest> sources) {
    }

    /** 水源配置视图。 */
    public record SourceCapResponse(String sourceId, String supplyCap) {
    }

    /** 窗口水源配置列表视图，按 sourceId 升序。 */
    public record SourceListResponse(long windowId, List<SourceCapResponse> sources) {
    }

    /** 用水核销命令：在指定水源分片上登记已核销用水量。 */
    public record ConsumeRequest(String commandKey, String sourceId, String amount) {
    }

    /** 核销结果视图：consumed 为该分片累计核销量，available 为该分片剩余可核销/可搬出量。 */
    public record ConsumeResponse(String allocationKey, String sourceId, String consumed, String available,
                                  long version) {
    }

    /** 重平衡明细：把某区块额度从源水源搬出 adjust 立方米到目标水源。 */
    public record RebalanceItemRequest(String allocationKey, String fromSourceId, String toSourceId,
                                       String amount) {
    }

    /** 涉及额度的期望版本。 */
    public record ExpectedVersionRequest(String allocationKey, Long version) {
    }

    /** 重平衡预览命令（只读，不落库）。 */
    public record RebalancePreviewRequest(List<RebalanceItemRequest> items,
                                          List<ExpectedVersionRequest> expectedVersions) {
    }

    /** 重平衡激活命令；requestId 为幂等键，rebalanceKey 全局唯一。 */
    public record RebalanceActivateRequest(String requestId, String rebalanceKey,
                                           List<RebalanceItemRequest> items,
                                           List<ExpectedVersionRequest> expectedVersions) {
    }

    /** 规范化后的重平衡明细（同区块同源目标的重复明细已求和），按 fromSourceId、区块、toSourceId 排序。 */
    public record RebalanceItemResponse(String allocationKey, String fromSourceId, String toSourceId,
                                        String amount) {
    }

    /** 矩阵单元：某区块在某水源上的额度，按 sourceId、区块排序。 */
    public record MatrixCellResponse(String allocationKey, String sourceId, String amount) {
    }

    /** 水源上限快照：供给上限与重平衡前/后该水源总分配。 */
    public record SourceCapSnapshotResponse(String sourceId, String supplyCap, String beforeTotal,
                                            String afterTotal) {
    }

    /** 核销量快照单元：某区块在某水源上的已核销用水量。 */
    public record ConsumedCellResponse(String allocationKey, String sourceId, String consumed) {
    }

    /** 额度版本视图：激活后为递增后的新版本，预览时为当前版本。 */
    public record AllocationVersionResponse(String allocationKey, long version) {
    }

    /**
     * 重平衡单视图（预览时 rebalanceKey/createdUtc 为 null、status 为 PREVIEW）。
     * 冻结内容：规范化明细、前后完整矩阵、供给上限与核销量快照、涉及额度版本。
     */
    public record RebalanceResponse(String rebalanceKey, long windowId, String status, String createdUtc,
                                    List<RebalanceItemResponse> normalizedItems,
                                    List<MatrixCellResponse> beforeMatrix,
                                    List<MatrixCellResponse> afterMatrix,
                                    List<SourceCapSnapshotResponse> caps,
                                    List<ConsumedCellResponse> consumed,
                                    List<AllocationVersionResponse> versions) {
    }

    /** 窗口重平衡单列表视图，按激活顺序。 */
    public record RebalanceListResponse(long windowId, List<RebalanceResponse> rebalances) {
    }

    /** 额度水源分片视图。 */
    public record SliceResponse(String sourceId, String amount, String consumed) {
    }

    /** 申请的水源分片列表视图，按 sourceId 升序。 */
    public record SliceListResponse(String allocationKey, List<SliceResponse> slices) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
