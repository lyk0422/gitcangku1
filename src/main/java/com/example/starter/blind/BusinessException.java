package com.example.starter.blind;

import com.example.starter.blind.dto.ViolationView;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务规则异常：携带 HTTP 状态码与可区分的违规明细。
 * 用于代码退役与区组扩容等需要整批校验、一次事务提交的写操作；
 * 抛出后事务回滚，不留半成品，且不占用幂等键。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final List<ViolationView> violations;

    public BusinessException(HttpStatus status, String message, List<ViolationView> violations) {
        super(message);
        this.status = status;
        this.violations = List.copyOf(violations);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public List<ViolationView> getViolations() {
        return violations;
    }

    /** 单条违规的快捷构造。 */
    public static BusinessException single(HttpStatus status, ViolationView violation) {
        return new BusinessException(status, violation.message(), List.of(violation));
    }
}
