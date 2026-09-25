package com.example.starter.batch;

/**
 * 运输段温控状态。
 * NORMAL：登记后尚未发现异常；
 * EXCURSION：存在越界读数或相邻读数间隔超过 30 分钟，终态，异常段与读数历史均不可改写。
 */
public enum SegmentStatus {
    NORMAL,
    EXCURSION
}
