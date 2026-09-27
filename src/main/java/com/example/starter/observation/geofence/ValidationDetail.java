package com.example.starter.observation.geofence;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 422 校验失败明细：数量、时长、坐标等给出实际值、要求值，可计算时给出差额。
 *
 * @param field      出错字段（批量条目带下标，如 items[0].latitude）
 * @param actual     实际值原文
 * @param required   要求值或合法范围描述
 * @param difference 实际值与最近越界边界的差额；不适用时为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationDetail(
        String field,
        String actual,
        String required,
        String difference) {
}
