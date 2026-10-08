package com.example.starter.race.domain;

import java.util.List;

/**
 * 单个阶段的计算结果。
 *
 * @param stageNo    阶段序号
 * @param fastestMs  该阶段全部完整选手的最快原始用时F（毫秒）；无完整选手为 null
 * @param entries    该阶段全部选手的成绩条目，按参赛号字典序排列
 */
public record StageResult(int stageNo, Long fastestMs, List<StageEntry> entries) {

    /** 该阶段完整（覆盖全部检查点）选手数量。 */
    public int completeCount() {
        int count = 0;
        for (StageEntry entry : entries) {
            if (entry.status() != StageStatus.INCOMPLETE) {
                count++;
            }
        }
        return count;
    }
}
