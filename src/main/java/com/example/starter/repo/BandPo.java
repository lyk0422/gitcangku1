package com.example.starter.repo;

/**
 * 区域高度带持久化记录（{@code [lowerAltitude, upperAltitude)} 左闭右开）。
 *
 * @param bandId         高度带唯一标识
 * @param zoneId         所属禁飞区标识
 * @param lowerAltitude  高度带下限（含，米）
 * @param upperAltitude  高度带上限（不含，米）
 * @param capacity       同时段容量，范围 1~50，配置只允许上调
 * @param createdVersion 该带登记时的全局空域版本
 */
public record BandPo(String bandId, String zoneId, int lowerAltitude, int upperAltitude,
                     int capacity, long createdVersion) {
}
