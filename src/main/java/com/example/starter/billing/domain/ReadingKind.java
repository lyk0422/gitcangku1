package com.example.starter.billing.domain;

/**
 * 读数种类：基线电量或实际电量。
 */
public enum ReadingKind {

    /** 基线电量（不参与需求响应时的预计用电量），kWh。 */
    BASELINE,

    /** 实际电量（计量表实测用电量），kWh。 */
    ACTUAL
}
