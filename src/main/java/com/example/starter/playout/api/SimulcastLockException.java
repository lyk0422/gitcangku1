package com.example.starter.playout.api;

import com.example.starter.playout.api.Dtos.SimulcastChannelFailure;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 联播锁定校验失败异常：整次 422，携带逐频道失败原因；事务回滚，不写入任何锁定。
 */
public class SimulcastLockException extends ApiException {

    private final List<SimulcastChannelFailure> failures;

    public SimulcastLockException(String code, String message, List<SimulcastChannelFailure> failures) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
        this.failures = List.copyOf(failures);
    }

    public List<SimulcastChannelFailure> failures() {
        return failures;
    }
}
