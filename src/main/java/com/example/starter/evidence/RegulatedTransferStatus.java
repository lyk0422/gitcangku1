package com.example.starter.evidence;

/**
 * 受监管交接状态。
 * IN_TRANSIT 在途（可签到、可取消、全部签到完成后可接收）；
 * FROZEN 异常冻结（超时或封条不一致后不可签到、不可接收，仅原保管人可取消）；
 * COMPLETED 已接收（终态）；CANCELLED 已取消（终态）。
 */
public enum RegulatedTransferStatus {
    IN_TRANSIT,
    FROZEN,
    COMPLETED,
    CANCELLED
}