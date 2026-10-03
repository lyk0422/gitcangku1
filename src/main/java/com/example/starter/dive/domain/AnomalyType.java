package com.example.starter.dive.domain;

/**
 * 审核异常类型，标识训练计划限制被违反的具体方式。
 */
public enum AnomalyType {

    /** 无任何点位，无法评估。 */
    NO_POINTS("无点位"),
    /** 单点深度超过计划最大深度（米）。 */
    EXCEED_DEPTH("超深"),
    /** 单点深度为负值，数据非法。 */
    INVALID_DEPTH("非法深度"),
    /** 相邻点位间隔超过5分钟，存在缺测区间。 */
    MEASURE_GAP("缺测"),
    /** 点位时间未严格递增，出现倒退或重复。 */
    TIME_REGRESSION("时间倒退"),
    /** 首末点间隔超过连续作业上限（分钟）。 */
    DURATION_EXCEEDED("超过时长"),
    /** 首末点未覆盖计划作业起止时刻。 */
    COVERAGE_MISSING("起止覆盖缺失");

    private final String label;

    AnomalyType(String label) {
        this.label = label;
    }

    /** 中文标签，用于展示。 */
    public String label() {
        return label;
    }
}