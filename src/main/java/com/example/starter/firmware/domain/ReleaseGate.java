package com.example.starter.firmware.domain;

/**
 * 正式投放前门禁结论，授予后不可变。
 *
 * @param id                门禁ID
 * @param model             适用设备型号
 * @param targetVersion     门禁对应的待发布目标版本；新目标版本必须重新演练
 * @param rollbackVersion   门禁对应的回退（来源）版本
 * @param status            门禁结论，固定 READY_FOR_RELEASE
 * @param rehearsalId       授予门禁的 PASSED 演练ID
 * @param operator          门禁授予操作者（结果提交者）
 * @param grantedAtUtc      授予时刻，UTC，ISO-8601，等于演练结束时刻
 */
public record ReleaseGate(long id, String model, String targetVersion, String rollbackVersion,
                          GateStatus status, long rehearsalId, String operator, String grantedAtUtc) {
}
