package com.example.starter.race.domain;

/**
 * 检查点配置视图：赛事内顺序从1连续递增。
 */
public interface CheckpointView {

    /** 检查点代码，赛事内唯一。 */
    String checkpointCode();

    /** 检查点顺序，从1连续递增。 */
    int position();
}
