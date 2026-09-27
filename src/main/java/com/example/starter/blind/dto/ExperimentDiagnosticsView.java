package com.example.starter.blind.dto;

import java.util.List;

/**
 * 实验随机表诊断视图：只读，不改变任何状态。
 * 汇总各区组版本链的实际容量、已分配数、可分配余量与已关闭死名额；封存/扩容状态可区分。
 *
 * @param experimentId         实验编号
 * @param status               实验状态 OPEN / CLOSED
 * @param totalCapacity        全部版本容量合计，实际值，单位：个
 * @param totalAssigned        全部分配数（含退组，退组不释放序号），实际值
 * @param totalRemaining       ACTIVE 版本可分配未分配名额合计，实际值
 * @param totalClosedRemaining CLOSED 版本被关闭的未分配余量合计，实际值，不可再分配
 * @param blocks               各区组诊断明细，按区组号升序
 */
public record ExperimentDiagnosticsView(
        String experimentId,
        String status,
        int totalCapacity,
        int totalAssigned,
        int totalRemaining,
        int totalClosedRemaining,
        List<BlockDiagnosticsView> blocks
) {

    /**
     * 单个区组的诊断明细。
     *
     * @param blockNo              区组号
     * @param currentVersionNo     当前最新版本号
     * @param sealed               最新版本是否已封存
     * @param currentVersionStatus 当前最新版本状态 ACTIVE / CLOSED
     * @param capacityTotal        该区组所有版本容量合计，实际值
     * @param assignedTotal        该区组所有版本已分配数合计，实际值（含退组）
     * @param remainingTotal       该区组 ACTIVE 版本可分配未分配名额合计，实际值
     * @param closedRemainingTotal 该区组 CLOSED 版本被关闭的未分配余量合计，实际值
     * @param versions             版本链诊断，按版本号升序
     */
    public record BlockDiagnosticsView(
            int blockNo,
            int currentVersionNo,
            boolean sealed,
            String currentVersionStatus,
            int capacityTotal,
            int assignedTotal,
            int remainingTotal,
            int closedRemainingTotal,
            List<TableVersionView> versions
    ) {
    }
}
