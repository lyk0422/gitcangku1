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
                                     String heldAmount, String requester, String status, long version,
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

    /**
     * 批量净额清算提交命令。
     * requestId 为请求幂等键（同 requestId 同参重放，异参 409）；settlementKey 为成功批次全局唯一键；
     * subjects 必须为指令涉及的全部主体“申请键 + 当前额度版本”的完整集合（不遗漏、不多余）。
     */
    public record SettlementRequest(String commandKey, String requestId, String settlementKey, Long windowId,
                                    List<SettlementInstruction> instructions, List<SubjectVersion> subjects) {
    }

    /** 清算指令：from 转出、to 转入，volume 为正整数体积（立方米），instructionKey 全局唯一，禁止自转。 */
    public record SettlementInstruction(String instructionKey, String from, String to, String volume) {
    }

    /** 主体版本：allocationKey 主体申请键，version 提交方认知的当前额度版本。 */
    public record SubjectVersion(String allocationKey, long version) {
    }

    /** 清算批次视图，创建后不可变。 */
    public record SettlementResponse(String settlementKey, String requestId, long windowId,
                                     int instructionCount, List<SettlementInstructionView> instructions,
                                     List<SubjectSnapshotView> subjects, String createdUtc) {
    }

    /** 清算指令视图：严格按提交输入顺序返回（含相同主体对的多条与成环指令）。 */
    public record SettlementInstructionView(int seqNo, String instructionKey, String from, String to,
                                            String volume) {
    }

    /** 主体净额快照视图：净额、清算前后余额与版本；净额为 0 的主体同样出现。 */
    public record SubjectSnapshotView(String allocationKey, String netChange, String balanceBefore,
                                      String balanceAfter, long versionBefore, long versionAfter) {
    }

    /** 主体清算历史：按提交顺序返回该主体参与过的全部成功批次快照。 */
    public record SubjectSettlementHistoryResponse(String allocationKey, List<SubjectSnapshotView> settlements) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
