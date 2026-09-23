package com.example.starter.firmware.api;

import java.util.List;

/**
 * 回退计划查询视图：设备逐跳冻结路径（只读）与各（跳,轮）任务执行结果历史。
 */
public record RollbackPlanDetailView(RollbackPlanView plan, List<DevicePathView> devicePaths,
                                     List<RoundStatView> rounds) {

    /**
     * 单设备的一条跳：冻结路径及其在该跳的执行结果（跨轮次取已 SUCCESS 或最近一次终结结果）。
     */
    public record DevicePathView(String deviceId, int hopIndex, String expectedVersion, String toVersion,
                                 long sourceReleaseId, long sourceTaskId,
                                 String hopStatus, Integer completedRound) {
    }

    /**
     * 某跳某轮的任务统计：派发数、成功数、失败数、仍待回执数。
     */
    public record RoundStatView(int hopIndex, int round, int dispatched, int success, int failed, int pending) {
    }
}
