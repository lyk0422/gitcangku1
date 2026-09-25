package com.example.starter.race.api;

/**
 * 赛道信息响应。
 *
 * @param courseKey 赛道标识
 * @param createdAt 登记时间，Unix毫秒时间戳
 */
public record CourseResponse(String courseKey, long createdAt) {
}
