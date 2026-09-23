package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 复核/重新放行整批失败：任一项校验失败则整批拒绝（409），携带逐项失败原因，事务回滚不占幂等键。
 */
public class ItemsConflictException extends ApiException {

    private final List<ItemFailure> failures;

    public ItemsConflictException(String code, String message, List<ItemFailure> failures) {
        super(HttpStatus.CONFLICT, code, message);
        this.failures = List.copyOf(failures);
    }

    public List<ItemFailure> getFailures() {
        return failures;
    }
}
