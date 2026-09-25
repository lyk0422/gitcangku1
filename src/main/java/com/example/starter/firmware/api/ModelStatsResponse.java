package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单按硬件型号的投放统计。
 *
 * @param releaseId 发布单ID
 * @param stats     按硬件型号分组的统计，按型号升序
 */
public record ModelStatsResponse(long releaseId, List<ModelStats> stats) {

    /**
     * 单个硬件型号的投放统计。
     *
     * @param hardwareModel 硬件型号
     * @param pending       待回执任务数
     * @param success       成功任务数
     * @param failed        失败任务数
     * @param cancelled     已取消任务数
     * @param incompatible  被兼容矩阵拦截（未创建任务）的设备数
     */
    public record ModelStats(String hardwareModel, long pending, long success, long failed,
                             long cancelled, long incompatible) {
    }
}
