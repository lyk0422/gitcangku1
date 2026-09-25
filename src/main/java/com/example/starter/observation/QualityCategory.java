package com.example.starter.observation;

/**
 * 质量问题类别：传感器异常 / 人工误操作 / 环境干扰。
 */
public enum QualityCategory {
    /** 传感器异常 */
    SENSOR_FAULT,
    /** 人工误操作 */
    HUMAN_ERROR,
    /** 环境干扰 */
    ENVIRONMENT_INTERFERENCE
}
