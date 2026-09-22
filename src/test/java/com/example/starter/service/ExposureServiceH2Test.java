package com.example.starter.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.starter.domain.ReservationStatus;
import com.example.starter.testsupport.MutableClock;
import com.example.starter.testsupport.TestClockConfiguration;
import com.example.starter.web.dto.ApplyExposureRequest;
import com.example.starter.web.dto.CreateCampaignRequest;
import com.example.starter.web.dto.QuotaResponse;
import com.example.starter.web.dto.QuotaView;
import com.example.starter.web.dto.ReservationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基于隔离 H2（MODE=MySQL）的业务与数据库边界集成测试：
 * 覆盖主流程、429/409/404 失败分支、过期惰性结算、UTC 日固定、
 * 幂等重放/异参/失败不占键，以及真实并发下的单次终态与不超卖。
 */
@SpringBootTest
@ContextConfiguration(classes = TestClockConfiguration.class)
class ExposureServiceH2Test {

    @Autowired
    private ExposureService service;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM quota_account");
        jdbc.update("DELETE FROM campaign");
        clock.setInstant(TestClockConfiguration.START);
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void createApplyConfirmKeepsQuotaHeld() {
        createCampaign("c-main", 10, 5);

        ServiceResult<Object> applied = service.apply(req("r1", "c-main", "visitor-A"));
        assertThat(applied.status()).isEqualTo(201);
        ReservationResponse reservation = body(applied);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.getUtcDate()).isEqualTo("2026-09-22");
        assertThat(reservation.getExpiresAt() - reservation.getCreatedAt()).isEqualTo(60_000L);

        assertHeld("c-main", "visitor-A", 1, 1);

        ServiceResult<Object> confirmed = service.confirm(reservation.getReservationId(), "r2");
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(body(confirmed).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

        // 确认持续占用当天额度。
        assertHeld("c-main", "visitor-A", 1, 1);
    }

    @Test
    void cancelReleasesQuotaForBothScopes() {
        createCampaign("c-cancel", 10, 5);
        ReservationResponse reservation = body(service.apply(req("r1", "c-cancel", "visitor-A")));
        assertHeld("c-cancel", "visitor-A", 1, 1);

        ServiceResult<Object> cancelled = service.cancel(reservation.getReservationId(), "r2");
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(body(cancelled).getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertHeld("c-cancel", "visitor-A", 0, 0);

        // 再次取消（不同 requestId）返回原状态，额度不二次释放。
        ServiceResult<Object> again = service.cancel(reservation.getReservationId(), "r3");
        assertThat(body(again).getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertHeld("c-cancel", "visitor-A", 0, 0);

        // 已取消不可确认。
        assertThatThrownBy(() -> service.confirm(reservation.getReservationId(), "r4"))
                .isInstanceOf(ConflictException.class);
    }

    // ------------------------------------------------------------------
    // 429 额度
    // ------------------------------------------------------------------

    @Test
    void campaignTotalCapReturns429AndHoldsNothing() {
        createCampaign("c-total", 2, 10);

        service.apply(req("r1", "c-total", "v1"));
        service.apply(req("r2", "c-total", "v2"));
        assertHeld("c-total", "v1", 2, 1);
        assertHeld("c-total", "v2", 2, 1);

        assertThatThrownBy(() -> service.apply(req("r3", "c-total", "v3")))
                .isInstanceOf(RateLimitException.class);

        // 第三个申请两级额度均不得增加：公告仍 2，新访客无账目（held=0）。
        assertThat(campaignHeld("c-total")).isEqualTo(2);
        assertHeld("c-total", "v3", 2, 0);
    }

    @Test
    void visitorCapReturns429WithoutIncreasingCampaignQuota() {
        createCampaign("c-visitor", 10, 1);

        service.apply(req("r1", "c-visitor", "v1"));
        assertThatThrownBy(() -> service.apply(req("r2", "c-visitor", "v1")))
                .isInstanceOf(RateLimitException.class);

        // 访客已满 429：公告额度同样不得增加。
        assertHeld("c-visitor", "v1", 1, 1);
    }

    @Test
    void expiredReservationIsSettledAndFreesCapacity() {
        createCampaign("c-exp", 1, 1);
        ReservationResponse first = body(service.apply(req("r1", "c-exp", "v1")));
        assertHeld("c-exp", "v1", 1, 1);

        clock.advance(Duration.ofSeconds(60));

        // 到期时刻（now == expiresAt）即过期；结算后新申请成功。
        ReservationResponse second = body(service.apply(req("r2", "c-exp", "v1")));
        assertThat(second.getReservationId()).isNotEqualTo(first.getReservationId());
        assertHeld("c-exp", "v1", 1, 1);
        assertThat(service.getReservation(first.getReservationId()).getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
    }

    // ------------------------------------------------------------------
    // 状态机 409
    // ------------------------------------------------------------------

    @Test
    void confirmAtExpiryMomentIs409AndReleasesQuota() {
        createCampaign("c-expiry", 1, 1);
        ReservationResponse reservation = body(service.apply(req("r1", "c-expiry", "v1")));

        clock.advance(Duration.ofSeconds(60));

        assertThatThrownBy(() -> service.confirm(reservation.getReservationId(), "r2"))
                .isInstanceOf(ConflictException.class);
        assertThat(service.getReservation(reservation.getReservationId()).getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertHeld("c-expiry", "v1", 0, 0);

        // 过期后取消同样 409。
        assertThatThrownBy(() -> service.cancel(reservation.getReservationId(), "r3"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void cancelConfirmedReservationIs409() {
        createCampaign("c-cc", 10, 5);
        ReservationResponse reservation = body(service.apply(req("r1", "c-cc", "v1")));
        service.confirm(reservation.getReservationId(), "r2");

        assertThatThrownBy(() -> service.cancel(reservation.getReservationId(), "r3"))
                .isInstanceOf(ConflictException.class);
        assertHeld("c-cc", "v1", 1, 1);
    }

    @Test
    void repeatedConfirmReturnsOriginalState() {
        createCampaign("c-rep", 10, 5);
        ReservationResponse reservation = body(service.apply(req("r1", "c-rep", "v1")));

        assertThat(body(service.confirm(reservation.getReservationId(), "r2")).getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(body(service.confirm(reservation.getReservationId(), "r3")).getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertHeld("c-rep", "v1", 1, 1);
    }

    @Test
    void unknownCampaignAndReservationAre404() {
        assertThatThrownBy(() -> service.apply(req("r1", "missing", "v1")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getReservation("rv-missing"))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------
    // UTC 日固定
    // ------------------------------------------------------------------

    @Test
    void quotaDatePinnedAtApplyAndConfirmAcrossUtcMidnightDoesNotMoveCounters() {
        // TTL 固定 60 秒：23:59:30 申请，40 秒后（次日 00:00:10）确认，跨 UTC 日但未到期。
        clock.setInstant(Instant.parse("2026-09-22T23:59:30Z"));
        createCampaign("c-midnight", 10, 5);
        ReservationResponse reservation = body(service.apply(req("r1", "c-midnight", "v1")));
        assertThat(reservation.getUtcDate()).isEqualTo("2026-09-22");

        clock.advance(Duration.ofSeconds(40));
        assertThat(body(service.confirm(reservation.getReservationId(), "r2")).getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);

        // 计数留在申请日，不迁移到新 UTC 日。
        assertHeld("c-midnight", "v1", "2026-09-22", 1, 1);
        QuotaResponse nextDay = service.getQuota("c-midnight", "v1", "2026-09-23");
        assertThat(nextDay.getCampaignQuota().getHeld()).isZero();
        assertThat(nextDay.getVisitorQuotas()).hasSize(1);
        assertThat(nextDay.getVisitorQuotas().get(0).getHeld()).isZero();

        // 历史日账目仍可查。
        QuotaResponse oldDay = service.getQuota("c-midnight", "v1", "2026-09-22");
        assertThat(oldDay.getCampaignQuota().getHeld()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdAndParamsReplaysOriginalSuccess() {
        createCampaign("c-idem", 10, 5);
        ApplyExposureRequest first = req("idem-1", "c-idem", "v1");
        ReservationResponse r1 = body(service.apply(first));
        ReservationResponse r2 = body(service.apply(req("idem-1", "c-idem", "v1")));

        assertThat(r2.getReservationId()).isEqualTo(r1.getReservationId());
        assertHeld("c-idem", "v1", 1, 1);
    }

    @Test
    void sameRequestIdDifferentParamsIs409() {
        createCampaign("c-idem2", 10, 5);
        service.apply(req("idem-2", "c-idem2", "v1"));

        assertThatThrownBy(() -> service.apply(req("idem-2", "c-idem2", "v2")))
                .isInstanceOf(ConflictException.class);
        assertHeld("c-idem2", "v1", 1, 1);
    }

    @Test
    void failedRequestDoesNotOccupyKey() {
        createCampaign("c-idem3", 1, 1);
        service.apply(req("idem-first", "c-idem3", "v1"));

        // 首次 429 失败不占键（失败使用独立 requestId）；过期释放后同一 requestId 可成功使用。
        assertThatThrownBy(() -> service.apply(req("idem-3", "c-idem3", "v1")))
                .isInstanceOf(RateLimitException.class);
        clock.advance(Duration.ofSeconds(60));
        ReservationResponse retry = body(service.apply(req("idem-3", "c-idem3", "v1")));
        assertThat(retry.getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void duplicateCampaignCreationIs409() {
        createCampaign("c-dup", 10, 5);
        assertThatThrownBy(() -> createCampaign("c-dup", 20, 8))
                .isInstanceOf(ConflictException.class);
        // 原额度保持不变。
        QuotaView view = service.getQuota("c-dup", null, "2026-09-22").getCampaignQuota();
        assertThat(view.getCap()).isEqualTo(10);
    }

    // ------------------------------------------------------------------
    // 并发
    // ------------------------------------------------------------------

    @Test
    void concurrentAppliesNeverOversellCampaignCap() throws Exception {
        int cap = 5;
        int threads = 20;
        createCampaign("c-conc", cap, 1000);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    service.apply(req("conc-" + seq.incrementAndGet(), "c-conc",
                            "visitor-" + Thread.currentThread().getId() + "-" + UUID.randomUUID()));
                    return true;
                } catch (RateLimitException e) {
                    return false;
                }
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int success = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(10, TimeUnit.SECONDS)) {
                success++;
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(cap);
        assertThat(campaignHeld("c-conc")).isEqualTo(cap);
        Integer held = jdbc.queryForObject(
                "SELECT MIN(held_count) FROM quota_account WHERE scope = 'CAMPAIGN' AND quota_key = 'c-conc'",
                Integer.class);
        assertThat(held).isEqualTo(cap);
    }

    @Test
    void concurrentConfirmAndCancelYieldsSingleTerminalAndSingleRelease() throws Exception {
        createCampaign("c-terminal", 10, 5);
        ReservationResponse reservation = body(service.apply(req("t1", "c-terminal", "v1")));
        assertHeld("c-terminal", "v1", 1, 1);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();
        for (int i = 0; i < threads / 2; i++) {
            String requestId = "confirm-" + i;
            outcomes.add(pool.submit(() -> runTerminal(start,
                    () -> service.confirm(reservation.getReservationId(), requestId))));
        }
        for (int i = 0; i < threads / 2; i++) {
            String requestId = "cancel-" + i;
            outcomes.add(pool.submit(() -> runTerminal(start,
                    () -> service.cancel(reservation.getReservationId(), requestId))));
        }
        start.countDown();

        int ok = 0;
        int conflict = 0;
        for (Future<String> future : outcomes) {
            String outcome = future.get(10, TimeUnit.SECONDS);
            if ("OK".equals(outcome)) {
                ok++;
            } else if ("CONFLICT".equals(outcome)) {
                conflict++;
            }
        }
        pool.shutdown();

        // 同类终态操作重复执行返回原状态（OK），异类操作 409；
        // 无论确认还是取消最终胜出，均为 5 个 OK、5 个 CONFLICT，且底层只发生一次终态迁移。
        assertThat(ok).isEqualTo(threads / 2);
        assertThat(conflict).isEqualTo(threads / 2);
        assertThat(ok + conflict).isEqualTo(threads);

        ReservationStatus finalStatus = service.getReservation(reservation.getReservationId()).getStatus();
        assertThat(finalStatus).isIn(ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED);
        int expectedHeld = finalStatus == ReservationStatus.CONFIRMED ? 1 : 0;
        assertHeld("c-terminal", "v1", 1, expectedHeld);
    }

    @Test
    void concurrentSameRequestIdCreatesExactlyOneReservation() throws Exception {
        createCampaign("c-idem-conc", 100, 100);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> reservationIds = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            reservationIds.add(pool.submit(() -> {
                start.await();
                ReservationResponse r = body(service.apply(req("same-idem-key", "c-idem-conc", "v9")));
                return r.getReservationId();
            }));
        }
        start.countDown();
        for (Future<String> future : reservationIds) {
            assertThat(future.get(10, TimeUnit.SECONDS))
                    .isEqualTo(reservationIds.get(0).get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();
        // 所有线程拿到同一预占，只占用一次额度。
        assertHeld("c-idem-conc", "v9", 1, 1);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private String runTerminal(CountDownLatch start, Callable<ServiceResult<Object>> action) {
        try {
            start.await();
            action.call();
            return "OK";
        } catch (ConflictException e) {
            return "CONFLICT";
        } catch (Exception e) {
            return "ERROR:" + e.getClass().getSimpleName();
        }
    }

    private void createCampaign(String campaignId, int totalCap, int visitorCap) {
        CreateCampaignRequest request = new CreateCampaignRequest();
        request.setRequestId("create-" + campaignId + "-" + UUID.randomUUID());
        request.setCampaignId(campaignId);
        request.setDailyTotalCap(totalCap);
        request.setVisitorCap(visitorCap);
        ServiceResult<Object> result = service.createCampaign(request);
        assertThat(result.status()).isEqualTo(201);
    }

    private static ApplyExposureRequest req(String requestId, String campaignId, String visitorId) {
        ApplyExposureRequest r = new ApplyExposureRequest();
        r.setRequestId(requestId);
        r.setCampaignId(campaignId);
        r.setVisitorId(visitorId);
        return r;
    }

    private ReservationResponse body(ServiceResult<Object> result) {
        Object b = result.body();
        if (b instanceof ExposureService.RawJson raw) {
            try {
                return objectMapper.readValue(raw.json(), ReservationResponse.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        assertThat(b).isInstanceOf(ReservationResponse.class);
        return (ReservationResponse) b;
    }

    private int heldCount(String scope, String quotaKey, String utcDate) {
        List<Integer> values = jdbc.queryForList(
                "SELECT held_count FROM quota_account WHERE scope = ? AND quota_key = ? AND utc_date = ?",
                Integer.class, scope, quotaKey, utcDate);
        return values.isEmpty() ? 0 : values.get(0);
    }

    private int campaignHeld(String campaignId) {
        return heldCount("CAMPAIGN", campaignId, "2026-09-22");
    }

    private void assertHeld(String campaignId, String visitorId, int expectedCampaign, int expectedVisitor) {
        assertHeld(campaignId, visitorId, "2026-09-22", expectedCampaign, expectedVisitor);
    }

    private void assertHeld(String campaignId, String visitorId, String utcDate,
                            int expectedCampaign, int expectedVisitor) {
        assertThat(heldCount("CAMPAIGN", campaignId, utcDate)).isEqualTo(expectedCampaign);
        assertThat(heldCount("VISITOR", campaignId + ":" + visitorId, utcDate)).isEqualTo(expectedVisitor);
    }
}
