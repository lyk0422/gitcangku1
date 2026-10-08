package com.example.starter.race.domain;

/**
 * 阶段赛事的阶段检查点配置视图。
 */
public interface StageCheckpointView {

    /** 阶段序号，从1连续递增。 */
    int stageNo();

    /** 检查点代码。 */
    String checkpointCode();

    /** 阶段内检查点顺序，从1连续递增。 */
    int position();
}
