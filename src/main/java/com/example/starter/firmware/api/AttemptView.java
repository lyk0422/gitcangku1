package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.RolloutTask;

/**
 * 单次尝试视图：按尝试序号列出的前后继链节点。
 *
 * @param taskId      本次尝试的任务ID
 * @param attemptNo   尝试序号，从1开始，仅有一次原始任务的数据兼容为序号1
 * @param prevTaskId  前驱任务ID，首次尝试为 null
 * @param status      任务状态
 * @param firstResult 首次回执结果（SUCCESS/FAILED），未回执或已取消为 null
 */
public record AttemptView(long taskId, int attemptNo, Long prevTaskId, String status, String firstResult) {

    public static AttemptView of(RolloutTask task) {
        return new AttemptView(task.id(), task.attemptNo(), task.prevTaskId(), task.status().name(),
                task.firstResult() == null ? null : task.firstResult().name());
    }
}
