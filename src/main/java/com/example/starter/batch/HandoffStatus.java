package com.example.starter.batch;

/**
 * 跨厂移交单状态机。
 * CREATED 已创建（清单已冻结，批次尚未离厂）；SHIPPED 已发运（清单批次全部 IN_TRANSIT）；
 * RECEIVED 已接收（终态，批次持有厂已切换）；CANCELLED 已取消（终态，批次已恢复发运前状态）。
 */
public enum HandoffStatus {
    CREATED,
    SHIPPED,
    RECEIVED,
    CANCELLED
}
