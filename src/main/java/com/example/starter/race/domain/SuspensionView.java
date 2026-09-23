package com.example.starter.race.domain;

/**
 * 中止恢复事件视图。
 */
public interface SuspensionView {

    /** 事件键，赛事内唯一。 */
    String eventKey();

    /** 受影响起始检查点代码：已通过该检查点（含其后序检查点）的选手补偿0。 */
    String checkpointKey();

    /** 受影响起始检查点顺序，从1连续递增。 */
    int checkpointPosition();

    /** 中止开始的比赛相对耗时（毫秒）。 */
    long startElapsedMs();

    /** 是否已恢复：false 表示赛事仍处于该事件的 SUSPENDED 状态。 */
    boolean resumed();

    /** 恢复时刻（毫秒）；未恢复时为 null。净计时仅消费已恢复事件，此时非 null。 */
    Long resumeElapsedMs();

    /** 中止时长=resumeElapsedMs-startElapsedMs（毫秒）；未恢复时为 null。 */
    Long durationMs();
}
