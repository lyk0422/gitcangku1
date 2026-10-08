package com.example.starter.race.domain;

/**
 * 中立（转运）赛段区间视图：区间内的耗时不计入净竞技用时。
 *
 * <p>区间以有序检查点的起止序号表示；起点可为 0（发枪零点，累计耗时按 0 计），
 * 终点必须是后续必经检查点。区间之间不得交叠，首尾相接合法。
 */
public interface NeutralLegView {

    /** 起点检查点顺序；0 表示发枪零点。 */
    int startPosition();

    /** 终点检查点顺序，严格大于起点且不超过检查点总数。 */
    int endPosition();
}
