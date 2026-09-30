package com.example.starter.tag;

import com.example.starter.tag.service.ConflictException;
import com.example.starter.tag.service.TagService;
import com.example.starter.tag.web.AttachRequest;
import com.example.starter.tag.web.DetachRequest;
import com.example.starter.tag.web.HandoffConfirmRequest;
import com.example.starter.tag.web.HandoffInitiateRequest;
import com.example.starter.tag.web.TagRegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 H2 上的并发测试：用线程池与闩锁协调真实事务，带超时断言，
 * 并在结束后核对数据库最终状态，而非靠休眠或打印判断。
 */
@Import(FixedClockConfig.class)
class TagConcurrencyTest extends AbstractTagIntegrationTest {

    @Autowired
    private TagService tagService;

    TagConcurrencyTest() {
    }

    private static final Instant T08 = Instant.parse("2026-03-10T08:00:00Z");
    private static final Instant T1000 = Instant.parse("2026-03-10T10:00:00Z");

    private void register(String tagCode, String siteCode) {
        TagRegisterRequest request = new TagRegisterRequest();
        request.setTagCode(tagCode);
        request.setSiteCode(siteCode);
        tagService.registerTag(request);
    }

    private AttachRequest attachRequest(String requestId, String tag, String animal,
                                        String site, Instant at) {
        AttachRequest request = new AttachRequest();
        request.setRequestId(requestId);
        request.setTagCode(tag);
        request.setAnimalCode(animal);
        request.setSiteCode(site);
        request.setAttachAt(at);
        return request;
    }

    private String initiateHandoff(String requestId, String tag, String from, String to,
                                   Instant at) {
        HandoffInitiateRequest request = new HandoffInitiateRequest();
        request.setRequestId(requestId);
        request.setTagCode(tag);
        request.setFromSiteCode(from);
        request.setToSiteCode(to);
        request.setInitiateAt(at);
        return tagService.initiateHandoff(request).getHandoffCode();
    }

    /**
     * 摘除与目标站点确认并发：二者在标签行锁上按提交顺序排队。
     * 摘除先提交则确认成功、持有权转移；确认先到则 409、持有权不转移。
     * 无论谁先，同一标签绝不能同时被两站点持有，也不能留下半个所有权转移。
     */
    @Test
    void detachAndConfirmSerializeByCommitOrder() throws Exception {
        register("L", "S1");
        tagService.attach(attachRequest("c-1", "L", "A1", "S1", T08));
        String code = initiateHandoff("c-h1", "L", "S1", "S2", T08);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> confirmError = new AtomicReference<>();

        Future<?> detachFuture = pool.submit(() -> {
            ready.countDown();
            await(start);
            DetachRequest detach = new DetachRequest();
            detach.setTagCode("L");
            detach.setSiteCode("S1");
            detach.setDetachAt(T1000);
            tagService.detach(detach);
        });
        Future<?> confirmFuture = pool.submit(() -> {
            ready.countDown();
            await(start);
            HandoffConfirmRequest confirm = new HandoffConfirmRequest();
            confirm.setHandoffCode(code);
            confirm.setSiteCode("S2");
            confirm.setConfirmAt(T1000);
            try {
                tagService.confirmHandoff(confirm);
            } catch (Throwable ex) {
                confirmError.set(ex);
            }
        });

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        detachFuture.get(10, TimeUnit.SECONDS);
        confirmFuture.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        String holder = jdbcTemplate.queryForObject(
                "SELECT s.site_code FROM tag t JOIN site s ON t.holder_site_id = s.id "
                        + "WHERE t.tag_code = 'L'", String.class);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM handoff WHERE handoff_code = ?", String.class, code);
        Integer openCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment a JOIN tag t ON a.tag_id = t.id "
                        + "WHERE t.tag_code = 'L' AND a.detach_at IS NULL", Integer.class);

        assertEquals(0, openCount, "摘除最终必须生效，标签无未结束附着");
        if ("CONFIRMED".equals(status)) {
            // 摘除先提交：确认成功。
            assertEquals("S2", holder);
            assertNull(confirmError.get());
        } else {
            // 确认先到：409，交接单保持 PENDING，持有权仍在 S1。
            assertEquals("PENDING", status);
            assertEquals("S1", holder);
            assertNotNull(confirmError.get());
            assertInstanceOf(ConflictException.class, confirmError.get());
            assertTrue(confirmError.get().getMessage().contains("must be detached"));
        }
    }

    /**
     * 同一标签两个并发附着（不同动物、不同 requestId、同一时刻）：
     * 恰好一个成功，数据库中只有一条未结束附着。
     */
    @Test
    void concurrentAttachToSameTagOnlyOneWins() throws Exception {
        register("L", "S1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicReference<Throwable> secondError = new AtomicReference<>();

        Future<?> f1 = pool.submit(() -> {
            ready.countDown();
            await(start);
            try {
                tagService.attach(attachRequest("c-2", "L", "A1", "S1", T08));
            } catch (Throwable ex) {
                firstError.set(ex);
            }
        });
        Future<?> f2 = pool.submit(() -> {
            ready.countDown();
            await(start);
            try {
                tagService.attach(attachRequest("c-3", "L", "A2", "S1", T08));
            } catch (Throwable ex) {
                secondError.set(ex);
            }
        });

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        long successCount = (firstError.get() == null ? 1 : 0) + (secondError.get() == null ? 1 : 0);
        assertEquals(1, successCount, "并发附着必须恰好一个成功");
        Throwable loser = firstError.get() != null ? firstError.get() : secondError.get();
        // 负方可能命中唯一约束（409）或锁内区间复查（422），两者都是合法拒绝。
        assertTrue(loser instanceof ConflictException
                        || loser instanceof com.example.starter.tag.service.UnprocessableException,
                "失败方必须是业务冲突或区间交叉，实际: " + loser.getClass());

        Integer openCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment a JOIN tag t ON a.tag_id = t.id "
                        + "WHERE t.tag_code = 'L' AND a.detach_at IS NULL", Integer.class);
        assertEquals(1, openCount);
        Integer distinctHolders = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT animal_id) FROM attachment a "
                        + "JOIN tag t ON a.tag_id = t.id WHERE t.tag_code = 'L' AND a.detach_at IS NULL",
                Integer.class);
        assertEquals(1, distinctHolders, "同一时刻标签最多对应一个动物");
    }

    /**
     * 两个接收站点确认动作并发（同单据重复确认）：恰好一次 CONFIRMED。
     */
    @Test
    void concurrentConfirmOnlyOneTransfersHolder() throws Exception {
        register("L", "S1");
        tagService.attach(attachRequest("c-4", "L", "A1", "S1", T08));
        DetachRequest detach = new DetachRequest();
        detach.setTagCode("L");
        detach.setSiteCode("S1");
        detach.setDetachAt(T1000);
        tagService.detach(detach);
        String code = initiateHandoff("c-h2", "L", "S1", "S2", T1000);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicReference<Throwable> secondError = new AtomicReference<>();

        Future<?> f1 = pool.submit(() -> {
            ready.countDown();
            await(start);
            try {
                HandoffConfirmRequest confirm = new HandoffConfirmRequest();
                confirm.setHandoffCode(code);
                confirm.setSiteCode("S2");
                confirm.setConfirmAt(T1000);
                tagService.confirmHandoff(confirm);
            } catch (Throwable ex) {
                firstError.set(ex);
            }
        });
        Future<?> f2 = pool.submit(() -> {
            ready.countDown();
            await(start);
            try {
                HandoffConfirmRequest confirm = new HandoffConfirmRequest();
                confirm.setHandoffCode(code);
                confirm.setSiteCode("S2");
                confirm.setConfirmAt(T1000);
                tagService.confirmHandoff(confirm);
            } catch (Throwable ex) {
                secondError.set(ex);
            }
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM handoff WHERE handoff_code = ?", String.class, code);
        String holder = jdbcTemplate.queryForObject(
                "SELECT s.site_code FROM tag t JOIN site s ON t.holder_site_id = s.id "
                        + "WHERE t.tag_code = 'L'", String.class);
        assertEquals("CONFIRMED", status);
        assertEquals("S2", holder);
        long successCount = (firstError.get() == null ? 1 : 0) + (secondError.get() == null ? 1 : 0);
        assertEquals(1, successCount, "并发确认必须恰好一次成功");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
