package com.example.starter.film.support;

import org.springframework.stereotype.Component;

/**
 * 并发控制点：默认空操作；测试可在影片行锁竞争前后插入闩锁，确定性地验证锁顺序，
 * 不靠长时间睡眠判断。生产环境不设置任何钩子，行为为空。
 */
@Component
public class ConcurrencyHooks {

    private volatile Runnable beforeSubmitLock;
    private volatile Runnable insideSubmitAfterLock;
    private volatile Runnable beforeApproveLock;
    private volatile Runnable insideApproveAfterLock;

    /** 片段提交事务尝试获取影片行锁之前执行。 */
    public void runBeforeSubmitLock() {
        Runnable r = beforeSubmitLock;
        if (r != null) {
            r.run();
        }
    }

    /** 片段提交事务拿到影片行锁后、写入新版次前执行。 */
    public void runInsideSubmitAfterLock() {
        Runnable r = insideSubmitAfterLock;
        if (r != null) {
            r.run();
        }
    }

    /** 审核通过事务尝试获取影片行锁之前执行。 */
    public void runBeforeApproveLock() {
        Runnable r = beforeApproveLock;
        if (r != null) {
            r.run();
        }
    }

    /** 审核通过事务拿到影片行锁后、读取片段集合前执行。 */
    public void runInsideApproveAfterLock() {
        Runnable r = insideApproveAfterLock;
        if (r != null) {
            r.run();
        }
    }

    public void setBeforeSubmitLock(Runnable hook) {
        this.beforeSubmitLock = hook;
    }

    public void setInsideSubmitAfterLock(Runnable hook) {
        this.insideSubmitAfterLock = hook;
    }

    public void setBeforeApproveLock(Runnable hook) {
        this.beforeApproveLock = hook;
    }

    public void setInsideApproveAfterLock(Runnable hook) {
        this.insideApproveAfterLock = hook;
    }

    /**
     * 清除全部钩子。
     */
    public void clear() {
        this.beforeSubmitLock = null;
        this.insideSubmitAfterLock = null;
        this.beforeApproveLock = null;
        this.insideApproveAfterLock = null;
    }
}
