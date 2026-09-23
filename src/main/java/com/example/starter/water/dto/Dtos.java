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
     * quotaVersion 为额度版本，初始 1，每次额度相关变更加一，清算提交须携带全部涉及主体的当前版本。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, long quotaVersion, String requester, String status,
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

    /** 批量净额清算中的一条有向转让指令；from/to 为申请业务键，禁止自转，volume 为正整数体积。 */
    public record SettlementInstructionRequest(String instructionKey, String from, String to, String volume) {
    }

    /** 清算提交携带的主体版本项：主体键（申请业务键）+ 提交方看到的当前额度版本。 */
    public record SettlementVersionEntry(String allocationKey, long version) {
    }

    /**
     * 批量净额清算提交命令。requestId 为幂等键：同参（指令顺序有业务意义）重放首次结果，异参 409。
     * settlementKey 全局唯一，被其他批次使用返回 409；versions 必须恰好覆盖全部涉及主体。
     */
    public record SettlementRequest(String requestId, String settlementKey, Long windowId,
                                    List<SettlementInstructionRequest> instructions,
                                    List<SettlementVersionEntry> versions) {
    }

    /** 清算原始指令视图（输入顺序快照）。 */
    public record SettlementInstructionResponse(int index, String instructionKey, String from, String to,
                                                String volume) {
    }

    /** 清算批次中单个主体的净额与前后余额/版本快照；净额为 0 的主体同样存在且版本加一。 */
    public record SettlementLegResponse(String allocationKey, String netChange, String beforeHeld,
                                        String afterHeld, long beforeVersion, long afterVersion) {
    }

    /** 清算批次详情视图，创建后不可变。 */
    public record SettlementResponse(String settlementKey, long windowId, int instructionCount,
                                     String totalVolume, List<SettlementInstructionResponse> instructions,
                                     List<SettlementLegResponse> legs, String createdUtc) {
    }

    /** 按主体查询清算历史列表视图（只读）。 */
    public record SettlementHistoryResponse(String allocationKey, List<SettlementResponse> settlements) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
