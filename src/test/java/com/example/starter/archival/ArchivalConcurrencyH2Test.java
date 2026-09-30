package com.example.starter.archival;

import com.example.starter.archival.service.ArchivalService;
import com.example.starter.archival.web.ApiConflictException;
import com.example.starter.archival.web.dto.EnrollRequest;
import com.example.starter.archival.web.dto.ReadingRequest;
import com.example.starter.archival.web.dto.ReleaseRequest;
import com.example.starter.archival.web.dto.SealRequest;
import com.example.starter.archival.web.dto.SealResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 H2 + 真实线程并发测试：唯一约束阻止并发双入组、页面调拨与封存按提交顺序确定归属。
 * 所有线程通过 Spring 事务代理执行真实事务，用 CyclicBarrier 对齐起跑，Future 超时兜底，
 * 最终以数据库行状态断言业务结果，不靠打印或固定休眠。
 */
class ArchivalConcurrencyH2Test extends AbstractH2IntegrationTest {

    @Autowired
    private ArchivalService service;

    @Test
    @DisplayName("并发双入组不同批次：唯一约束只放行一个，失败方用原requestId重试成功")
    void concurrentDoubleEnrollOnlyOneSucceedsAndLoserRetriesWithOriginalKey() throws Exception {
        service.registerPage("CP");
        service.createBatch("CA");
        service.createBatch("CB");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                await(barrier);
                service.enroll(new EnrollRequest("CA", "CP", "cp-ca"));
            });
            Future<?> f2 = pool.submit(() -> {
                await(barrier);
                service.enroll(new EnrollRequest("CB", "CP", "cp-cb"));
            });

            AtomicInteger success = new AtomicInteger();
            AtomicInteger conflict = new AtomicInteger();
            collect(f1, success, conflict);
            collect(f2, success, conflict);

            assertThat(success.get()).isEqualTo(1);
            assertThat(conflict.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        // 数据库最终事实：占用唯一、在组 ACTIVE 唯一，没有双占
        assertThat(count("SELECT COUNT(*) FROM page_occupation")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM batch_member WHERE member_status='ACTIVE'"))
                .isEqualTo(1L);
        String holderBatch = jdbc.queryForObject(
                "SELECT b.batch_no FROM page_occupation o "
                        + "JOIN deacidification_batch b ON b.id = o.batch_id",
                String.class);
        assertThat(holderBatch).isIn("CA", "CB");

        // 失败方归还页面后用“原键”重试入组自己的批次：原 requestId 未落库，重试得到确定成功
        service.release(new ReleaseRequest(holderBatch, "CP"));
        String loserBatch = "CA".equals(holderBatch) ? "CB" : "CA";
        String loserKey = "CA".equals(holderBatch) ? "cp-cb" : "cp-ca";
        assertThat(service.enroll(new EnrollRequest(loserBatch, "CP", loserKey)).replayed())
                .isFalse();
        assertThat(count("SELECT COUNT(*) FROM page_occupation")).isEqualTo(1L);
    }

    @Test
    @DisplayName("并发双入组同一批次：只允许一条在组关系")
    void concurrentDoubleEnrollSameBatchOnlyOneSucceeds() throws Exception {
        service.registerPage("SP");
        service.createBatch("SAME");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                await(barrier);
                service.enroll(new EnrollRequest("SAME", "SP", "sp-1"));
            });
            Future<?> f2 = pool.submit(() -> {
                await(barrier);
                service.enroll(new EnrollRequest("SAME", "SP", "sp-2"));
            });

            AtomicInteger success = new AtomicInteger();
            AtomicInteger conflict = new AtomicInteger();
            collect(f1, success, conflict);
            collect(f2, success, conflict);

            assertThat(success.get()).isEqualTo(1);
            assertThat(conflict.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("SELECT COUNT(*) FROM page_occupation")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM batch_member")).isEqualTo(1L);
    }

    @Test
    @DisplayName("封存与归还并发：按提交顺序归属，绝不既归还又保留占用，也绝不封存后仍占用")
    void sealVsReleaseConcurrencyCommitOrderOwnership() throws Exception {
        // 重复 12 轮制造两种提交顺序的机会；每轮独立批次与页面
        for (int idx = 0; idx < 12; idx++) {
            final int i = idx;
            final String pageNo = "X" + i;
            final String batchNo = "BX" + i;
            service.registerPage(pageNo);
            service.createBatch(batchNo);
            service.enroll(new EnrollRequest(batchNo, pageNo, "enr-" + i));
            service.recordReading(new ReadingRequest(batchNo, pageNo,
                    new BigDecimal("7.00"), new BigDecimal("7.20"), "LOT-" + i));

            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<SealResult> sealFuture = pool.submit(() -> {
                    await(barrier);
                    return service.seal(new SealRequest(batchNo, "seal-" + i));
                });
                Future<?> releaseFuture = pool.submit(() -> {
                    await(barrier);
                    service.release(new ReleaseRequest(batchNo, pageNo));
                    return null;
                });

                boolean sealed;
                boolean released;
                try {
                    SealResult r = sealFuture.get(30, TimeUnit.SECONDS);
                    sealed = r.sealed();
                } catch (Exception e) {
                    sealed = false;
                }
                try {
                    releaseFuture.get(30, TimeUnit.SECONDS);
                    released = true;
                } catch (Exception e) {
                    released = false;
                }

                // 互斥归属：封存成功与归还成功不可同时发生
                assertThat(sealed && released)
                        .as("第%d轮：封存与归还不能同时成功", i)
                        .isFalse();

                // 不变量1：无论谁先提交，处理占用都必须不存在（封存释放或归还释放）
                assertThat(count("SELECT COUNT(*) FROM page_occupation"))
                        .as("第%d轮：不得残留占用", i)
                        .isZero();

                String status = jdbc.queryForObject(
                        "SELECT status FROM deacidification_batch WHERE batch_no = ?",
                        String.class, batchNo);
                String memberStatus = jdbc.queryForObject(
                        "SELECT member_status FROM batch_member m "
                                + "JOIN deacidification_batch b ON b.id = m.batch_id "
                                + "WHERE b.batch_no = ?",
                        String.class, batchNo);

                // 不变量2：封存先提交 → 批次 SEALED、成员仍 ACTIVE；归还先提交 → 成员 RELEASED、批次未封存
                if (sealed) {
                    assertThat(status).as("第%d轮：封存方先提交", i).isEqualTo("SEALED");
                    assertThat(memberStatus).isEqualTo("ACTIVE");
                    assertThat(released).isFalse();
                } else {
                    assertThat(status).as("第%d轮：归还方先提交，批次不得封存", i)
                            .isNotEqualTo("SEALED");
                    assertThat(memberStatus).isEqualTo("RELEASED");
                    assertThat(released).isTrue();
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("并发栅栏等待失败", e);
        }
    }

    private void collect(Future<?> future, AtomicInteger success, AtomicInteger conflict)
            throws Exception {
        try {
            future.get(30, TimeUnit.SECONDS);
            success.incrementAndGet();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof ApiConflictException) {
                conflict.incrementAndGet();
            } else {
                throw e;
            }
        }
    }
}
