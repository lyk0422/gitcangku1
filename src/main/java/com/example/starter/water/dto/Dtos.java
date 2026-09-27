package com.example.starter.water.dto;

import java.util.List;

/**
 * 灌区配水 API 请求/响应契约。水量在 API 层一律使用十进制字符串（单位立方米）：
 * 普通水量最多 3 位小数，下泄上限最多 2 位小数且允许为 0；时间使用 ISO-8601 UTC 字符串，
 * 小时区间一律为左闭右开 [hourStart, hourStart+1h)。
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

    /** 提交配水申请命令；申请人由 X-Actor-Id 请求头提供；plan 为按 UTC 小时的配水计划（有曲线窗口必填）。 */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount, List<PlanItemRequest> plan) {
    }

    /** 申请逐小时配水计划点：hourUtc 必须为 UTC 整点并落在窗口内，volume 大于 0，最多 3 位小数。 */
    public record PlanItemRequest(String hourUtc, String volume) {
    }

    /**
     * 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。
     * amount 为不可改写的原申请水量；heldAmount 为当前持有额度（REQUESTED 为 0，普通批准时等于原水量，
     * 转出时等额扣减，取消时归零；持有额度恰为零的已转出申请仍为 APPROVED）。
     * approvedCurveVersion 为批准时固化的曲线版本号（无曲线窗口为 null）；reservations 为批准时刻固化的
     * 逐小时预留快照，曲线后续调整、取消或核销均不改写。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String requester, String status,
                                     String createdUtc, String updatedUtc, Integer approvedCurveVersion,
                                     List<ReservationResponse> reservations) {
    }

    /** 仅含幂等键的命令（批准/取消申请、取消限供）。 */
    public record CommandRequest(String commandKey) {
    }

    /** 批量批准命令：一次事务内按列表顺序处理，任一失败整批回滚。 */
    public record BatchApproveRequest(String commandKey, List<String> allocationKeys) {
    }

    /** 批量批准结果：全部成功时返回，每条固化其批准时的曲线版本。 */
    public record BatchApproveResponse(String commandKey, int processedCount,
                                       List<AllocationResponse> allocations) {
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

    /** 单笔转让（批量命令的元素）。 */
    public record TransferItemRequest(String transferKey, String sourceAllocationKey,
                                      String targetAllocationKey) {
    }

    /** 批量转让命令：多笔转让在一次事务内基于完整最终额度与逐小时预留裁决，任一失败整批回滚。 */
    public record BatchTransferRequest(String commandKey, List<TransferItemRequest> transfers) {
    }

    /** 转让流水视图，创建后不可变，不提供撤销。 */
    public record TransferResponse(String transferKey, long windowId, String sourceAllocationKey,
                                   String targetAllocationKey, String amount, String actor, String createdUtc) {
    }

    /** 窗口转让流水列表视图。 */
    public record TransferListResponse(long windowId, List<TransferResponse> transfers) {
    }

    /** 批量转让结果：全部成功时返回。 */
    public record BatchTransferResponse(String commandKey, int processedCount, List<TransferResponse> transfers) {
    }

    /** 设置/修改下泄曲线命令；points 必须恰好覆盖窗口全部 UTC 整点小时，不得重复、缺失或跨窗口。 */
    public record DrawdownCurveRequest(String commandKey, List<DrawdownPointRequest> points) {
    }

    /** 曲线小时点：capVolume 为该小时下泄上限，非负、最多 2 位小数，单位立方米。 */
    public record DrawdownPointRequest(String hourUtc, String capVolume) {
    }

    /** 曲线小时点视图。 */
    public record DrawdownPointResponse(String hourUtc, String capVolume) {
    }

    /** 曲线版本视图：版本创建后不可变，points 按小时升序。 */
    public record DrawdownCurveResponse(long curveId, long windowId, int version, String status,
                                        List<DrawdownPointResponse> points, String createdUtc) {
    }

    /**
     * 逐小时预留快照视图。reservedVolume 为该申请获批时在该小时的预留水量（不可变）；
     * curveVersion 为校验所依据的曲线版本号；createdUtc 为批准提交时刻。
     */
    public record ReservationResponse(String hourUtc, String reservedVolume, int curveVersion,
                                      String createdUtc) {
    }

    /** 实际核销命令：只扣减 hourUtc 所在小时同一申请的未核销预留，不能挪用其他小时余量。 */
    public record WriteoffRequest(String commandKey, String writeoffKey, String hourUtc, String amount) {
    }

    /** 核销流水视图，创建后不可变。 */
    public record WriteoffResponse(String writeoffKey, String allocationKey, long windowId, String hourUtc,
                                   String amount, String actor, String createdUtc) {
    }

    /** 单小时下泄占用诊断（只读）。 */
    public record DrawdownHourStatusResponse(String hourUtc, String capVolume, String reservedTotal,
                                             String writtenOffTotal, String approvedUnwrittenTotal,
                                             String remainingCap) {
    }

    /** 窗口逐小时下泄诊断视图（只读）：当前 ACTIVE 曲线版本 + 每小时上限、已核销、已批准未核销与余量。 */
    public record DrawdownStatusResponse(long windowId, Integer activeCurveVersion,
                                         List<DrawdownHourStatusResponse> hours) {
    }

    /** 窗口历史明细：窗口本身 + 全部申请 + 全部限供 + 当前曲线 + 全部核销流水。 */
    public record HistoryResponse(WindowResponse window, List<AllocationResponse> allocations,
                                  List<CurtailmentResponse> curtailments,
                                  DrawdownCurveResponse activeCurve, List<WriteoffResponse> writeoffs) {
    }

    /** 逐小时超限明细：实际需求 requiredVolume 相对上限 capVolume 的差额 deficit（均为正数）。 */
    public record HourViolation(long windowId, String hourUtc, String capVolume, String reservedVolume,
                                String requestedVolume, String requiredVolume, String deficit) {
    }

    /** 统一错误响应体；violations 仅在 422 逐小时下泄超限时给出全部超限小时。 */
    public record ErrorResponse(String code, String message, List<HourViolation> violations) {

        public ErrorResponse(String code, String message) {
            this(code, message, null);
        }
    }
}
