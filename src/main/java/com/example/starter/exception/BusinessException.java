package com.example.starter.exception;

import java.util.List;

/**
 * 业务拒绝异常（HTTP 422）：携带拒绝原因及涉及的电芯编号。
 */
public class BusinessException extends RuntimeException {

    private final List<String> cellCodes;

    public BusinessException(String message) {
        this(message, List.of());
    }

    public BusinessException(String message, List<String> cellCodes) {
        super(message);
        this.cellCodes = cellCodes == null ? List.of() : List.copyOf(cellCodes);
    }

    /**
     * 拒绝涉及的电芯编号列表，无涉及时为空表。
     */
    public List<String> cellCodes() {
        return cellCodes;
    }
}