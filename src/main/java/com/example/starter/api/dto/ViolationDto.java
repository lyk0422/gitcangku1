package com.example.starter.api.dto;

/**
 * 违规明细项。
 *
 * @param code    错误码（如 VERSION_CONFLICT / PATH_NOT_CONTINUOUS / CAPACITY_EXCEEDED）
 * @param message 人类可读的违规描述
 */
public record ViolationDto(String code, String message) {
}
