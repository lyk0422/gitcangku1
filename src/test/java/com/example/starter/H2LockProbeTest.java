package com.example.starter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2（MODE=MySQL）事务边界测试：验证同一行的真实更新会取得行级排他锁，
 * 第二个事务的同行更新必须阻塞至第一个事务提交或回滚。这是审核与禁飞区变更
 * 串行化、保证一致状态读取的数据库基础，不使用 mock 代替。
 */
@SpringBootTest
@DisplayName("H2 行级排他锁事务互斥")
class H2LockProbeTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("未提交的同行更新阻塞其他事务，提交后放行")
    void sameRowUpdateIsExclusiveUntilCommit() throws Exception {
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch holderUpdated = new CountDownLatch(1);
        Connection holder = dataSource.getConnection();
        Connection waiter = dataSource.getConnection();
        try {
            holder.setAutoCommit(false);
            waiter.setAutoCommit(false);

            Future<?> holdFuture = pool.submit(() -> {
                try (PreparedStatement ps = holder.prepareStatement(
                        "UPDATE coord_lock SET touched = touched + 1 WHERE id = 1")) {
                    ps.executeUpdate();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                holderUpdated.countDown();
                try {
                    Thread.sleep(1500);
                    holder.commit();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            assertTrue(holderUpdated.await(5, TimeUnit.SECONDS));

            Future<Integer> waitFuture = pool.submit(() -> {
                try (PreparedStatement ps = waiter.prepareStatement(
                        "UPDATE coord_lock SET touched = touched + 1 WHERE id = 1")) {
                    return ps.executeUpdate();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            // 持锁事务未提交时，等待方在 500ms 内拿不到锁
            boolean blocked = true;
            try {
                waitFuture.get(500, TimeUnit.MILLISECONDS);
                blocked = false;
            } catch (java.util.concurrent.TimeoutException expected) {
                // 预期：被行锁阻塞
            }
            assertTrue(blocked, "同行更新必须被未提交事务的行级排他锁阻塞");

            holdFuture.get(10, TimeUnit.SECONDS);
            waitFuture.get(10, TimeUnit.SECONDS);
            waiter.commit();

            // 两次更新均已提交：touched 增加 2
            Long touched = jdbc.queryForObject(
                    "SELECT touched FROM coord_lock WHERE id = 1", Long.class);
            assertEquals(2L, touched);
        } finally {
            holder.close();
            waiter.close();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("持锁事务回滚后等待方立即获得锁，回滚更新不残留")
    void rollbackReleasesLock() throws Exception {
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch holderUpdated = new CountDownLatch(1);
        Connection holder = dataSource.getConnection();
        Connection waiter = dataSource.getConnection();
        try {
            holder.setAutoCommit(false);
            waiter.setAutoCommit(false);

            Future<?> holdFuture = pool.submit(() -> {
                try (PreparedStatement ps = holder.prepareStatement(
                        "UPDATE coord_lock SET touched = touched + 100 WHERE id = 1")) {
                    ps.executeUpdate();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                holderUpdated.countDown();
                try {
                    Thread.sleep(800);
                    holder.rollback();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            assertTrue(holderUpdated.await(5, TimeUnit.SECONDS));

            Future<Integer> waitFuture = pool.submit(() -> {
                try (PreparedStatement ps = waiter.prepareStatement(
                        "UPDATE coord_lock SET touched = touched + 1 WHERE id = 1")) {
                    return ps.executeUpdate();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            holdFuture.get(10, TimeUnit.SECONDS);
            waitFuture.get(10, TimeUnit.SECONDS);
            waiter.commit();

            // 回滚方的 +100 不生效，等待方的 +1 生效
            Long touched = jdbc.queryForObject(
                    "SELECT touched FROM coord_lock WHERE id = 1", Long.class);
            assertFalse(touched >= 100, "回滚事务的更新不得残留");
            assertEquals(1L, touched);
        } finally {
            holder.close();
            waiter.close();
            pool.shutdownNow();
        }
    }
}
