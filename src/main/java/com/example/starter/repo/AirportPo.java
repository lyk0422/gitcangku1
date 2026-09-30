package com.example.starter.repo;

/**
 * 备降机场 PO。
 *
 * @param airportId 机场唯一标识
 * @param capacity  同一窗口可同时容纳的预留数量，恒 &gt;=1
 * @param touch     仅用于预留事务加行级写锁的计数器，无业务含义
 * @param createdAt 创建时间，epoch 毫秒（UTC）
 */
public record AirportPo(String airportId, int capacity, long touch, long createdAt) {
}
