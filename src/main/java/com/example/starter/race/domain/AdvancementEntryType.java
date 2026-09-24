package com.example.starter.race.domain;

/**
 * 晋级条目类型：
 * DIRECT-组内按成绩（含处罚加时与并列）取前 Q 名直接晋级；
 * WILDCARD-各组未直接晋级者中按成绩全局取前 W 名补位；
 * NON_ADVANCED-分组内未晋级（仅用于固化的未晋级清单，不属于生效名单）。
 */
public enum AdvancementEntryType {
    DIRECT,
    WILDCARD,
    NON_ADVANCED
}
