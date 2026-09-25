package com.example.starter.consent;

import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

/**
 * 授权域串行裁决锁：委托、续签、撤销、用途迁移（授权/撤回）与批量查询
 * 在同一 JVM 内按提交顺序裁决，避免跨操作交错产生不一致判定。
 */
@Component
public class ConsentLock {

    private final ReentrantLock lock = new ReentrantLock(true);

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }
}
