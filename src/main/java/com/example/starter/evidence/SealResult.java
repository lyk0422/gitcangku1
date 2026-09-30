package com.example.starter.evidence;

/**
 * 巡检封条结论。两名见证人必须给出相同结论：
 * INTACT 封条完好；ANOMALY 封条异常（箱内证物一次转为隔离待复核）。
 */
public enum SealResult {
    INTACT,
    ANOMALY
}
