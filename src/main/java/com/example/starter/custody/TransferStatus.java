package com.example.starter.custody;

/**
 * 移交状态:PENDING=待签收;ACCEPTED=已签收(持有人已变更);REJECTED=已拒绝(持有人不变)。
 */
public enum TransferStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}