package com.example.starter.film;

import com.example.starter.film.api.ApiException;
import com.example.starter.film.api.DeliveryRequest;
import com.example.starter.film.api.DeliveryResponse;
import com.example.starter.film.api.FilmView;
import com.example.starter.film.api.MasterSegmentView;
import com.example.starter.film.api.MasterView;
import com.example.starter.film.api.ReceiptView;
import com.example.starter.film.api.ReviewRequest;
import com.example.starter.film.api.ReviewResponse;
import com.example.starter.film.api.SegmentSubmitRequest;
import com.example.starter.film.api.SegmentSubmitResponse;
import com.example.starter.film.domain.MasterStatus;
import com.example.starter.film.service.FilmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于真实 H2 内存库（MODE=MySQL）的业务流程测试：主流程、拒绝分支、幂等、
 * 事务回滚、唯一约束与真实线程并发，所有结论核对数据库最终状态。
 */
@SpringBootTest
class FilmWorkflowH2Test {

    @Autowired
    private FilmService filmService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM delivery_receipt");
        jdbc.update("DELETE FROM master_segment");
        jdbc.update("DELETE FROM master");
        jdbc.update("DELETE FROM segment_version");
        jdbc.update("DELETE FROM film");
    }

    private static String digest(String tag) {
        return TestData.digest(tag);
    }

    private String newFilmCode() {
        return "F-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private SegmentSubmitResponse submit(String code, int segmentNo, String digest,
                                         Integer basedOn, String requestId) {
        return filmService.submitSegment(code,
                new SegmentSubmitRequest(segmentNo, digest, basedOn, requestId));
    }

    @Test
    void 合成影片两段提交后审核并交付_母版冻结_后来更新不影响交付快照() {
        String code = newFilmCode();
        filmService.registerFilm(code, 2);

        SegmentSubmitResponse s1 = submit(code, 1, digest("seg1-v1"), null, "req-s1");
        SegmentSubmitResponse s2 = submit(code, 2, digest("seg2-v1"), null, "req-s2");
        assertEquals(1, s1.versionNo());
        assertFalse(s1.replayed());
        assertEquals(1, s2.versionNo());

        ReviewResponse review = filmService.review(code, new ReviewRequest("req-review-1"));
        assertFalse(review.replayed());
        assertEquals(MasterStatus.APPROVED, review.status());
        assertEquals(List.of(
                new MasterSegmentView(1, 1, digest("seg1-v1")),
                new MasterSegmentView(2, 1, digest("seg2-v1"))), review.segments());

        // 审核后第 2 段产生新工作版：母版不可变，交付仍引用旧版。
        SegmentSubmitResponse s2v2 = submit(code, 2, digest("seg2-v2"), 1, "req-s2-v2");
        assertEquals(2, s2v2.versionNo());

        DeliveryResponse delivery = filmService.deliver(
                new DeliveryRequest(review.masterNo(), "req-deliver-1"));
        assertFalse(delivery.replayed());
        assertEquals(review.masterNo(), delivery.masterNo());
        assertEquals(List.of(
                new MasterSegmentView(1, 1, digest("seg1-v1")),
                new MasterSegmentView(2, 1, digest("seg2-v1"))), delivery.segments());
        assertNotNull(delivery.receiptNo());

        FilmView view = filmService.getFilm(code);
        assertEquals(2, view.plannedSegments());
        assertEquals(3, view.segmentChain().size());
        assertEquals(1, view.masters().size());
        assertEquals(1, view.receipts().size());
        // 回执快照保存的是旧母版内容，不读取后来的工作版。
        assertTrue(view.receipts().get(0).snapshotJson().contains(digest("seg2-v1")));
        assertFalse(view.receipts().get(0).snapshotJson().contains(digest("seg2-v2")));
    }

    @Test
    void 缺段审核返回422且不留下任何母版记录() {
        String code = newFilmCode();
        filmService.registerFilm(code, 2);
        submit(code, 1, digest("seg1"), null, "req-s1");

        ApiException ex = assertThrows(ApiException.class,
                () -> filmService.review(code, new ReviewRequest("req-review")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("MISSING_SEGMENTS", ex.getCode());

        Long filmId = jdbc.queryForObject("SELECT id FROM film WHERE code = ?", Long.class, code);
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM master WHERE film_id = ?", Long.class, filmId));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM master_segment ms JOIN master m ON ms.master_id = m.id "
                        + "WHERE m.film_id = ?", Long.class, filmId));
    }

    @Test
    void 摘要格式错误与序号越界返回422() {
        String code = newFilmCode();
        filmService.registerFilm(code, 2);

        ApiException badDigest = assertThrows(ApiException.class,
                () -> submit(code, 1, "md5:abc", null, "req-bad-digest"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badDigest.getStatus());

        ApiException outOfRange = assertThrows(ApiException.class,
                () -> submit(code, 3, digest("seg3"), null, "req-range"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, outOfRange.getStatus());
        assertEquals("SEGMENT_OUT_OF_RANGE", outOfRange.getCode());

        ApiException badBase = assertThrows(ApiException.class,
                () -> submit(code, 1, digest("seg1"), 0, "req-base-zero"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badBase.getStatus());

        Long filmId = jdbc.queryForObject("SELECT id FROM film WHERE code = ?", Long.class, code);
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment_version WHERE film_id = ?", Long.class, filmId));
    }

    @Test
    void 基于旧版次提交返回409且不改写旧版记录() {
        String code = newFilmCode();
        filmService.registerFilm(code, 1);
        submit(code, 1, digest("v1"), null, "req-v1");
        submit(code, 1, digest("v2"), 1, "req-v2");

        // 当前版次为 2，基于旧版次 1 的提交必须冲突。
        ApiException ex = assertThrows(ApiException.class,
                () -> submit(code, 1, digest("v3-stale"), 1, "req-v3-stale"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("VERSION_CONFLICT", ex.getCode());

        // 首段已有版本时声称“首版”（basedOnVersion 省略）同样冲突。
        ApiException noBase = assertThrows(ApiException.class,
                () -> submit(code, 1, digest("v3-nobase"), null, "req-v3-nobase"));
        assertEquals(HttpStatus.CONFLICT, noBase.getStatus());

        FilmView view = filmService.getFilm(code);
        assertEquals(2, view.segmentChain().size());
        assertEquals(2, view.segmentChain().get(1).versionNo());
        assertEquals(digest("v2"), view.segmentChain().get(1).contentDigest());
    }

    @Test
    void 同一requestId同参返回原版次_异参409() {
        String code = newFilmCode();
        filmService.registerFilm(code, 1);
        SegmentSubmitRequest first = new SegmentSubmitRequest(1, digest("same"), null, "dup");
        SegmentSubmitResponse r1 = filmService.submitSegment(code, first);
        SegmentSubmitResponse r2 = filmService.submitSegment(code, first);
        assertTrue(r2.replayed());
        assertEquals(r1.versionNo(), r2.versionNo());
        assertEquals(r1.contentDigest(), r2.contentDigest());

        SegmentSubmitRequest different =
                new SegmentSubmitRequest(1, digest("different"), null, "dup");
        ApiException ex = assertThrows(ApiException.class,
                () -> filmService.submitSegment(code, different));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());

        // 同 requestId 用于不同片段也算异参冲突。
        String code2 = newFilmCode();
        filmService.registerFilm(code2, 2);
        SegmentSubmitRequest seg1 = new SegmentSubmitRequest(1, digest("a"), null, "shared-id");
        filmService.submitSegment(code2, seg1);
        ApiException otherSegment = assertThrows(ApiException.class,
                () -> filmService.submitSegment(code2,
                        new SegmentSubmitRequest(2, digest("b"), null, "shared-id")));
        assertEquals(HttpStatus.CONFLICT, otherSegment.getStatus());
    }

    @Test
    void 重复审核返回同一母版_新审核固定新版次() {
        String code = newFilmCode();
        filmService.registerFilm(code, 2);
        submit(code, 1, digest("s1v1"), null, "a1");
        submit(code, 2, digest("s2v1"), null, "a2");

        ReviewRequest reviewRequest = new ReviewRequest("review-dup");
        ReviewResponse first = filmService.review(code, reviewRequest);
        ReviewResponse replay = filmService.review(code, reviewRequest);
        assertTrue(replay.replayed());
        assertEquals(first.masterNo(), replay.masterNo());

        // 片段更新后重新审核：生成另一张母版，固定新的版次集合；旧母版仍在。
        submit(code, 1, digest("s1v2"), 1, "a1v2");
        ReviewResponse second = filmService.review(code, new ReviewRequest("review-2"));
        assertNotEquals(first.masterNo(), second.masterNo());
        assertEquals(2, second.segments().get(0).versionNo());
        assertEquals(1, second.segments().get(1).versionNo());

        List<MasterView> masters = filmService.getFilm(code).masters();
        assertEquals(2, masters.size());
        // 旧母版仍固定在旧版次，没有被改写。
        assertEquals(1, masters.get(0).segments().get(0).versionNo());
    }

    @Test
    void 撤销母版只阻止新交付_既有回执快照保留() {
        String code = newFilmCode();
        filmService.registerFilm(code, 1);
        submit(code, 1, digest("frozen"), null, "a1");
        ReviewResponse review = filmService.review(code, new ReviewRequest("rv"));
        DeliveryResponse delivery = filmService.deliver(
                new DeliveryRequest(review.masterNo(), "d1"));
        String receiptNo = delivery.receiptNo();

        filmService.revoke(review.masterNo());
        // 重复撤销安全。
        filmService.revoke(review.masterNo());

        ApiException blocked = assertThrows(ApiException.class,
                () -> filmService.deliver(new DeliveryRequest(review.masterNo(), "d2")));
        assertEquals(HttpStatus.CONFLICT, blocked.getStatus());
        assertEquals("MASTER_REVOKED", blocked.getCode());

        // 原交付重试仍返回原回执。
        DeliveryResponse replay = filmService.deliver(
                new DeliveryRequest(review.masterNo(), "d1"));
        assertTrue(replay.replayed());
        assertEquals(receiptNo, replay.receiptNo());
        assertEquals(digest("frozen"), replay.segments().get(0).contentDigest());

        FilmView view = filmService.getFilm(code);
        assertEquals(MasterStatus.REVOKED, view.masters().get(0).status());
        assertNotNull(view.masters().get(0).revokedAt());
        assertEquals(1, view.receipts().size());
        assertEquals(receiptNo, view.receipts().get(0).receiptNo());
    }

    @Test
    void 交付重试不生成第二张回执_异参409() {
        String code = newFilmCode();
        filmService.registerFilm(code, 1);
        submit(code, 1, digest("v1"), null, "a1");
        ReviewResponse m1 = filmService.review(code, new ReviewRequest("r1"));

        DeliveryRequest request = new DeliveryRequest(m1.masterNo(), "deliver-dup");
        DeliveryResponse d1 = filmService.deliver(request);
        DeliveryResponse d2 = filmService.deliver(request);
        assertTrue(d2.replayed());
        assertEquals(d1.receiptNo(), d2.receiptNo());

        // 片段更新、再审核得到第二张母版；同一交付 requestId 改引用别的母版必须冲突。
        submit(code, 1, digest("v2"), 1, "a2");
        ReviewResponse m2 = filmService.review(code, new ReviewRequest("r2"));
        ApiException ex = assertThrows(ApiException.class,
                () -> filmService.deliver(new DeliveryRequest(m2.masterNo(), "deliver-dup")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());

        Long filmId = jdbc.queryForObject("SELECT id FROM film WHERE code = ?", Long.class, code);
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_receipt WHERE film_id = ?", Long.class, filmId));
    }

    @Test
    void 不存在的影片与母版返回404() {
        ApiException noFilm = assertThrows(ApiException.class,
                () -> filmService.getFilm("F-MISSING"));
        assertEquals(HttpStatus.NOT_FOUND, noFilm.getStatus());

        ApiException noMaster = assertThrows(ApiException.class,
                () -> filmService.deliver(new DeliveryRequest("M-NOPE", "x")));
        assertEquals(HttpStatus.NOT_FOUND, noMaster.getStatus());
    }

    @Test
    void 并发同requestId交付重试只产生一张回执() throws Exception {
        String code = newFilmCode();
        filmService.registerFilm(code, 1);
        submit(code, 1, digest("v1"), null, "a1");
        ReviewResponse review = filmService.review(code, new ReviewRequest("rv"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<DeliveryResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return filmService.deliver(new DeliveryRequest(review.masterNo(), "race-deliver"));
                }));
            }
            start.countDown();
            String receiptNo = null;
            for (Future<DeliveryResponse> f : futures) {
                DeliveryResponse response = f.get(10, TimeUnit.SECONDS);
                if (receiptNo == null) {
                    receiptNo = response.receiptNo();
                }
                assertEquals(receiptNo, response.receiptNo());
            }
        } finally {
            pool.shutdownNow();
        }

        Long filmId = jdbc.queryForObject("SELECT id FROM film WHERE code = ?", Long.class, code);
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_receipt WHERE film_id = ? AND request_id = ?",
                Long.class, filmId, "race-deliver"));
    }

    @Test
    void 查询展示版次链_母版引用与回执() {
        String code = newFilmCode();
        filmService.registerFilm(code, 2);
        submit(code, 1, digest("1-1"), null, "s1");
        submit(code, 2, digest("2-1"), null, "s2");
        ReviewResponse review = filmService.review(code, new ReviewRequest("rv"));
        filmService.deliver(new DeliveryRequest(review.masterNo(), "dv"));

        FilmView view = filmService.getFilm(code);
        assertEquals(code, view.code());
        assertEquals(List.of(1, 2),
                view.segmentChain().stream().map(v -> v.segmentNo()).toList());
        assertNull(view.segmentChain().get(0).basedOnVersion());
        assertEquals(Integer.valueOf(1), view.segmentChain().get(0).versionNo());

        MasterView master = view.masters().get(0);
        assertEquals(review.masterNo(), master.masterNo());
        assertEquals(List.of(1, 2),
                master.segments().stream().map(MasterSegmentView::segmentNo).toList());

        ReceiptView receipt = view.receipts().get(0);
        assertEquals(review.masterNo(), receipt.masterNo());
        assertEquals("dv", receipt.requestId());
        assertNotNull(receipt.createdAt());
    }
}
