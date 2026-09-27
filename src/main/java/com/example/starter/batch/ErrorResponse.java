package com.example.starter.batch;

import java.math.BigDecimal;

/**
 * 统一错误响应体：code 机器可区分，message 面向人。
 * actual/required/difference 为数量、时长或比例类失败的实际值、要求值与差额，
 * 非数量类错误为 null（null 语义稳定，字段始终出现）。
 */
public record ErrorResponse(String code, String message,
                            BigDecimal actual, BigDecimal required, BigDecimal difference) {
}
