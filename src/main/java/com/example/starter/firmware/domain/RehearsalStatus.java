package com.example.starter.firmware.domain;

/**
 * 回滚演练状态：RUNNING 已开始、结果未提交；SUCCEEDED 全部设备成功回退且结束不早于开始（终态）。
 * 任一设备失败时不产生演练终态记录，整批结果回滚，演练保持 RUNNING。
 */
public enum RehearsalStatus {
    RUNNING,
    SUCCEEDED
}
