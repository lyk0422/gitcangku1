package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id               任务ID
 * @param releaseId        所属发布单ID
 * @param deviceId         设备ID
 * @param status           任务状态
 * @param firstResult      首次回执结果（SUCCESS/FAILED），未回执为 null（历史冻结，不可改）
 * @param taskType         任务类型：PLAIN 普通回执任务；INSTALL 安装握手任务
 * @param cancelGeneration 取消代次，0 表示未请求取消；每次取消请求加一，停止确认须同代次（历史冻结）
 * @param cancelRound      最近一次取消请求时所在监控轮次（历史冻结），未取消为 null
 * @param deviceSlot       占用中的设备独占安装槽，INSTALLING/CANCEL_REQUESTED 时等于 deviceId，其余为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, TaskType taskType, int cancelGeneration,
                          Integer cancelRound, String deviceSlot) {
}
