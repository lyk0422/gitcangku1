package com.example.starter.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 接受交接请求，由待接受的目标指挥人发起。
 */
public record AcceptHandoverRequest(
        @NotBlank @Size(max = 128) String commandKey) {
}
