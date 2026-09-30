package com.example.starter.repo;

/**
 * 备降机场容量记录。
 *
 * @param airportId 机场唯一标识
 * @param capacity  同一时段窗口允许同时占用的预留数量，至少为 1
 * @param touch     仅用于对机场行产生真实更新以加行级排他锁的计数器，无业务含义
 */
public record DiversionAirportPo(String airportId, int capacity, long touch) {
}
