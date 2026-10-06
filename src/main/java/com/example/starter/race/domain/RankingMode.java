package com.example.starter.race.domain;

/**
 * 赛事排名模式。
 *
 * <ul>
 *   <li>TOTAL_TIME：仅按总耗时（原始完赛耗时+生效加时）升序，同耗时同名次并跳号；</li>
 *   <li>COUNTBACK：先按总耗时升序，相同再比较最后一段耗时，再比较倒数第二段，
 *       依次到第一段，均较小优先；全部分段与总耗时都相同才并列并跳号，
 *       不以选手ID破实际并列。段耗时取相邻累计时刻差（起点为0，
 *       末段为完赛耗时减最后检查点累计耗时）；处罚只加总成绩、不改分段。</li>
 * </ul>
 */
public enum RankingMode {
    TOTAL_TIME,
    COUNTBACK
}
