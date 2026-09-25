package com.example.starter.batch;

import java.util.List;

/**
 * 统一错误响应体：code 机器可区分，message 面向人；
 * pendingItems 仅在 422 需要列出未核销条件子项时出现，其余场景为 null。
 */
public record ErrorResponse(String code, String message, List<String> pendingItems) {

    public ErrorResponse(String code, String message) {
        this(code, message, null);
    }
}
