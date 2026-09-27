package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id            任务ID
 * @param releaseId     所属发布单ID
 * @param deviceId      设备ID
 * @param status        任务状态
 * @param firstResult   首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param frozenChainId 任务创建时刻固化的签名证书链ID；该固件未启用证书时为 null。
 *                      事后链撤销/轮换不改变本快照，回执与复核始终按冻结链裁决
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, Long frozenChainId) {
}
