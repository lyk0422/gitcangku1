package com.example.starter.domain;

/**
 * 替代解析业务失败：触发整次锁定 422 的确定性错误（冲突、环、无可用替代等）。
 */
public class SubstitutionFailureException extends RuntimeException {

    public SubstitutionFailureException(String message) {
        super(message);
    }
}
