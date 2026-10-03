package com.example.starter.blackstart;

import jakarta.validation.constraints.Size;

/**
 * 终止计划请求。
 *
 * @param reason 终止原因，可为空
 */
public record TerminatePlanRequest(@Size(max = 255) String reason) {
}