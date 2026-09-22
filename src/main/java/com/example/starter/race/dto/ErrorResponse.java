package com.example.starter.race.dto;

/**
 * 统一错误响应。
 *
 * @param code    稳定错误码，如 VERSION_CONFLICT、RACE_SEALED
 * @param message 错误描述
 */
public record ErrorResponse(String code, String message) {
}
