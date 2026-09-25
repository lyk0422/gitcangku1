package com.example.starter.domain;

/** 航班状态机：PENDING → APPROVED → DEPARTED；APPROVED 可被新关闭窗口转为 RUNWAY_RISK；终态 CANCELLED。 */
public enum FlightStatus {
    /** 待审查（新登记或改航后）。 */
    PENDING,
    /** 批量审查通过，已批准。 */
    APPROVED,
    /** 已批准的 NORMAL 航班被新关闭窗口命中，只能改航、取消或转为合格紧急例外。 */
    RUNWAY_RISK,
    /** 已起飞；不再受后续关闭窗口影响。 */
    DEPARTED,
    /** 已取消（终态）。 */
    CANCELLED
}
