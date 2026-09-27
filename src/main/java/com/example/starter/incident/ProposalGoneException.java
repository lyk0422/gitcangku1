package com.example.starter.incident;

/**
 * 提案已到期失效（410 GONE）：抛出前同事务已把提案置为 EXPIRED，
 * 服务方法以 noRollbackFor 声明该异常不回滚，使到期判定与 410 响应同时生效；
 * 失败不占用 commandKey（占位插入发生在该异常抛出之后）。
 */
public class ProposalGoneException extends RuntimeException {

    public ProposalGoneException(String message) {
        super(message);
    }
}
