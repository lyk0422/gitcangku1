package com.example.starter.incident;

/**
 * 指挥交接单状态：PENDING 待目标指挥人接受，ACCEPTED 已接受并生效。
 */
public enum TransferStatus {

    /** 待接受；期间仍由原指挥人负责处置。 */
    PENDING,

    /** 已被目标指挥人接受，指挥权原子切换。 */
    ACCEPTED
}
