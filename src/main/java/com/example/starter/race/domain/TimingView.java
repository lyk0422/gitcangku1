package com.example.starter.race.domain;

/**
 * 选手分段通过记录视图。
 */
public interface TimingView {

    /** 全局唯一分段记录ID。 */
    String timingId();

    /** 选手参赛号。 */
    String bib();

    /** 检查点代码。 */
    String checkpointCode();

    /** 检查点顺序，从1连续递增。 */
    int position();

    /** 通过该检查点的累计耗时（毫秒，1~86400000）。 */
    long elapsedMillis();

    /** 产生该读数并据 UTC 左闭右开区间判定归属的芯片代码；选手尚无芯片时为 null。 */
    default String chipCode() {
        return null;
    }

    /** 芯片实际被读取的 UTC 时刻（毫秒）；未上报为 null，归属按接收时刻判定。 */
    default Long readAtMs() {
        return null;
    }
}
