package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId           设备唯一标识
 * @param model              设备型号，登记后不可修改
 * @param currentVersion     设备当前固件版本
 * @param bucketNo           灰度分桶号，取值0~99，登记后不可修改
 * @param version            设备配置版本号，从1开始，每次维护窗口修改成功加一
 * @param utcOffsetMinutes   设备本地时区相对UTC的偏移分钟，取值-720~840；null表示未配置维护窗口
 * @param windowStartMinute  每日维护窗口本地起始分钟，取值0~1439，左闭；大于结束分钟表示跨零点
 * @param windowEndMinute    每日维护窗口本地结束分钟，取值0~1439，右开
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo, int version,
                     Integer utcOffsetMinutes, Integer windowStartMinute, Integer windowEndMinute) {

    /**
     * 是否已配置维护窗口（偏移与起止分钟三项同时配置或同时未配置）。
     */
    public boolean hasMaintenanceWindow() {
        return utcOffsetMinutes != null && windowStartMinute != null && windowEndMinute != null;
    }
}
