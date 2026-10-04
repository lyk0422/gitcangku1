package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 批量校验失败的单簇错误明细：区分失败原因，涉及数量/时长时给出实际值、要求值与差额。
 *
 * @param observationId     观测记录唯一标识
 * @param reason            机器可读的失败原因码
 * @param message           失败描述
 * @param actual            实际值（如请求提交的截止时刻；无则 null）
 * @param required          要求值（如簇创建时刻；无则 null）
 * @param differenceSeconds 实际值与要求值的差额（秒，可正可负；无则 null）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetail(
        String observationId,
        String reason,
        String message,
        String actual,
        String required,
        Long differenceSeconds) {
}