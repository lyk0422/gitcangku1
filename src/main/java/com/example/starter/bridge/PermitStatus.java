package com.example.starter.bridge;

/**
 * 许可状态：APPROVED 已签发未进桥；ENTERED 已进桥占用中；EXITED 已出桥；CANCELLED 已撤销。
 */
public enum PermitStatus {
    APPROVED,
    ENTERED,
    EXITED,
    CANCELLED
}