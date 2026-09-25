package com.example.starter.firmware.api;

/**
 * 按硬件型号的投放统计：已下发任务按最终状态计数，并单独统计被兼容矩阵拦截、未产生任务的次数。
 *
 * @param hardwareModel 硬件型号
 * @param pending       PENDING 任务数
 * @param success       SUCCESS 任务数
 * @param failed        FAILED 任务数（首次回执失败）
 * @param cancelled     CANCELLED 任务数
 * @param incompatible  拉取被拦截记录数（不创建任务、不计失败率样本）
 */
public record ModelRolloutStat(String hardwareModel, long pending, long success, long failed,
                               long cancelled, long incompatible) {

    public long totalTasks() {
        return pending + success + failed + cancelled;
    }
}
