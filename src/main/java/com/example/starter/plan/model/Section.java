package com.example.starter.plan.model;

/**
 * 区段目录条目：登记区段起讫站，用于接续站点连续性校验，登记后不可变。
 *
 * @param sectionId   区段 ID
 * @param fromStation 区段始发站
 * @param toStation   区段终到站
 * @param createdAt   登记时刻，UTC 毫秒
 */
public record Section(String sectionId, String fromStation, String toStation, long createdAt) {
}