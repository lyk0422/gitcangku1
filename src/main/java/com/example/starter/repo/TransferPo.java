package com.example.starter.repo;

/**
 * 容量转配单头记录（不可变）。
 *
 * @param transferKey 转配单唯一业务标识
 * @param requestId   激活写操作请求标识
 * @param createdAt   激活时间，epoch 毫秒（UTC）
 */
public record TransferPo(String transferKey, String requestId, long createdAt) {
}
