package com.example.starter.incident;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提案到期固化事务组件。
 *
 * <p>投票/撤回在持有事件行锁的事务内发现提案到达到期时刻时，必须让提案 EXPIRED
 * 持久化后再返回 410：若在外层事务内更新后抛异常，回滚会把 EXPIRED 一并撤销。
 * 因此到期固化以 REQUIRES_NEW 独立事务提交；该方法只做条件 UPDATE（不 SELECT FOR UPDATE），
 * 不读取外层已锁定的事件行，避免与外层事务争用行锁形成自死锁。
 * 所有提案写操作均先锁事件行，同事件的到期判定因此按事务提交顺序串行。
 */
@Service
public class DecisionExpiryService {

    private final DecisionRepository decisions;

    public DecisionExpiryService(DecisionRepository decisions) {
        this.decisions = decisions;
    }

    /**
     * 在独立已提交事务中将仍 OPEN 的提案固化为 EXPIRED；返回更新行数（0 或 1）。
     * 0 表示提案已被其他已提交事务处理（生效或到期），调用方按最新状态分支即可。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int commitExpiryIfOpen(long proposalId, Instant expiredAt) {
        return decisions.markExpired(proposalId, expiredAt);
    }
}
