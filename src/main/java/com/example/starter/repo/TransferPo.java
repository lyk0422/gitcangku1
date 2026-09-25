package com.example.starter.repo;

/**
 * 转配单主记录（仅激活成功才落库）。
 *
 * @param transferKey 转配单唯一标识
 * @param requestId   激活请求标识
 * @param itemCount   转配项数量
 * @param activatedAt 激活时间，epoch 毫秒（UTC）
 */
public record TransferPo(String transferKey, String requestId, int itemCount, long activatedAt) {
}
