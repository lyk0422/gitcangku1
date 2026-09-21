package com.example.starter.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发起交接请求，目标指挥人必须与当前指挥人不同。
 */
public record InitiateHandoverRequest(
        @NotBlank @Size(max = 128) String commandKey,
        @NotBlank @Size(max = 128) String targetCommanderId) {
}
