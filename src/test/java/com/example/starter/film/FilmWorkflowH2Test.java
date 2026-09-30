package com.example.starter.film;

import com.example.starter.film.model.DeliveryReceipt;
import com.example.starter.film.model.Film;
import com.example.starter.film.model.Master;
import com.example.starter.film.model.MasterSegment;
import com.example.starter.film.model.ReviewRequest;
import com.example.starter.film.model.ReviewStatus;
import com.example.starter.film.model.SegmentVersion;
import com.example.starter.film.repo.MasterRepository;
import com.example.starter.film.repo.ReviewRepository;
import com.example.starter.film.service.FilmQueryService;
import com.example.starter.film.service.FilmService;
import com.example.starter.film.support.TimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 H2 内存库（MODE=MySQL）上的业务集成测试：SQL、唯一约束、回滚、状态流转均实际落库验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FilmWorkflowH2Test {

    @Autowired
    private FilmService service;
    @Autowired
    private FilmQueryService queryService;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private MasterRepository masterRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TimeSource timeSource;

    @BeforeEach
    void clean() {
        // 独立命名库，逐表清理场景数据并复位自增，不依赖外键级联
        jdbc.execute("TRUNCATE TABLE delivery_receipt");
        jdbc.execute("TRUNCATE TABLE master_segment");
        jdbc.execute("TRUNCATE TABLE master");
        jdbc.execute("TRUNCATE TABLE review_segment_baseline");
        jdbc.execute("TRUNCATE TABLE review_request");
        jdbc.execute("TRUNCATE TABLE segment_version");
        jdbc.execute("TRUNCATE TABLE idempotent_request");
        jdbc.execute("TRUNCATE TABLE film");
        timeSource.setClock(Clock.system(TimeSource.ZONE));
    }

    private Film newTwoSegmentFilm() {
        return service.createFilm("F-" + UUID.randomUUID().toString().substring(0, 8),
                "合成影片", 2);
    }

    private SegmentVersion submit(long filmId, int seq, String digest, int base, String prefix) {
        return service.submitSegment(filmId, seq, digest, base,
                prefix + "-" + UUID.randomUUID()).version();
    }

    @Test
    void mainFlow_twoSegments_approve_freezeDeliver_andQuery() {
        Film film = newTwoSegmentFilm();

        SegmentVersion v1 = submit(film.id(), 1, "seg1-v1", 0, "s1");
        SegmentVersion v2 = submit(film.id(), 2, "seg2-v1", 0, "s2");
        assertEquals(1, v1.versionNo());
        assertEquals(1, v2.versionNo());

        ReviewRequest review = service.createReview(film.id(), "rv-" + UUID.randomUUID());
        assertEquals(ReviewStatus.PENDING, review.status());

        var approved = service.approveReview(film.id(), review.id(), "ap-" + UUID.randomUUID());
        assertEquals(ReviewStatus.APPROVED, approved.review().status());
        Master master = approved.master();
        assertEquals("M-" + film.code() + "-" + review.id(), master.masterNo());
        assertEquals(2, master.segmentCount());

        List<MasterSegment> snap = masterRepository.findSegments(master.id());
        assertEquals(List.of(1, 2), snap.stream().map(MasterSegment::seq).toList());
        assertEquals("seg1-v1", snap.get(0).digest());
        assertEquals("seg2-v1", snap.get(1).digest());
        assertEquals(1, snap.get(0).versionNo());
        assertEquals(1, snap.get(1).versionNo());

        String deliveryRequestId = "dl-" + UUID.randomUUID();
        DeliveryReceipt receipt = service.deliver(film.id(), master.masterNo(), deliveryRequestId);
        assertEquals("1:1:seg1-v1|2:1:seg2-v1", receipt.snapshotDigest());

        FilmQueryService.FilmDetail detail = queryService.getDetail(film.id());
        assertEquals(2, detail.segmentChain().size());
        assertEquals(1, detail.reviews().size());
        assertEquals(2, detail.reviews().get(0).baselines().size());
        assertEquals(1, detail.masters().size());
        assertEquals(1, detail.deliveries().size());
    }

    @Test
    void missingSegment_reviewRejected422_andLeavesNoReviewData() {
        Film film = newTwoSegmentFilm();
        submit(film.id(), 1, "seg1-v1", 0, "s1");

        var ex = assertThrows(com.example.starter.film.exception.UnprocessableException.class,
                () -> service.createReview(film.id(), "rv-" + UUID.randomUUID()));
        assertTrue(ex.getMessage().contains("缺少计划片段"));

        assertEquals(0, reviewRepository.findByFilm(film.id()).size());
        assertEquals(0, masterRepository.findByFilm(film.id()).size());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_segment_baseline", Integer.class));
    }

    @Test
    void seqOutOfRange_rejected422() {
        Film film = newTwoSegmentFilm();
        assertThrows(com.example.starter.film.exception.UnprocessableException.class,
                () -> service.submitSegment(film.id(), 3, "seg3-v1", 0,
                        "bad-" + UUID.randomUUID()));
    }

    @Test
    void staleBaseVersion_rejected409_thenCorrectBaseSucceeds() {
        Film film = newTwoSegmentFilm();
        submit(film.id(), 1, "seg1-v1", 0, "s1a");

        assertThrows(com.example.starter.film.exception.ConflictException.class,
                () -> service.submitSegment(film.id(), 1, "seg1-v2", 0, "s1b"));

        SegmentVersion v2 = submit(film.id(), 1, "seg1-v2", 1, "s1c");
        assertEquals(2, v2.versionNo());

        // append-only：旧版次仍然存在，版次链完整
        List<SegmentVersion> chain = queryService.getDetail(film.id()).segmentChain()
                .stream().filter(s -> s.seq() == 1).toList();
        assertEquals(2, chain.size());
        assertEquals("seg1-v1", chain.get(0).digest());
        assertEquals("seg1-v2", chain.get(1).digest());
    }

    @Test
    void segmentSubmit_sameRequestIdSameParams_returnsOriginalVersion() {
        Film film = newTwoSegmentFilm();
        String requestId = "idem-" + UUID.randomUUID();

        var first = service.submitSegment(film.id(), 1, "seg1-v1", 0, requestId);
        var retry = service.submitSegment(film.id(), 1, "seg1-v1", 0, requestId);

        assertEquals(first.version().id(), retry.version().id());
        assertEquals(1, retry.version().versionNo());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment_version WHERE request_id = ?",
                Integer.class, requestId));
    }

    @Test
    void segmentSubmit_sameRequestIdDifferentDigest_409() {
        Film film = newTwoSegmentFilm();
        String requestId = "idemx-" + UUID.randomUUID();
        service.submitSegment(film.id(), 1, "seg1-v1", 0, requestId);

        assertThrows(com.example.starter.film.exception.ConflictException.class,
                () -> service.submitSegment(film.id(), 1, "seg1-v2", 1, requestId));
    }

    @Test
    void pendingReview_expiresWhenAnySegmentChanges_andApproveLeavesNoMaster() {
        Film film = newTwoSegmentFilm();
        submit(film.id(), 1, "seg1-v1", 0, "s1");
        submit(film.id(), 2, "seg2-v1", 0, "s2");
        ReviewRequest review = service.createReview(film.id(), "rv-" + UUID.randomUUID());

        // 第1段产生新修复 → 未决审核过期
        var update = service.submitSegment(film.id(), 1, "seg1-v2", 1, "s1-new");
        assertEquals(1, update.expiredReviewCount());

        ReviewRequest reloaded = reviewRepository.findById(review.id()).orElseThrow();
        assertEquals(ReviewStatus.EXPIRED, reloaded.status());

        assertThrows(com.example.starter.film.exception.ConflictException.class,
                () -> service.approveReview(film.id(), review.id(), "ap-" + UUID.randomUUID()));

        // 失败不留部分母版
        assertEquals(0, masterRepository.findByFilm(film.id()).size());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM master", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM master_segment", Integer.class));
    }

    @Test
    void approvedMaster_isImmutableAgainstLaterWorkVersions() {
        Film film = newTwoSegmentFilm();
        submit(film.id(), 1, "seg1-v1", 0, "s1");
        submit(film.id(), 2, "seg2-v1", 0, "s2");
        ReviewRequest review = service.createReview(film.id(), "rv-" + UUID.randomUUID());
        Master master = service.approveReview(film.id(), review.id(), "ap-" + UUID.randomUUID())
                .master();

        // 母版生成后再更新工作版，母版快照不变
        submit(film.id(), 2, "seg2-v2", 1, "s2-new");
        List<MasterSegment> snap = masterRepository.findSegments(master.id());
        assertEquals("seg2-v1", snap.get(1).digest());
        assertEquals(1, snap.get(1).versionNo());

        // 已通过审核不受后续提交影响，仍可按母版编号交付，交付不读取工作版
        DeliveryReceipt receipt = service.deliver(film.id(), master.masterNo(),
                "dl-" + UUID.randomUUID());
        assertEquals("1:1:seg1-v1|2:1:seg2-v1", receipt.snapshotDigest());
    }

    @Test
    void revokeBlocksNewDeliveryButKeepsExistingReceipt() {
        Film film = newTwoSegmentFilm();
        submit(film.id(), 1, "seg1-v1", 0, "s1");
        submit(film.id(), 2, "seg2-v1", 0, "s2");
        ReviewRequest review = service.createReview(film.id(), "rv-" + UUID.randomUUID());
        Master master = service.approveReview(film.id(), review.id(), "ap-" + UUID.randomUUID())
                .master();

        DeliveryReceipt first = service.deliver(film.id(), master.masterNo(),
                "dl1-" + UUID.randomUUID());
        assertNotNull(first.id());

        ReviewRequest revoked = service.revokeReview(film.id(), review.id(),
                "rk-" + UUID.randomUUID());
        assertEquals(ReviewStatus.REVOKED, revoked.status());

        assertThrows(com.example.starter.film.exception.ConflictException.class,
                () -> service.deliver(film.id(), master.masterNo(), "dl2-" + UUID.randomUUID()));

        // 既有交付回执仍保存曾交付的母版快照
        List<DeliveryReceipt> receipts = queryService.getDetail(film.id()).deliveries();
        assertEquals(1, receipts.size());
        assertEquals(first.id(), receipts.get(0).id());
        assertEquals("1:1:seg1-v1|2:1:seg2-v1", receipts.get(0).snapshotDigest());
    }

    @Test
    void deliveryRetry_sameRequestId_returnsSameReceipt_andDifferentParams409() {
        Film film = newTwoSegmentFilm();
        submit(film.id(), 1, "seg1-v1", 0, "s1");
        submit(film.id(), 2, "seg2-v1", 0, "s2");
        ReviewRequest review = service.createReview(film.id(), "rv-" + UUID.randomUUID());
        Master master = service.approveReview(film.id(), review.id(), "ap-" + UUID.randomUUID())
                .master();

        String requestId = "dlidem-" + UUID.randomUUID();
        DeliveryReceipt first = service.deliver(film.id(), master.masterNo(), requestId);
        DeliveryReceipt retry = service.deliver(film.id(), master.masterNo(), requestId);
        assertEquals(first.id(), retry.id());
        assertEquals(Integer.valueOf(1),
                jdbc.queryForObject("SELECT COUNT(*) FROM delivery_receipt", Integer.class));

        // 同键异参：另取一部影片与母版，409
        Film film2 = newTwoSegmentFilm();
        submit(film2.id(), 1, "x1-v1", 0, "x1");
        submit(film2.id(), 2, "x2-v1", 0, "x2");
        ReviewRequest review2 = service.createReview(film2.id(), "rv2-" + UUID.randomUUID());
        Master master2 = service.approveReview(film2.id(), review2.id(),
                "ap2-" + UUID.randomUUID()).master();
        assertThrows(com.example.starter.film.exception.ConflictException.class,
                () -> service.deliver(film2.id(), master2.masterNo(), requestId));
    }

    @Test
    void deliveryToUnknownMaster_404() {
        Film film = newTwoSegmentFilm();
        assertThrows(com.example.starter.film.exception.NotFoundException.class,
                () -> service.deliver(film.id(), "M-nope-1", "dl-" + UUID.randomUUID()));
    }

    @Test
    void fixedClock_producesDeterministicTimestamps() {
        LocalDateTime fixed = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        timeSource.setClock(Clock.fixed(
                fixed.atZone(TimeSource.ZONE).toInstant(), TimeSource.ZONE));

        Film film = service.createFilm("F-CLOCK", "时钟影片", 1);
        SegmentVersion v = service.submitSegment(film.id(), 1, "seg1-v1", 0,
                "clk-" + UUID.randomUUID()).version();
        ReviewRequest review = service.createReview(film.id(), "rvclk-" + UUID.randomUUID());
        Master master = service.approveReview(film.id(), review.id(),
                "apclk-" + UUID.randomUUID()).master();
        DeliveryReceipt receipt = service.deliver(film.id(), master.masterNo(),
                "dlclk-" + UUID.randomUUID());

        assertEquals(fixed, film.createdAt());
        assertEquals(fixed, v.createdAt());
        assertEquals(fixed, review.createdAt());
        assertEquals(fixed, master.createdAt());
        assertEquals(fixed, receipt.createdAt());
        // 防止固定时钟污染后续测试
        assertFalse(Instant.now().isBefore(Instant.parse("2026-09-01T00:00:00Z")));
    }
}
