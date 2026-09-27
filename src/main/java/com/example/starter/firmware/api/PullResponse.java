package com.example.starter.firmware.api;

import java.util.List;

/**
 * 设备拉取响应，三种互斥形态：
 * 1. 命中或已有任务：task 非空；
 * 2. 未命中既有门禁（无发布单/PAUSED/版本或分桶不符）：task 为空且 blockCode 为空；
 * 3. MODULE_BLOCKED：task 为空，blockCode=MODULE_BLOCKED，缺失明细按模块名稳定排序。
 * 阻断不创建任务、不计入失败率；blockId/matrixVersion/exemptionVersion 为本次裁决固化的诊断证据。
 */
public record PullResponse(TaskView task, String blockCode, Long blockId,
                           List<MissingModuleView> missingModules, int missingCount,
                           Integer matrixVersion, Integer exemptionVersion) {

    public PullResponse(TaskView task) {
        this(task, null, null, null, 0, null, null);
    }

    public static PullResponse blocked(long blockId, Integer matrixVersion, Integer exemptionVersion,
                                       List<MissingModuleView> missingModules) {
        return new PullResponse(null, "MODULE_BLOCKED", blockId, missingModules, missingModules.size(),
                matrixVersion, exemptionVersion);
    }
}
