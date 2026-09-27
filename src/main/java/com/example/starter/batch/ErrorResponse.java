package com.example.starter.batch;

import java.util.Map;

/**
 * 统一错误响应体：code 机器可区分，message 面向人；
 * details 携带结构化差额信息（如效价实际值/要求值/差额），无附加信息时为 null。
 */
public record ErrorResponse(String code, String message, Map<String, Object> details) {
}
