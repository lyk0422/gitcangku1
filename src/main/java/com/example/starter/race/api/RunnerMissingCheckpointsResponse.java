package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事缺失检查点汇总中的单个选手行。
 *
 * @param bib                参赛号
 * @param missingCheckpoints 缺失检查点代码，按检查点顺序排列；无缺失为空列表
 */
public record RunnerMissingCheckpointsResponse(
        String bib,
        List<String> missingCheckpoints
) {
}
