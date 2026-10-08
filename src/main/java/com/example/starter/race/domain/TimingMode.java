package com.example.starter.race.domain;

/**
 * 赛事计时模式：
 * SINGLE-单值计时（默认，每个计时点一个确定毫秒值）；
 * INTERVAL-区间计时（每个计时点一个闭区间 [L,U]，仅可证明的名次范围可输出）。
 * 模式仅可在赛事尚无任何计时证据时由 SINGLE 启用为 INTERVAL，启用后不可回退。
 */
public enum TimingMode {
    SINGLE,
    INTERVAL
}
