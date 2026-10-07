package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePoolRequest;
import com.example.starter.exposure.web.PoolResponse;
import com.example.starter.exposure.web.PoolSelectionResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SelectFromPoolRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 缺额轮转选择池 H2（MODE=MySQL）集成测试：轮转主流程、取消/确认/到期对缺额的影响、
 * 429/409 失败分支、访客额度参与竞选、幂等与版本校验、跨日缺额保留。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PoolSelectionIntegrationTest {

    /** 可控时钟：固定起点，可按毫秒推进。 */
    static class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        void advanceMillis(long millis) {
            this.instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static final Instant BASE = Instant.parse("2026-09-22T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    /** 以可控时钟覆盖生产系统时钟。 */
    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock mutableClock() {
            return new MutableClock(BASE);
        }
    }

    @Autowired
    ExposureService service;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    Clock clock;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void cleanAndReset() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM pool_selection_deficit");
        jdbc.update("DELETE FROM pool_selection");
        jdbc.update("DELETE FROM pool_member");
        jdbc.update("DELETE FROM selection_pool");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void campaign(String campaignId, int totalCap, int perVisitorCap) {
        service.createCampaign(
                new CreateCampaignRequest("req-c-" + campaignId, campaignId, totalCap, perVisitorCap));
    }

    /** 以 "campaignId:weight" 可变参数组装建池请求，操作者固定 tester。 */
    private CreatePoolRequest poolReq(String requestId, String poolId, String... campaignWeight) {
        List<CreatePoolRequest.Member> members = new ArrayList<>();
        for (String cw : campaignWeight) {
            String[] parts = cw.split(":");
            members.add(new CreatePoolRequest.Member(parts[0], Integer.parseInt(parts[1])));
        }
        return new CreatePoolRequest(requestId, "tester", poolId, members);
    }

    private PoolSelectionResponse select(String requestId, String poolId, String visitorId) {
        return service.selectFromPool(poolId,
                new SelectFromPoolRequest(requestId, "tester", visitorId, null));
    }

    /** 当前已正式提交的各成员缺额（公告号字典序）。 */
    private Map<String, Long> committedDeficits(String poolId) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (PoolResponse.Member member : service.getPool(poolId).members()) {
            result.put(member.campaignId(), member.committedDeficit());
        }
        return result;
    }

    @Test
    @DisplayName("轮转主流程：首选举 A；取消后仍选 A；确认后下一轮选 B；到期释放额度且不推进缺额")
    void rotation_cancelKeepsPosition_confirmAdvances_expiryReleasesQuotaNotDeficit() {
        campaign("A", 10, 10);
        campaign("B", 10, 10);
        service.createPool(poolReq("req-p1", "P", "A:2", "B:1"));

        // 第一轮：缺额 (0,0) -> 临时 (2,1)，选 A，A 减权重和 3 -> 临时 (-1,1)
        PoolSelectionResponse r1 = select("req-s1", "P", "v1");
        assertEquals("A", r1.campaignId());
        assertEquals(Map.of("A", -1L, "B", 1L), r1.deficits());
        assertEquals(BASE.toEpochMilli(), r1.createdAtUtc());
        assertEquals(BASE.toEpochMilli() + 60_000L, r1.expiresAtUtc());
        assertEquals("RESERVED", r1.status().name());

        // 取消：丢弃临时缺额，已提交缺额不变，下一次重选同一位置
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        assertEquals(Map.of("A", 0L, "B", 0L), committedDeficits("P"));
        PoolSelectionResponse r2 = select("req-s2", "P", "v1");
        assertEquals("A", r2.campaignId());
        assertEquals(Map.of("A", -1L, "B", 1L), r2.deficits());

        // 确认：临时缺额正式提交；下一轮 临时 (1,2) 选 B
        service.confirm(r2.reservationId(), new ReservationActionRequest("req-k2"));
        assertEquals(Map.of("A", -1L, "B", 1L), committedDeficits("P"));
        PoolSelectionResponse r3 = select("req-s3", "P", "v1");
        assertEquals("B", r3.campaignId());
        assertEquals(Map.of("A", 1L, "B", -1L), r3.deficits());
        service.confirm(r3.reservationId(), new ReservationActionRequest("req-k3"));
        assertEquals(Map.of("A", 1L, "B", -1L), committedDeficits("P"));

        // 第三轮：临时 (3,0) 选 A；到 60 秒未确认 -> 释放额度且不推进缺额
        PoolSelectionResponse r4 = select("req-s4", "P", "v1");
        assertEquals("A", r4.campaignId());
        assertEquals(Map.of("A", 0L, "B", 0L), r4.deficits());
        mutableClock().advanceMillis(60_000L);

        PoolSelectionResponse r5 = select("req-s5", "P", "v1");
        assertEquals("A", r5.campaignId(), "到期丢弃临时缺额，重选同一位置");
        assertEquals(Map.of("A", 0L, "B", 0L), r5.deficits());
        assertEquals(Map.of("A", 1L, "B", -1L), committedDeficits("P"));
        assertEquals("EXPIRED", service.getReservation(r4.reservationId()).status().name());

        // 额度核对：A = r2(CONFIRMED) + r5(RESERVED) = 2；B = r3(CONFIRMED) = 1；r4 已过期释放
        QuotaResponse quotaA = service.queryQuota("A", "v1", DAY);
        assertEquals(2, quotaA.usedTotal());
        assertEquals(2, quotaA.usedVisitor());
        assertEquals(1, service.queryQuota("B", "v1", DAY).usedTotal());
    }

    @Test
    @DisplayName("全部成员不可选返回 429：临时缺额不保存、无选择记录；失败不占幂等键")
    void allIneligible_429_deficitsUntouched_failureKeyReusable() {
        campaign("A", 1, 1);
        campaign("B", 1, 1);
        service.createPool(poolReq("req-p1", "P", "A:1", "B:1"));
        ReservationResponse ra = service.apply(new ApplyExposureRequest("req-a1", "A", "v1"));
        service.apply(new ApplyExposureRequest("req-a2", "B", "v1"));

        assert429(() -> select("req-s1", "P", "v2"));
        assertEquals(Map.of("A", 0L, "B", 0L), committedDeficits("P"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pool_selection", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pool_selection_deficit", Integer.class));

        // 失败不占键：释放 A 后同键同参重试成功；同分取公告号小者 A
        service.cancel(ra.reservationId(), new ReservationActionRequest("req-x1"));
        PoolSelectionResponse r = select("req-s1", "P", "v2");
        assertEquals("A", r.campaignId());
        assertEquals(Map.of("A", -1L, "B", 1L), r.deficits());
    }

    @Test
    @DisplayName("RESERVED 期间池禁止另一轮转选择 409；确认后可继续；409 不占幂等键")
    void activeReservedSelection_409_untilConfirmed() {
        campaign("A", 10, 10);
        campaign("B", 10, 10);
        service.createPool(poolReq("req-p1", "P", "A:1", "B:1"));

        PoolSelectionResponse r1 = select("req-s1", "P", "v1");
        assertEquals("A", r1.campaignId());
        assertEquals(r1.reservationId(), service.getPool("P").activeReservationId());
        assert409(() -> select("req-s2", "P", "v1"));

        // 409 失败不占键：确认后同键可成功；已提交 (-1,1) -> 临时 (0,2) 选 B，B 减权重和 2
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        PoolSelectionResponse r2 = select("req-s2", "P", "v1");
        assertEquals("B", r2.campaignId());
        assertEquals(Map.of("A", 0L, "B", 0L), r2.deficits());
    }

    @Test
    @DisplayName("仅当天仍有额度的成员参与竞选：访客对 A 额度已满时选 B，全部成员仍按权重累计缺额")
    void visitorIneligibleMemberSkipped_deficitStillAccumulates() {
        campaign("A", 10, 1);
        campaign("B", 10, 10);
        service.createPool(poolReq("req-p1", "P", "A:5", "B:1"));
        // 普通曝光参与原额度核验：v1 对 A 的访客当日上限已满
        service.apply(new ApplyExposureRequest("req-a1", "A", "v1"));

        PoolSelectionResponse r = select("req-s1", "P", "v1");
        assertEquals("B", r.campaignId());
        // 全部成员按权重加缺额；选中者减所有成员权重之和 6
        assertEquals(Map.of("A", 5L, "B", -5L), r.deficits());
        service.confirm(r.reservationId(), new ReservationActionRequest("req-k1"));
        assertEquals(Map.of("A", 5L, "B", -5L), committedDeficits("P"));
    }

    @Test
    @DisplayName("幂等：同键同参重放首次完整响应不重新选择；改参 409；期望版本不匹配 409 且不占键")
    void idempotency_replayFrozenResponse_conflictOnDifferentParams_versionCheck() {
        campaign("A", 10, 10);
        campaign("B", 10, 10);
        service.createPool(poolReq("req-p1", "P", "A:2", "B:1"));

        PoolSelectionResponse first = select("req-s1", "P", "v1");
        PoolSelectionResponse replay = select("req-s1", "P", "v1");
        assertEquals(first.reservationId(), replay.reservationId());
        assertEquals(first.deficits(), replay.deficits());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation", Integer.class));

        // 同键异参：访客、操作者、期望版本任一不同均 409
        assert409(() -> select("req-s1", "P", "v2"));
        assert409(() -> service.selectFromPool("P",
                new SelectFromPoolRequest("req-s1", "other", "v1", null)));
        assert409(() -> service.selectFromPool("P",
                new SelectFromPoolRequest("req-s1", "tester", "v1", 1)));

        // 期望版本：不匹配 409 且失败不占键；新池匹配版本可成功
        service.createPool(poolReq("req-p2", "P2", "A:2", "B:1"));
        assert409(() -> service.selectFromPool("P2",
                new SelectFromPoolRequest("req-s9", "tester", "v1", 2)));
        PoolSelectionResponse ok = service.selectFromPool("P2",
                new SelectFromPoolRequest("req-s9", "tester", "v1", 1));
        assertEquals("A", ok.campaignId());
    }

    @Test
    @DisplayName("跨日不清池缺额，预占仍归申请日")
    void crossDay_poolDeficitPersist_reservationKeepsApplicationDay() {
        mutableClock().setInstant(Instant.parse("2026-09-22T23:59:30Z"));
        campaign("A", 10, 10);
        campaign("B", 10, 10);
        service.createPool(poolReq("req-p1", "P", "A:2", "B:1"));

        PoolSelectionResponse r1 = select("req-s1", "P", "v1");
        assertEquals("A", r1.campaignId());
        assertEquals(LocalDate.of(2026, 9, 22), r1.utcDate());
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        assertEquals(Map.of("A", -1L, "B", 1L), committedDeficits("P"));

        // 跨日后缺额不清零：临时 (1,2) 选 B，预占归新申请日
        mutableClock().setInstant(Instant.parse("2026-09-23T00:00:01Z"));
        PoolSelectionResponse r2 = select("req-s2", "P", "v1");
        assertEquals("B", r2.campaignId(), "跨日不清池缺额，按已提交缺额继续轮转");
        assertEquals(LocalDate.of(2026, 9, 23), r2.utcDate(), "预占仍归申请日");
        assertEquals(Map.of("A", -1L, "B", 1L), committedDeficits("P"));
    }

    @Test
    @DisplayName("建池校验：重放/异参/重复编号/成员缺失/成员重复/资源缺失")
    void createPool_validationAndIdempotency() {
        campaign("A", 10, 10);
        campaign("B", 10, 10);

        PoolResponse created = service.createPool(poolReq("req-p1", "P", "B:1", "A:2"));
        assertEquals(1, created.poolVersion());
        // 成员按公告号字典序返回，缺额初始 0
        assertEquals(List.of("A", "B"),
                created.members().stream().map(PoolResponse.Member::campaignId).toList());
        assertEquals(Map.of("A", 0L, "B", 0L), committedDeficits("P"));

        // 同键同参重放原响应；同键异参（成员顺序不同）409
        PoolResponse replay = service.createPool(poolReq("req-p1", "P", "B:1", "A:2"));
        assertEquals(created.createdAtUtc(), replay.createdAtUtc());
        assert409(() -> service.createPool(poolReq("req-p1", "P", "A:2", "B:1")));
        // 重复池编号 409；成员公告不存在 404；成员重复 400；缺失资源 404
        assert409(() -> service.createPool(poolReq("req-p2", "P", "A:1", "B:1")));
        assert404(() -> service.createPool(poolReq("req-p3", "P3", "A:1", "ZZ:1")));
        assert400(() -> service.createPool(poolReq("req-p4", "P4", "A:1", "A:2")));
        assert404(() -> service.getPool("nope"));
        assert404(() -> select("req-s1", "nope", "v1"));
    }

    @Test
    @DisplayName("HTTP 语义：建池/选择 201、活动选择 409、参数校验 400、未知池 404")
    void httpSemantics_poolEndpoints() throws Exception {
        campaign("A", 10, 10);
        campaign("B", 10, 10);

        mockMvc.perform(post("/api/exposure/pools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"operator\":\"op\",\"poolId\":\"P\","
                                + "\"members\":[{\"campaignId\":\"A\",\"weight\":2},"
                                + "{\"campaignId\":\"B\",\"weight\":1}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.poolVersion").value(1))
                .andExpect(jsonPath("$.members[0].campaignId").value("A"))
                .andExpect(jsonPath("$.members[0].committedDeficit").value(0));

        mockMvc.perform(post("/api/exposure/pools/P/selections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"operator\":\"op\",\"visitorId\":\"v1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campaignId").value("A"))
                .andExpect(jsonPath("$.deficits.A").value(-1))
                .andExpect(jsonPath("$.deficits.B").value(1));

        mockMvc.perform(get("/api/exposure/pools/P"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeReservationId").isNotEmpty());

        // RESERVED 期间另一选择 409
        mockMvc.perform(post("/api/exposure/pools/P/selections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"operator\":\"op\",\"visitorId\":\"v2\"}"))
                .andExpect(status().isConflict());

        // 缺操作者 400；权重越界 400；成员数不足 400；未知池 404
        mockMvc.perform(post("/api/exposure/pools/P/selections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"visitorId\":\"v1\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/exposure/pools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"operator\":\"op\",\"poolId\":\"P5\","
                                + "\"members\":[{\"campaignId\":\"A\",\"weight\":0},"
                                + "{\"campaignId\":\"B\",\"weight\":1}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/exposure/pools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h6\",\"operator\":\"op\",\"poolId\":\"P6\","
                                + "\"members\":[{\"campaignId\":\"A\",\"weight\":1}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/exposure/pools/nope"))
                .andExpect(status().isNotFound());
    }

    private void assert400(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(400, ex.getStatus().value());
    }

    private void assert404(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(404, ex.getStatus().value());
    }

    private void assert409(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert429(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(429, ex.getStatus().value());
    }
}
