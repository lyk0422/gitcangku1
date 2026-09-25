package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id                       任务ID
 * @param releaseId                所属发布单ID
 * @param deviceId                 设备ID
 * @param status                   任务状态
 * @param firstResult              首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param releaseVersionAtCreate   任务创建时发布单版本快照；历史任务为 null
 * @param releaseVersionAtComplete 首次回执完成时发布单版本快照；未完成或被冻结/取消为 null
 * @param freezeSnapshot           冻结令快照；仅 RELEASE_FROZEN 非空
 * @param emergencyFreezeId        紧急例外放行所针对的冻结令ID；非例外任务为 null。
 *                                 其他冻结令开始扫荡时仍可冻结该任务
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, Integer releaseVersionAtCreate,
                          Integer releaseVersionAtComplete, FreezeSnapshot freezeSnapshot,
                          Long emergencyFreezeId) {

    /**
     * 冻结开始时固化到任务上的冻结令快照，历史不可改。
     *
     * @param freezeId        冻结令ID
     * @param freezeVersion   冻结时刻冻结令版本
     * @param models          冻结时刻硬件型号范围（规范化排序）
     * @param releaseIds      冻结时刻发布单范围（规范化升序）
     * @param windowStartUtc  冻结窗口起点UTC快照
     * @param windowEndUtc    冻结窗口终点UTC快照
     * @param frozenAtUtc     任务被冻结的时刻UTC
     */
    public record FreezeSnapshot(long freezeId, int freezeVersion, String models, String releaseIds,
                                 String windowStartUtc, String windowEndUtc, String frozenAtUtc) {
    }
}
