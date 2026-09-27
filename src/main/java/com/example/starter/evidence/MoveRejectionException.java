package com.example.starter.evidence;

import com.example.starter.evidence.dto.ItemRejection;

import java.util.List;

/**
 * 迁移申请前置校验失败（HTTP 422）：携带逐项失败说明，
 * 由全局异常处理器序列化为带 items 数组的错误响应。
 */
public class MoveRejectionException extends RuntimeException {

    private final List<ItemRejection> items;

    public MoveRejectionException(String message, List<ItemRejection> items) {
        super(message);
        this.items = items;
    }

    /**
     * 逐项失败说明（与申请中证物集合对应）。
     */
    public List<ItemRejection> items() {
        return items;
    }
}
