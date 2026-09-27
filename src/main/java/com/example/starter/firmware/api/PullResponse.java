package com.example.starter.firmware.api;

import java.util.List;

/**
 * 设备拉取响应。
 *
 * @param task             命中或已存在的任务；无匹配投放或被模块兼容阻断时为 null
 * @param compatibility    兼容判定：GRANTED 模块全部满足直接下发；EXEMPTION 存在模块依靠有效豁免放行；
 *                         MODULE_BLOCKED 存在缺失或低版本模块（不创建任务、不计入失败率）；
 *                         未发生兼容判定（型号/版本/分桶/状态不匹配）时为 null
 * @param blockedModules   阻断明细，按模块名稳定排序，含设备版本（缺失为 null）与最低要求；非阻断为空列表
 * @param exemptedModules  本次命中有效豁免的模块名，按模块名排序；无命中为空列表
 * @param matrixVersion    判定时兼容矩阵版本，未判定时为 null
 * @param exemptionVersion 判定时发布单豁免版本，未判定时为 null
 */
public record PullResponse(TaskView task, String compatibility, List<BlockedModuleView> blockedModules,
                           List<String> exemptedModules, Integer matrixVersion, Integer exemptionVersion) {

    /**
     * 无模块判定的响应：返回已有任务或空响应。
     */
    public PullResponse(TaskView task) {
        this(task, null, List.of(), List.of(), null, null);
    }
}
