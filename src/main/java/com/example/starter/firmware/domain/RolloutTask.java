package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单按尝试序号最多 3 条；首次投放序号为 1，每次显式重试追加一条。
 *
 * @param id            任务ID
 * @param releaseId     所属发布单ID
 * @param deviceId      设备ID
 * @param status        任务状态
 * @param firstResult   首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param attemptNo     尝试序号，从 1 开始，每次显式重试加一
 * @param predecessorId 前驱任务ID，首次投放为 null；同一前驱至多一个后继
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, int attemptNo, Long predecessorId) {
}
