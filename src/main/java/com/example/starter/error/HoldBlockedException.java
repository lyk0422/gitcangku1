package com.example.starter.error;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 销毁申请命中有效冻结：HTTP 422，携带稳定（去重、字典序排序）的冻结键列表。
 */
public class HoldBlockedException extends RuntimeException {

    private final HttpStatus status = HttpStatus.UNPROCESSABLE_ENTITY;
    private final List<String> holdKeys;

    public HoldBlockedException(String message, List<String> holdKeys) {
        super(message);
        this.holdKeys = List.copyOf(holdKeys);
    }

    public HttpStatus status() {
        return status;
    }

    /**
     * 命中冻结的 holdKey 列表（稳定排序）。
     */
    public List<String> holdKeys() {
        return holdKeys;
    }
}
