package com.example.starter.race.domain;

/**
 * 赛事排名模式。
 *
 * <ul>
 *   <li>NORMAL：仅按总耗时（原始完赛耗时+生效加时）升序，同耗时并列；</li>
 *   <li>COUNTBACK：先按总耗时升序，相同再依次比较最后一段、倒数第二段……第一段的
 *       段耗时（较小优先），全部比较值都相同才并列；段耗时取相邻检查点累计时刻之差，
 *       起点为0，处罚只加总成绩、不改分段。仅尚无分段记录的 OPEN 赛事可启用。</li>
 * </ul>
 */
public enum RankingMode {
    NORMAL,
    COUNTBACK
}
