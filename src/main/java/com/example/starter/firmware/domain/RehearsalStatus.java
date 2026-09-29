package com.example.starter.firmware.domain;

/**
 * 回滚演练状态。
 * RUNNING 进行中（已开始、结果未提交）；
 * PASSED 全部设备成功回退且结束不早于开始（终态，授予门禁）；
 * FAILED 存在失败结果，或结束时刻早于开始时刻（终态，不授予门禁）。
 * 终态记录不可变。
 */
public enum RehearsalStatus {
    RUNNING,
    PASSED,
    FAILED
}
