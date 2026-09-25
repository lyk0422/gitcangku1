package com.example.starter.domain;

/** 航线生命周期状态。 */
public enum RouteStatus {
    /** 待批准：新建或改航（替换）后，尚未通过审查。 */
    DRAFT,
    /** 已批准：最近一次审查通过且结论可用。 */
    APPROVED,
    /** 跑道关闭风险：新关闭窗口命中未来已批准 NORMAL 航线，只能改航、取消或转合格紧急例外。 */
    RUNWAY_RISK,
    /** 已起飞：不再受后续关闭变更影响。 */
    DEPARTED,
    /** 已取消：终态，不可再审查或起飞。 */
    CANCELLED
}
