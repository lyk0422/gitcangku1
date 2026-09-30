package com.example.starter.support;

import org.springframework.stereotype.Component;

/**
 * 并发同步钩子：生产环境全部为空操作；测试中覆盖以在事务提交前制造确定性的并发交错，
 * 验证并发作废与放行按提交顺序决定最终状态。
 */
@Component
public class ConcurrencyHooks {

    /**
     * 作废事务内、提交前（检测已置 VOID、未放行证书已置 INVALID 之后）触发。
     */
    public void beforeVoidCommit(String batchNo) {
        // 默认空操作
    }

    /**
     * 放行事务内、拿到批次行锁后、读取证书状态前触发。
     */
    public void afterReleaseLockAcquired(String batchNo) {
        // 默认空操作
    }
}
