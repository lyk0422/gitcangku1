package com.example.starter.incident.dto;

import java.time.Instant;
import java.util.List;

/**
 * 写接口请求体集合。commandKey 为调用方幂等键；occurredAt 为 UTC 时间。
 */
public final class Requests {

    private Requests() {
    }

    /** 事件上报请求。 */
    public record ReportRequest(String incidentKey, String severity, String summary, String reporter) {
    }

    /** 接管请求。 */
    public record TakeoverRequest(String commandKey) {
    }

    /** 交接发起请求：toCommander 必须不同于当前指挥人。 */
    public record TransferRequest(String commandKey, String toCommander) {
    }

    /** 交接接受请求，操作人由 X-Actor-Id 指定且须为待接受目标人。 */
    public record TransferAcceptRequest(String commandKey) {
    }

    /** 处置记录追加请求。 */
    public record ActionRequest(String commandKey, String actionKey, String actionType,
                                String note, Instant occurredAt) {
    }

    /** 状态变更请求：targetStatus 只允许 CONTAINED / RESOLVED / CLOSED。 */
    public record StatusRequest(String commandKey, String targetStatus) {
    }

    /** 遏制逾期检查请求：以注入 Clock 的当前时刻评估，不做定时扫描。 */
    public record EscalationCheckRequest(String commandKey) {
    }

    /** 升级确认请求：note 为非空处置说明，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record EscalationAckRequest(String commandKey, String note) {
    }

    /**
     * 处置任务创建请求：taskKey 事件内唯一；groupCode、title、workGrid 非空；
     * workGrid 为任务作业网格（简化网格标识，如 X12Y07），创建后固定；
     * blockerIncidentKeys 为 0~5 个阻塞事件键，必须存在且不能是自身，重复键按去重处理。
     * 作业网格与当前有效疏散区域相交时，必须已持有该区域版本的撤离豁免。
     */
    public record TaskCreateRequest(String commandKey, String taskKey, String groupCode,
                                    String title, String workGrid, List<String> blockerIncidentKeys) {

        /** 不含作业网格的兼容构造器：固定到默认网格（不与任何疏散区域相交），供历史调用使用。 */
        public TaskCreateRequest(String commandKey, String taskKey, String groupCode,
                                 String title, List<String> blockerIncidentKeys) {
            this(commandKey, taskKey, groupCode, title, "GRID-DEFAULT", blockerIncidentKeys);
        }
    }

    /** 任务完成/取消/开始/撤离请求，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record TaskActionRequest(String commandKey) {
    }

    /**
     * 批量派工请求：taskKeys 为本次派工的全部任务（至少 1 个）。
     * 先统一校验最终位置（作业网格）、资源依赖（阻塞事件已解除）与撤离豁免，
     * 任一不满足整体 422 且任务状态/租约全部回滚。
     */
    public record TaskBatchDispatchRequest(String commandKey, List<String> taskKeys) {
    }

    /**
     * 疏散区域登记请求：zoneKey 事件内唯一；riskLevel 为 HIGH/MEDIUM/LOW；
     * grids 为网格集合（服务端去空白、去重并字典序规范化）；
     * effectiveFrom/effectiveTo 为 UTC 左闭右开窗口，from &lt; to；
     * 同事件同等级的窗口网格不可与已登记区域重叠。
     */
    public record ZoneRegisterRequest(String commandKey, String zoneKey, String riskLevel,
                                      List<String> grids, Instant effectiveFrom, Instant effectiveTo) {
    }

    /**
     * 撤离豁免授予请求：为 taskKey 指定的任务授予 URL 中 zoneKey 区域当前版本的豁免。
     * 任务已存在时以其作业网格为准；任务尚未创建（创建高危任务前预授权）时必须显式给出
     * workGrid，且该网格必须落在区域网格集合内。
     */
    public record ExemptionGrantRequest(String commandKey, String taskKey, String workGrid) {
    }
}
