package com.example.starter.baggage.error;

/**
 * 资源不存在（HTTP 404）：航段或行李未登记。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
