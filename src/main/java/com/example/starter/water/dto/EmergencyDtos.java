package com.example.starter.water.dto;

import java.util.List;

/**
 * 紧急储备水 API 请求/响应契约。水量在 API 层一律使用十进制字符串（单位立方米，最多 3 位小数，
 * 与普通配水额度同一计量单位），时间使用 ISO-8601 UTC 字符串。
 *
 * <p>紧急储备是与普通配水相互独立的账本：储备总量固定，启用只是把水从储备总账预占并转入供水窗口，
 * 任何审批都不会增加总水量；普通排班/容量查询不读取储备。</p>
 */
public final class EmergencyDtos {

    private EmergencyDtos() {
    }

    /** 登记紧急事件命令；仅有 ACTIVE 事件可申请储备水。 */
    public record RegisterEventRequest(String commandKey, String eventKey, String title, String description) {
    }

    /** 紧急事件视图。状态：ACTIVE 有效 / CANCELLED 已失效（不可再启用储备）。 */
    public record EmergencyEventResponse(String eventKey, String title, String description, String status,
                                        String registeredUtc, String cancelledUtc) {
    }

    /**
     * 紧急启用命令：两名不同水务角色（{@code confirmerA} 与 {@code confirmerB}）确认同一事件与同一目标窗口后，
     * 从储备账本预占 {@code volume} 并转入该窗口。{@code requestId} 为启用请求幂等键：
     * 同键重放返回首次转入数量；同键换事件或窗口返回 409。
     */
    public record ActivateRequest(String commandKey, String requestId, String eventKey, Long windowId,
                                  String volume, String confirmerA, String confirmerB) {
    }

    /** 启用审批视图，历史审批记录保留当时事件与两位确认人，创建后不可变。 */
    public record ActivationResponse(long id, String requestId, String eventKey, long windowId, String volume,
                                     String confirmerA, String confirmerB, String status, String createdUtc) {
    }

    /** 核销命令：把已转入窗口的储备水量从窗口占用中核销（实际应急耗用）。 */
    public record WriteOffRequest(String commandKey, String requestId, String volume) {
    }

    /** 归还命令：把窗口中未核销的储备水量退回储备总账，归还不得超过该启用已启用且尚未核销的数量。 */
    public record ReturnRequest(String commandKey, String requestId, String volume) {
    }

    /** 核销/归还流水视图，创建后不可变。 */
    public record EmergencyFlowResponse(long id, String requestId, String eventKey, long windowId, String type,
                                       String volume, String actor, String createdUtc) {
    }

    /** 储备总账视图；总量恒定，可用 = 总量 - 已预占（已转入窗口且未归还）。 */
    public record ReserveResponse(String totalVolume, String reservedVolume, String availableVolume) {
    }

    /** 单条启用的额度视图：启用总量、已核销、已归还、窗口中仍占用（可归还上限）。 */
    public record ActivationBalanceResponse(String requestId, String eventKey, long windowId,
                                           String activatedVolume, String writtenOffVolume,
                                           String returnedVolume, String outstandingVolume, String status) {
    }

    /** 目标窗口的储备预占视图：窗口内全部启用占用、核销与归还流水。 */
    public record WindowReserveResponse(long windowId, String occupiedVolume, String writtenOffVolume,
                                       String returnedVolume, List<ActivationBalanceResponse> activations,
                                       List<EmergencyFlowResponse> flows) {
    }

    /** 紧急事件历史视图：事件本身 + 全部启用审批（留存两位确认人）+ 全部核销/归还流水。 */
    public record EventHistoryResponse(EmergencyEventResponse event,
                                      List<ActivationResponse> activations,
                                      List<EmergencyFlowResponse> flows) {
    }
}
