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
     * 处置任务创建请求：taskKey 事件内唯一；groupCode、title 非空；
     * blockerIncidentKeys 为 0~5 个阻塞事件键，必须存在且不能是自身，重复键按去重处理。
     */
    public record TaskCreateRequest(String commandKey, String taskKey, String groupCode,
                                    String title, List<String> blockerIncidentKeys) {
    }

    /** 任务完成/取消请求，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record TaskActionRequest(String commandKey) {
    }

    /**
     * 指令计划单步定义：stepKey 计划内唯一；effectDesc 合成效果说明、
     * compensationDesc 补偿说明均为非空原样文本。
     */
    public record PlanStepDefinition(String stepKey, String effectDesc, String compensationDesc) {
    }

    /**
     * 创建指令计划请求：1~5 个有序步骤，数组顺序即执行顺序（序号从 1 起）；
     * 步骤 stepKey 不可重复。写入须带全局 commandKey 与 X-Request-Id 请求头。
     */
    public record PlanCreateRequest(String commandKey, List<PlanStepDefinition> steps) {
    }

    /**
     * 显式执行步骤请求：只能执行当前下一步；expectedVersion 为调用方持有的计划版本，
     * 与服务端不一致返回 409，为 null 表示不校验。
     */
    public record PlanStepExecuteRequest(String commandKey, Long expectedVersion) {
    }

    /** 取消计划请求：RUNNING/COMPLETED → COMPENSATING；expectedVersion 语义同执行请求。 */
    public record PlanCancelRequest(String commandKey, Long expectedVersion) {
    }

    /**
     * 逐项补偿请求：stepKey 为待补偿步骤，须是当前序号最大的已执行（APPLIED）步骤。
     * evidence 非空表示补偿成功并保存补偿证据；failureDiagnostic 非空表示补偿失败，
     * 仅保存失败诊断、步骤保持 APPLIED，二者必须且只能填一个。
     */
    public record PlanCompensateRequest(String commandKey, Long expectedVersion,
                                        String evidence, String failureDiagnostic) {
    }
}
