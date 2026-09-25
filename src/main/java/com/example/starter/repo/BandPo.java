package com.example.starter.repo;

/**
 * 区域高度带持久化记录。高度带为左闭右开区间 [lowerM, upperM)，
 * 边界与标识创建后不可变，只允许上调容量。
 *
 * @param zoneId   所属禁飞区标识
 * @param bandId   高度带标识（区域内唯一）
 * @param lowerM   高度下限（含），米
 * @param upperM   高度上限（不含），米
 * @param capacity 同时容量，1~50
 */
public record BandPo(String zoneId, String bandId, int lowerM, int upperM, int capacity) {
}
