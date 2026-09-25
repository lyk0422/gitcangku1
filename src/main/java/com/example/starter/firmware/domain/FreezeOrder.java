package com.example.starter.firmware.domain;

import java.util.List;

/**
 * 固件发布冻结令。UTC 左闭右开窗口 [startUtc, endUtc) 内，命中型号或发布单范围的
 * 发布启动与新任务拉取被冻结（422），除非携带完整紧急例外。
 *
 * @param id                    冻结令ID
 * @param version               版本，从1开始，每次修订加一
 * @param freezeKey             幂等键
 * @param models                规范化排序后的硬件型号集合，空列表表示未指定
 * @param releaseIds            规范化排序后的发布单ID集合，空列表表示未指定
 * @param startUtc              窗口开始（UTC ISO-8601，左闭）
 * @param endUtc                窗口结束（UTC ISO-8601，右开），必须晚于开始
 * @param status                状态
 * @param exceptionIncidentId   紧急例外事件号，无例外为 null
 * @param exceptionApprovers    紧急例外确认人（规范化排序），无例外为 null
 * @param revokedAtUtc          撤销时刻（UTC ISO-8601），未撤销为 null
 * @param revokeAffectedTaskIds 撤销时解冻的任务ID，未撤销为 null
 */
public record FreezeOrder(long id, int version, String freezeKey, List<String> models,
                          List<Long> releaseIds, String startUtc, String endUtc, FreezeStatus status,
                          String exceptionIncidentId, List<String> exceptionApprovers,
                          String revokedAtUtc, List<Long> revokeAffectedTaskIds) {
}
