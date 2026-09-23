package com.example.starter.handoff;

/**
 * 跨厂移交单状态机。
 * CREATED 已创建并冻结清单，尚未发运；IN_TRANSIT 清单全部批次在途；
 * RECEIVED 目标厂已整体接收（终态）；CANCELLED 源厂在接收前整单取消（终态）。
 */
public enum HandoffStatus {
    CREATED,
    IN_TRANSIT,
    RECEIVED,
    CANCELLED
}
