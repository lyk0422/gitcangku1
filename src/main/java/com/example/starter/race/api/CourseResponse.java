package com.example.starter.race.api;

/**
 * 赛道信息响应。
 *
 * @param courseKey       赛道标识
 * @param currentRecordId 当前纪录ID；null 表示尚无纪录
 * @param createdAt       登记时间，Unix毫秒时间戳
 */
public record CourseResponse(String courseKey, String currentRecordId, long createdAt) {
}
