package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId          设备唯一标识
 * @param model             设备型号，登记后不可修改
 * @param currentVersion    设备当前固件版本
 * @param bucketNo          灰度分桶号，取值0~99，登记后不可修改
 * @param utcOffsetMinutes  设备时区相对UTC的偏移分钟，取值-720~840；登记默认0
 * @param windowStartMinute 每日维护窗口本地开始分钟，0~1439；登记默认0
 * @param windowEndMinute   每日维护窗口本地结束分钟（左闭右开），0~1439；登记默认0，与开始相等表示全天
 * @param deviceVersion     设备乐观锁版本号，从1开始，每次窗口修订成功加一
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo,
                     int utcOffsetMinutes, int windowStartMinute, int windowEndMinute,
                     int deviceVersion) {
}
