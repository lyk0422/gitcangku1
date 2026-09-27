package com.example.starter.firmware.domain;

/**
 * 投放任务状态。
 * PENDING：已拉取，分片接收中，尚不可安装；
 * INSTALLABLE：全部分片接收、顺序完整且聚合摘要匹配，可提交安装回执；
 * INTEGRITY_FAILED：分片缺失、重复或摘要不匹配，禁止安装与成功回执，且不计入设备执行失败率；
 * SUCCESS/FAILED：安装回执终态（仅 INSTALLABLE 可进入）；
 * CANCELLED：已撤销（终态）。
 */
public enum TaskStatus {
    PENDING,
    INSTALLABLE,
    INTEGRITY_FAILED,
    SUCCESS,
    FAILED,
    CANCELLED
}
