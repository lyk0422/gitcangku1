package com.example.starter.error;

import com.example.starter.evidence.dto.MoveItemFailure;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 迁移前置校验失败异常（422）：携带逐项失败说明，
 * 任一证物不满足前置条件时整单拒绝并逐项列出原因。
 */
public class MovePreconditionException extends ApiException {

    private final List<MoveItemFailure> items;

    public MovePreconditionException(String message, List<MoveItemFailure> items) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
        this.items = List.copyOf(items);
    }

    /**
     * 逐项失败说明（证物键 + 原因）。
     */
    public List<MoveItemFailure> items() {
        return items;
    }
}
