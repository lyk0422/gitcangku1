package com.example.starter.race.domain;

/**
 * 赛事类型：
 * STANDARD-标准赛事，沿用固定检查点与原始完赛耗时规则；
 * LOOP-绕圈赛事，终点多次经过，按圈次（每圈A、B、FINISH）核验完整性，
 * 以末圈FINISH耗时加生效处罚排名。
 */
public enum RaceType {
    STANDARD,
    LOOP
}