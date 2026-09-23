package com.example.starter.firmware.api;

import java.util.List;

/**
 * 回退计划明细（只读）：计划概要、逐设备逐跳历史、轮次统计与暂停记录。
 */
public record RollbackPlanDetailResponse(RollbackPlanView plan,
                                         List<RollbackDeviceHistory> devices,
                                         List<RollbackRoundStats> rounds,
                                         List<RollbackPauseRecordView> pauses) {

    /**
     * 单台设备的逐跳历史。
     *
     * @param deviceId 设备ID
     * @param hopCount 反向路径总跳数
     * @param hops     逐跳冻结定义与已派发任务（按跳次升序）
     */
    public record RollbackDeviceHistory(String deviceId, int hopCount, List<RollbackHopHistory> hops) {
    }

    /**
     * 单跳历史：冻结定义 + 历次派发任务（按轮次升序）。
     *
     * @param hopIndex        跳次，从1开始
     * @param expectedVersion 冻结的期望起始版本
     * @param targetVersion   冻结的目标版本
     * @param sourceReleaseId 逆向对应的原正向投放发布单ID
     * @param tasks           该跳已派发的任务（人工恢复后同跳可能出现多条不同轮次的任务）
     */
    public record RollbackHopHistory(int hopIndex, String expectedVersion, String targetVersion,
                                     long sourceReleaseId, List<RollbackHopTaskView> tasks) {
    }

    /**
     * 单轮统计：该轮内首次终结的回跳任务数。
     *
     * @param roundNo 监控轮次，从1开始
     * @param success 该轮首次进入 SUCCESS 的任务数
     * @param failed  该轮首次进入 FAILED 的任务数
     */
    public record RollbackRoundStats(int roundNo, int success, int failed) {
    }
}
