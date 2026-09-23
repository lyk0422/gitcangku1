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

    // ==================== 多水源配额矩阵与重平衡 ====================

    /** 水源供给上限输入；supplyCap 为非负、最多 3 位小数的十进制字符串（立方米）。 */
    public record SourceCapInput(String sourceId, String supplyCap) {
    }

    /** 配置窗口水源命令：每窗口 1~10 个 sourceId，窗口内唯一，仅可配置一次。 */
    public record ConfigureSourcesRequest(String commandKey, List<SourceCapInput> sources) {
    }

    /** 单个区块在某水源下的初始额度；quota 非负、最多 3 位小数（立方米）。 */
    public record BlockQuotaInput(String sourceId, String quota) {
    }

    /**
     * 登记区块矩阵命令：applicableSources 为该区块可适用水源白名单（目标水源必须在内）；
     * quotas 给出适用水源上的初始额度，未列出的适用水源额度按 0 建格；至少一个水源初始额度为正。
     */
    public record ConfigureBlockRequest(String commandKey, String blockId, List<String> applicableSources,
                                        List<BlockQuotaInput> quotas) {
    }

    /** 用水核销命令：把指定区块-水源单元格的已核销量累加 volume，核销后不得超过该格额度。 */
    public record WriteoffRequest(String commandKey, String writeoffKey, String sourceId, String volume) {
    }

    /** 重平衡明细输入：同一区块内源水源 -> 目标水源调整 volume 立方米；为正、最多 3 位小数。 */
    public record RebalanceDetailInput(String blockId, String sourceSourceId, String targetSourceId,
                                       String volume) {
    }

    /** 涉及额度的期望版本；激活时重读版本不一致整单 409。 */
    public record ExpectedVersionInput(String blockId, String sourceId, Long expectedVersion) {
    }

    /** 重平衡预览请求：不落库、不修改矩阵，仅规范化并计算完整后态。 */
    public record RebalancePreviewRequest(Long windowId, List<RebalanceDetailInput> details) {
    }

    /** 重平衡激活请求：2~50 条明细 + 全部涉及额度的 expectedVersion；requestId 幂等、rebalanceKey 唯一。 */
    public record RebalanceActivateRequest(String requestId, String rebalanceKey, Long windowId,
                                           List<RebalanceDetailInput> details,
                                           List<ExpectedVersionInput> expectedVersions) {
    }

    /** 水源视图。 */
    public record SourceResponse(long windowId, String sourceId, String supplyCap) {
    }

    /** 区块-水源矩阵单元格视图。 */
    public record BlockCellResponse(String sourceId, String quota, String consumed, long version) {
    }

    /** 区块矩阵视图。 */
    public record BlockResponse(long windowId, String blockId, List<String> applicableSources,
                                List<BlockCellResponse> cells) {
    }

    /** 核销结果视图。 */
    public record WriteoffResponse(String writeoffKey, long windowId, String blockId, String sourceId,
                                   String volume, String consumedAfter, long version, String createdUtc) {
    }

    /** 规范化后的重平衡明细视图（同区块同源目标已合并求和，稳定排序）。 */
    public record RebalanceDetailView(String blockId, String sourceSourceId, String targetSourceId,
                                      String volume) {
    }

    /**
     * 冻结的矩阵单元格证据：含额度、已核销量、版本与该水源供给上限快照；
     * 证据列表按 sourceId、blockId 稳定排序。
     */
    public record MatrixCellView(String blockId, String sourceId, String quota, String consumed,
                                 long version, String supplyCap) {
    }

    /** 水源维度汇总：重平衡前后该水源在全区块上的总分配与供给上限。 */
    public record SourceTotalView(String sourceId, String supplyCap, String allocatedBefore,
                                  String allocatedAfter) {
    }

    /** 重平衡预览：规范化明细 + 完整前后矩阵 + 水源汇总 + 守恒标志与违例说明（不落库）。 */
    public record RebalancePreviewResponse(long windowId, List<RebalanceDetailView> normalizedDetails,
                                           List<MatrixCellView> beforeMatrix, List<MatrixCellView> afterMatrix,
                                           List<SourceTotalView> sourceTotals, boolean blockTotalsConserved,
                                           boolean valid, List<String> violations) {
    }

    /** 已激活重平衡单视图：一次性冻结规范化明细、前后矩阵、上限与核销量快照。 */
    public record RebalanceResponse(String rebalanceKey, String requestId, long windowId, String status,
                                    List<RebalanceDetailView> details,
                                    List<MatrixCellView> beforeMatrix, List<MatrixCellView> afterMatrix,
                                    String createdUtc) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
