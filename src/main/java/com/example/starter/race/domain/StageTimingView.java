package com.example.starter.race.domain;

/**
 * 阶段分段通过记录视图：在通用分段视图之上增加阶段序号。
 */
public interface StageTimingView extends TimingView {

    /** 阶段序号，从1连续递增。 */
    int stageNo();
}
