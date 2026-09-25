package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CapacityBucketDto;
import com.example.starter.api.dto.CapacityBucketRequest;
import com.example.starter.api.dto.DepartRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PreemptionDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteStateResult;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 紧急备降优先级与时空容量抢占的 H2 数据库测试（MODE=MySQL）。
 * 覆盖：紧急优先级声明校验、容量约束、完整受影响集合原子置换、
 * 不可抢占状态（已起飞/EMERGENCY）、422 列表、抢占快照冻结、
 * 重新提交审查、单条未处理抢占记录约束、幂等重放与真实并发。
 */
@SpringBootTest
@DisplayName("紧急备降优先级与时空容量抢占")
class DiversionPriorityH2Test {

    /** 测试时间窗起点（epoch 毫秒），落在整分钟之内以验证规范化。 */
    private static final long WIN_START = 1_000_000_000_000L;
    private static final long WIN_END = WIN_START + 30 * 60_000L;
    private static final long START_MIN = WIN_START / 60_000L;
    private static final long END_MIN = WIN_END / 60_000L;
    private static final String BUCKET_KEY = "1:2:" + START_MIN + ":" + END_MIN;

    @Autowired
    private com.example.starter.service.AirspaceReviewService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void cleanBefore() {
        cleanup();
    }

    @AfterEach
    void cleanAfter() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM preemption");
        jdbc.update("DELETE FROM bucket_occupancy");
        jdbc.update("DELETE FROM capacity_bucket");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
    }

    private String rid(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private static List<RoutePointDto> pts(int... xy) {
        return java.util.stream.IntStream.range(0, xy.length / 2)
                .mapToObj(i -> new RoutePointDto(xy[2 * i], xy[2 * i + 1]))
                .toList();
    }

    private void createRoute(String routeId) {
        service.createRoute(new RouteCreateRequest(routeId, pts(0, 10, 100, 10),
                "req-" + rid("route")));
    }

    private void createBucket(int cellX, int cellY, int capacity) {
        service.createCapacityBucket(new CapacityBucketRequest(
                cellX, cellY, WIN_START, WIN_END, capacity, "req-" + rid("bucket")));
    }

    private ReviewRequest reviewReq(String routeId, String priority, String eventNo, int cellX, int cellY) {
        return new ReviewRequest(routeId, 1, 0L, priority, eventNo,
                cellX, cellY, WIN_START, WIN_END, "req-" + rid("review"));
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    private String routeStatus(String routeId) {
        return jdbc.queryForObject("SELECT status FROM route WHERE route_id = ?",
                String.class, routeId);
    }

    // ============================ 优先级声明校验 ============================

    @Test
    @DisplayName("EMERGENCY 缺事件编号 400；非法优先级 400；时空段不完整或退化 400")
    void priorityAndSegmentValidation() {
        createRoute("v1");
        ApiException noEvent = assertThrows(ApiException.class, () -> service.review(
                reviewReq("v1", "EMERGENCY", null, 1, 2)));
        assertEquals(HttpStatus.BAD_REQUEST, noEvent.status());
        assertEquals("EVENT_NO_REQUIRED", noEvent.code());

        ApiException blankEvent = assertThrows(ApiException.class, () -> service.review(
                reviewReq("v1", "EMERGENCY", "  ", 1, 2)));
        assertEquals(HttpStatus.BAD_REQUEST, blankEvent.status());

        ApiException badPriority = assertThrows(ApiException.class, () -> service.review(
                reviewReq("v1", "VIP", null, 1, 2)));
        assertEquals(HttpStatus.BAD_REQUEST, badPriority.status());
        assertEquals("INVALID_PRIORITY", badPriority.code());

        // 时空段四字段必须同时出现
        ApiException partial = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("v1", 1, 0L, "NORMAL", null,
                        1, null, WIN_START, WIN_END, "req-" + rid("review"))));
        assertEquals(HttpStatus.BAD_REQUEST, partial.status());
        assertEquals("SEGMENT_INCOMPLETE", partial.code());

        // 规范化后时间窗退化（同一分钟内）
        ApiException degenerate = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("v1", 1, 0L, "NORMAL", null,
                        1, 2, WIN_START, WIN_START + 1_000L, "req-" + rid("review"))));
        assertEquals(HttpStatus.BAD_REQUEST, degenerate.status());
        assertEquals("INVALID_TIME_WINDOW", degenerate.code());

        // 全部失败不占键（去重记录数保持建航线后的 1 条不变）、不产生审核记录
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM request_dedup", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
    }

    // ============================ 容量约束 ============================

    @Test
    @DisplayName("NORMAL 超容 422 且失败不占键；未建桶时空段不限容量")
    void normalCapacityExceededAndUnboundedWithoutBucket() {
        createRoute("n1");
        createRoute("n2");
        createRoute("n3");
        createBucket(1, 2, 1);

        MutationResponse first = service.review(reviewReq("n1", "NORMAL", null, 1, 2));
        assertEquals("CLEAR", dataOf(first).conclusion());
        assertEquals(BUCKET_KEY, dataOf(first).bucketKey());
        assertEquals("APPROVED", routeStatus("n1"));

        // 容量 1 已被占用：第二条 NORMAL 422
        int dedupBefore = jdbc.queryForObject("SELECT COUNT(*) FROM request_dedup", Integer.class);
        ApiException full = assertThrows(ApiException.class, () -> service.review(
                reviewReq("n2", "NORMAL", null, 1, 2)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, full.status());
        assertEquals("CAPACITY_EXCEEDED", full.code());
        // 失败回滚：无审核记录、无占用、状态仍 PENDING、去重键未被占用
        assertEquals("PENDING", routeStatus("n2"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM bucket_occupancy", Integer.class));
        assertEquals(dedupBefore,
                jdbc.queryForObject("SELECT COUNT(*) FROM request_dedup", Integer.class));

        // 未建桶的时空段不限容量
        MutationResponse other = service.review(reviewReq("n3", "NORMAL", null, 9, 9));
        assertEquals("CLEAR", dataOf(other).conclusion());
        assertEquals("9:9:" + START_MIN + ":" + END_MIN, dataOf(other).bucketKey());
    }

    @Test
    @DisplayName("容量桶创建：重复 409、同键重放、查询占用与剩余")
    void capacityBucketCrud() {
        CapacityBucketRequest req = new CapacityBucketRequest(
                1, 2, WIN_START, WIN_END, 2, "req-bucket-fixed");
        MutationResponse created = service.createCapacityBucket(req);
        assertFalse(created.replayed());
        CapacityBucketDto dto = objectMapper.convertValue(created.data(), CapacityBucketDto.class);
        assertEquals(BUCKET_KEY, dto.bucketKey());
        assertEquals(2, dto.capacity());
        assertEquals(0, dto.used());
        assertEquals(2, dto.remaining());

        // 同键同参重放
        MutationResponse replay = service.createCapacityBucket(req);
        assertTrue(replay.replayed());
        // 异参（不同容量）→ 409
        ApiException dup = assertThrows(ApiException.class, () -> service.createCapacityBucket(
                new CapacityBucketRequest(1, 2, WIN_START, WIN_END, 5, "req-" + rid("bucket"))));
        assertEquals(HttpStatus.CONFLICT, dup.status());
        assertEquals("BUCKET_ALREADY_EXISTS", dup.code());

        // 占用后查询 used/remaining
        createRoute("b1");
        service.review(reviewReq("b1", "NORMAL", null, 1, 2));
        List<CapacityBucketDto> buckets = service.listCapacityBuckets();
        assertEquals(1, buckets.size());
        assertEquals(1, buckets.get(0).used());
        assertEquals(1, buckets.get(0).remaining());
    }

    // ============================ 紧急抢占主流程 ============================

    @Test
    @DisplayName("EMERGENCY 抢占：完整集合原子置换、快照写入、查询可见")
    void emergencyPreemptsApprovedNormalAtomically() {
        createRoute("na");
        createRoute("nb");
        createRoute("em");
        createBucket(1, 2, 2);
        service.review(reviewReq("na", "NORMAL", null, 1, 2));
        service.review(reviewReq("nb", "NORMAL", null, 1, 2));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM bucket_occupancy", Integer.class));

        // 容量 2 已满，EMERGENCY 抢占全部未起飞 NORMAL
        MutationResponse resp = service.review(reviewReq("em", "EMERGENCY", "EV-001", 1, 2));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("CLEAR", dto.conclusion());
        assertEquals("EMERGENCY", dto.priority());
        assertEquals("EV-001", dto.eventNo());
        assertEquals(BUCKET_KEY, dto.bucketKey());
        assertEquals("APPROVED", routeStatus("em"));

        // 两条 NORMAL 全部置换为 DISPLACED，占用释放给紧急航线
        assertEquals("DISPLACED", routeStatus("na"));
        assertEquals("DISPLACED", routeStatus("nb"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM bucket_occupancy", Integer.class));
        assertEquals("em", jdbc.queryForObject(
                "SELECT route_id FROM bucket_occupancy WHERE bucket_key = ?",
                String.class, BUCKET_KEY));

        // 不可变抢占快照：两条 PENDING 记录，字段完整
        List<PreemptionDto> preemptions = service.listPreemptions(null, null);
        assertEquals(2, preemptions.size());
        for (PreemptionDto p : preemptions) {
            assertEquals(BUCKET_KEY, p.bucketKey());
            assertEquals("em", p.emergencyRouteId());
            assertEquals("EV-001", p.emergencyEventNo());
            assertEquals(dto.reviewId(), p.emergencyReviewId());
            assertEquals(1, p.displacedRouteVersion());
            assertEquals("PENDING", p.state());
            assertNull(p.processedAt());
        }
        // 被置换航线查询
        List<RouteStateResult> displaced = service.listDisplacedRoutes();
        assertEquals(2, displaced.size());
        assertTrue(displaced.stream().allMatch(d -> "DISPLACED".equals(d.status())));
        // 容量桶查询：占用 1
        assertEquals(1, service.listCapacityBuckets().get(0).used());
    }

    @Test
    @DisplayName("已起飞 NORMAL 不可抢占：422 并列出不可抢占航线")
    void departedNormalIsNotPreemptable() {
        createRoute("dep");
        createRoute("em");
        createBucket(1, 2, 1);
        service.review(reviewReq("dep", "NORMAL", null, 1, 2));
        service.depart(new DepartRequest("dep", "req-" + rid("depart")));
        assertEquals("DEPARTED", routeStatus("dep"));

        ApiException ex = assertThrows(ApiException.class, () -> service.review(
                reviewReq("em", "EMERGENCY", "EV-002", 1, 2)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("CAPACITY_INSUFFICIENT", ex.code());
        assertEquals(List.of("dep"), ex.details().get("nonPreemptableRouteIds"));

        // 整次回滚：紧急航线未批准、无占用、无快照、无审核记录、不占键
        assertEquals("PENDING", routeStatus("em"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM bucket_occupancy", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM preemption", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
    }

    @Test
    @DisplayName("EMERGENCY 不可抢占另一 EMERGENCY；移除可抢占集合后仍不足 422")
    void emergencyCannotPreemptEmergency() {
        createRoute("e1");
        createRoute("e2");
        createBucket(1, 2, 1);
        service.review(reviewReq("e1", "EMERGENCY", "EV-101", 1, 2));

        ApiException ex = assertThrows(ApiException.class, () -> service.review(
                reviewReq("e2", "EMERGENCY", "EV-102", 1, 2)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals(List.of("e1"), ex.details().get("nonPreemptableRouteIds"));
        assertEquals("PENDING", routeStatus("e2"));
    }

    @Test
    @DisplayName("已起飞 + EMERGENCY 占满时，移除全部可抢占集合后容量仍不足 422")
    void insufficientEvenAfterRemovingPreemptableSet() {
        createRoute("dep");
        createRoute("e1");
        createRoute("norm");
        createRoute("e2");
        createBucket(1, 2, 3);
        service.review(reviewReq("dep", "NORMAL", null, 1, 2));
        service.depart(new DepartRequest("dep", "req-" + rid("depart")));
        service.review(reviewReq("e1", "EMERGENCY", "EV-201", 1, 2));
        service.review(reviewReq("norm", "NORMAL", null, 1, 2));
        // 占用 3/3：dep(DEPARTED) + e1(EMERGENCY) + norm(NORMAL APPROVED)
        // e2 紧急：可抢占集合={norm}，3-1+1=3 <= 3 → 可抢占，先验证边界成立
        MutationResponse ok = service.review(reviewReq("e2", "EMERGENCY", "EV-202", 1, 2));
        assertEquals("CLEAR", dataOf(ok).conclusion());
        assertEquals("DISPLACED", routeStatus("norm"));

        // 现在占用 dep + e1 + e2（全部不可抢占），新紧急航线 e3 必 422 且列出全部三条
        createRoute("e3");
        ApiException ex = assertThrows(ApiException.class, () -> service.review(
                reviewReq("e3", "EMERGENCY", "EV-203", 1, 2)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals(List.of("dep", "e1", "e2"), ex.details().get("nonPreemptableRouteIds"));
    }

    // ============================ 置换后重新提交与快照 ============================

    @Test
    @DisplayName("被置换航线可重新提交审查：记录转 RESUBMITTED，不自动复原")
    void displacedRouteResubmissionClosesPendingRecord() {
        createRoute("na");
        createRoute("em");
        createBucket(1, 2, 1);
        service.review(reviewReq("na", "NORMAL", null, 1, 2));
        service.review(reviewReq("em", "EMERGENCY", "EV-301", 1, 2));
        assertEquals("DISPLACED", routeStatus("na"));

        // 不自动复原：重新提交前保持 DISPLACED
        assertEquals(1, service.listPreemptions("na", "PENDING").size());

        // 在不冲突的时空段重新提交审查 → CLEAR → 重新批准
        MutationResponse re = service.review(reviewReq("na", "NORMAL", null, 5, 5));
        assertEquals("CLEAR", dataOf(re).conclusion());
        assertEquals("APPROVED", routeStatus("na"));

        // 未处理记录已关闭
        assertEquals(0, service.listPreemptions("na", "PENDING").size());
        List<PreemptionDto> done = service.listPreemptions("na", "RESUBMITTED");
        assertEquals(1, done.size());
        assertNotNull(done.get(0).processedAt());
        // 非法 state 过滤 400
        ApiException bad = assertThrows(ApiException.class,
                () -> service.listPreemptions(null, "BOGUS"));
        assertEquals(HttpStatus.BAD_REQUEST, bad.status());
    }

    @Test
    @DisplayName("同一 NORMAL 航线只允许一条未处理抢占记录：第二次抢占整次回滚")
    void onlyOnePendingPreemptionPerRoute() {
        createRoute("na");
        createRoute("e1");
        createRoute("e2");
        // 两个桶容量均为 1；na 在两个桶都已批准
        createBucket(1, 2, 1);
        createBucket(7, 7, 1);
        service.review(reviewReq("na", "NORMAL", null, 1, 2));
        service.review(reviewReq("na", "NORMAL", null, 7, 7));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM bucket_occupancy", Integer.class));

        // e1 在桶 1 抢占 na → na DISPLACED，记录 PENDING
        service.review(reviewReq("e1", "EMERGENCY", "EV-401", 1, 2));
        assertEquals("DISPLACED", routeStatus("na"));

        // e2 在桶 2 再抢占 na：na 已不是 APPROVED，状态不符 → 409 且整次回滚
        ApiException ex = assertThrows(ApiException.class, () -> service.review(
                reviewReq("e2", "EMERGENCY", "EV-402", 7, 7)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("STATE_CONFLICT", ex.code());

        // 回滚验证：na 在桶 2 的占用保留、无第二条快照、e2 未批准未占用、不占键
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM preemption", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM bucket_occupancy WHERE route_id = 'na'", Integer.class));
        assertEquals("PENDING", routeStatus("e2"));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
    }

    @Test
    @DisplayName("区域版本更新后：已批准未起飞航线按既有规则 STALE，抢占快照不改写")
    void snapshotFrozenAfterZoneUpdate() {
        createRoute("na");
        createRoute("em");
        createBucket(1, 2, 1);
        MutationResponse approved = service.review(reviewReq("na", "NORMAL", null, 1, 2));
        String naReviewId = dataOf(approved).reviewId();
        service.review(reviewReq("em", "EMERGENCY", "EV-501", 1, 2));
        List<PreemptionDto> before = service.listPreemptions(null, null);
        assertEquals(1, before.size());

        // 区域版本更新（新建禁飞区，不与任何航线相交）
        service.createZone(new ZoneCreateRequest("zz", -90, -90, -80, -80, "req-" + rid("zone")));

        // 既有版本失效规则：na 的当前结论 STALE，历史结论不变
        assertEquals("STALE", service.getCurrentReview("na").conclusion());
        assertEquals("CLEAR", service.getReview(naReviewId).conclusion());

        // 抢占快照逐字段不变
        List<PreemptionDto> after = service.listPreemptions(null, null);
        assertEquals(1, after.size());
        assertEquals(before.get(0), after.get(0));
    }

    // ============================ 起飞登记 ============================

    @Test
    @DisplayName("起飞登记：仅 APPROVED 可登记；重复登记 409；同键重放")
    void departStateRules() {
        createRoute("d1");
        // PENDING 不可登记
        ApiException pending = assertThrows(ApiException.class, () -> service.depart(
                new DepartRequest("d1", "req-" + rid("depart"))));
        assertEquals(HttpStatus.CONFLICT, pending.status());
        assertEquals("INVALID_ROUTE_STATE", pending.code());
        // 不存在 404
        ApiException missing = assertThrows(ApiException.class, () -> service.depart(
                new DepartRequest("ghost", "req-" + rid("depart"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        // 批准后登记成功
        createBucket(1, 2, 1);
        service.review(reviewReq("d1", "NORMAL", null, 1, 2));
        MutationResponse departed = service.depart(new DepartRequest("d1", "req-depart-fixed"));
        assertEquals("DEPARTED",
                objectMapper.convertValue(departed.data(), RouteStateResult.class).status());
        assertEquals("DEPARTED", routeStatus("d1"));
        assertEquals("DEPARTED", jdbc.queryForObject(
                "SELECT status FROM bucket_occupancy WHERE route_id = 'd1'", String.class));

        // 同键重放
        MutationResponse replay = service.depart(new DepartRequest("d1", "req-depart-fixed"));
        assertTrue(replay.replayed());
        // 已起飞再登记（新键）→ 409
        ApiException twice = assertThrows(ApiException.class, () -> service.depart(
                new DepartRequest("d1", "req-" + rid("depart"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());
    }

    // ============================ 幂等与规范化指纹 ============================

    @Test
    @DisplayName("紧急审查幂等：同键重放首次快照；毫秒级时间窗差异规范化后同键")
    void emergencyReviewIdempotentReplayWithNormalizedSegment() {
        createRoute("na");
        createRoute("em");
        createBucket(1, 2, 1);
        service.review(reviewReq("na", "NORMAL", null, 1, 2));

        String requestId = "req-em-fixed";
        ReviewRequest first = new ReviewRequest("em", 1, 0L, "EMERGENCY", "EV-601",
                1, 2, WIN_START, WIN_END, requestId);
        MutationResponse r1 = service.review(first);
        assertFalse(r1.replayed());

        // 同一逻辑请求：windowStart/windowEnd 毫秒级偏移（规范化到同一分钟），requestId 相同 → 重放
        ReviewRequest sameNormalized = new ReviewRequest("em", 1, 0L, "EMERGENCY", "EV-601",
                1, 2, WIN_START + 10_000L, WIN_END + 15_000L, requestId);
        MutationResponse r2 = service.review(sameNormalized);
        assertTrue(r2.replayed());
        assertEquals(dataOf(r1).reviewId(), dataOf(r2).reviewId());

        // 只有一次业务生效
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM preemption", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM bucket_occupancy", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));

        // 同键异参（不同事件号）→ 409
        ApiException mismatch = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("em", 1, 0L, "EMERGENCY", "EV-999",
                        1, 2, WIN_START, WIN_END, requestId)));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", mismatch.code());
    }

    // ============================ 并发 ============================

    @Test
    @DisplayName("并发紧急审查同一桶：恰好一个成功，另一个 422，最终状态一致")
    void concurrentEmergencyReviewsSameBucket() throws Exception {
        createRoute("na");
        createRoute("e1");
        createRoute("e2");
        createBucket(1, 2, 1);
        service.review(reviewReq("na", "NORMAL", null, 1, 2));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> f1 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return service.review(new ReviewRequest("e1", 1, 0L, "EMERGENCY", "EV-701",
                            1, 2, WIN_START, WIN_END, "req-conc-e1"));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Future<Object> f2 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return service.review(new ReviewRequest("e2", 1, 0L, "EMERGENCY", "EV-702",
                            1, 2, WIN_START, WIN_END, "req-conc-e2"));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Object r1 = f1.get(15, TimeUnit.SECONDS);
            Object r2 = f2.get(15, TimeUnit.SECONDS);

            int successes = 0;
            int unprocessable = 0;
            for (Object r : List.of(r1, r2)) {
                if (r instanceof MutationResponse) {
                    successes++;
                } else {
                    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ((ApiException) r).status());
                    unprocessable++;
                }
            }
            assertEquals(1, successes, "容量 1 只允许一条紧急航线批准");
            assertEquals(1, unprocessable);

            // 最终数据一致：桶占用 1、快照 1、na 被置换、落败方仍 PENDING
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM bucket_occupancy", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM preemption", Integer.class));
            assertEquals("DISPLACED", routeStatus("na"));
            String winner = jdbc.queryForObject(
                    "SELECT route_id FROM bucket_occupancy WHERE bucket_key = ?",
                    String.class, BUCKET_KEY);
            String loser = "e1".equals(winner) ? "e2" : "e1";
            assertEquals("APPROVED", routeStatus(winner));
            assertEquals("PENDING", routeStatus(loser));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("同键并发紧急审查：恰好一次业务生效，其余重放首次快照")
    void concurrentSameKeyEmergencyReview() throws Exception {
        createRoute("na");
        createRoute("em");
        createBucket(1, 2, 1);
        service.review(reviewReq("na", "NORMAL", null, 1, 2));

        String requestId = "req-em-concurrent";
        int n = 4;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.review(new ReviewRequest("em", 1, 0L, "EMERGENCY", "EV-801",
                            1, 2, WIN_START, WIN_END, requestId));
                }));
            }
            int firstCount = 0;
            int replayCount = 0;
            String reviewId = null;
            for (Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(15, TimeUnit.SECONDS);
                if (resp.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                }
                String currentId = dataOf(resp).reviewId();
                if (reviewId == null) {
                    reviewId = currentId;
                } else {
                    assertEquals(reviewId, currentId, "重放必须返回首次快照");
                }
            }
            assertEquals(1, firstCount);
            assertEquals(n - 1, replayCount);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM preemption", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM bucket_occupancy", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }
}
