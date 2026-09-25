package com.example.starter.maintenance.domain;

/**
 * 设备登记计量单位。登记后不可更改；读数与保养周期均按该单位存储。
 */
public enum MeasurementUnit {

    /** 分钟：1 单位 = 1 分钟。 */
    MINUTES,

    /** 小时：1 单位 = 60 分钟。 */
    HOURS
}
