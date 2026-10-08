package com.example.starter.race.domain;

/**
 * 选手在单个阶段内的成绩条目。
 *
 * @param bib                    参赛号
 * @param stageNo                阶段序号
 * @param status                 阶段资格：COMPLETE / OUT / INCOMPLETE
 * @param rawTimeMs              阶段原始用时（毫秒）=最后一个检查点的累计耗时；INCOMPLETE 为 null
 * @param checkpointCount        该阶段检查点总数
 * @param coveredCheckpointCount 该选手该阶段已覆盖检查点数量
 */
public record StageEntry(
        String bib,
        int stageNo,
        StageStatus status,
        Long rawTimeMs,
        int checkpointCount,
        int coveredCheckpointCount
) {
}
