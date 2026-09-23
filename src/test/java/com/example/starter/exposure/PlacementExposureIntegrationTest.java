package com.example.starter.exposure;

import com.example.starter.exposure.domain.Placement;
import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.repo.PlacementRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyPlacementExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePlacementRequest;
import com.example.starter.exposure.web.PlacementResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多展示位共享频控 H2（MODE=MySQL）集成测试：
 * DEFAULT 兼容、展示位配置、三层额度同事务占用/释放、跨位共享访客上限、
 * 到期释放、配置版本竞争与写操作幂等、三层额度与预占明细查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlacementExposureIntegrationTest {

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

    static final Instant BASE = Instant.parse("2026-09-23T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 23);

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
    PlacementRepository placementRepository;
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
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_placement_ledger");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM placement");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createCampaign(String reqId, String id, int total, int perVisitor) {
        service.createCampaign(new CreateCampaignRequest(reqId, id, total, perVisitor));
    }

    private CreatePlacementRequest placementReq(String reqId, String code, int cap, int expectedVersion) {
        return new CreatePlacementRequest(reqId, code, cap, expectedVersion);
    }

    @Test
    @DisplayName("创建公告自动生成 DEFAULT 展示位：日额度=公告总额度，configVersion=1")
    void createCampaign_autoCreatesDefaultPlacement() {
        var campaign = service.createCampaign(new CreateCampaignRequest("req-c1", "c1", 100, 5));
        assertEquals(1, campaign.configVersion());

        var defaultPlacement = placementRepository.findById("c1", Placement.DEFAULT_CODE).orElseThrow();
        assertEquals(100, defaultPlacement.dailyCap());
        assertEquals(1, defaultPlacement.configVersion());
        assertEquals(BASE.toEpochMilli(), defaultPlacement.createdAtUtc());
        assertEquals(1, placementRepository.findByCampaign("c1").size());
    }

    @Test
    @DisplayName("新增展示位成功：版本递增；DEFAULT 保留字/重复 code/超额/越界/版本不符均失败")
    void createPlacement_successAndFailureBranches() {
        createCampaign("req-c1", "c1", 100, 5);

        PlacementResponse p1 = service.createPlacement("c1",
                placementReq("req-p1", "P1", 30, 1));
        assertEquals("P1", p1.placementCode());
        assertEquals(30, p1.dailyCap());
        assertEquals(2, p1.configVersion());

        PlacementResponse p2 = service.createPlacement("c1",
                placementReq("req-p2", "P2", 100, 2));
        assertEquals(3, p2.configVersion());

        // DEFAULT 为保留展示位
        assert409(() -> service.createPlacement("c1",
                placementReq("req-pd", Placement.DEFAULT_CODE, 10, 3)));
        // 重复 code
        assert409(() -> service.createPlacement("c1",
                placementReq("req-pdup", "P1", 10, 3)));
        // 日额度超过公告总额度
        assert409(() -> service.createPlacement("c1",
                placementReq("req-pbig", "P3", 101, 3)));
        // expectedConfigVersion 不匹配
        assert409(() -> service.createPlacement("c1",
                placementReq("req-pv", "P4", 10, 99)));
        // 失败不产生展示位、不递增版本
        assertEquals(3, placementRepository.findByCampaign("c1").size());
        assertEquals(3, service.queryQuota("c1", null, null, DAY).configVersion());
    }

    @Test
    @DisplayName("展示位数量上限 20（含 DEFAULT）：第 21 个返回 409")
    void createPlacement_limit20IncludingDefault() {
        createCampaign("req-c1", "c1", 100_000, 100_000);
        // 已含 DEFAULT，再建 19 个（共 20）
        for (int i = 1; i <= 19; i++) {
            String code = "P" + i;
            PlacementResponse created = service.createPlacement("c1",
                    placementReq("req-p" + i, code, 1, i));
            assertEquals(i + 1, created.configVersion());
        }
        assertEquals(20, placementRepository.findByCampaign("c1").size());
        // 第 21 个展示位被拒
        assert409(() -> service.createPlacement("c1",
                placementReq("req-p21", "P21", 1, 20)));
        assertEquals(20, placementRepository.findByCampaign("c1").size());
    }

    @Test
    @DisplayName("展示位创建幂等：同键同参重放原结果；异参 409；失败不占键")
    void createPlacement_idempotent() {
        createCampaign("req-c1", "c1", 100, 5);

        PlacementResponse first = service.createPlacement("c1",
                placementReq("key-p1", "P1", 30, 1));
        // 同键同参重放（即使服务端版本已前进），返回原结果，不重复创建/递增
        PlacementResponse replay = service.createPlacement("c1",
                placementReq("key-p1", "P1", 30, 1));
        assertEquals(first.configVersion(), replay.configVersion());
        assertEquals(2, placementRepository.findByCampaign("c1").size());

        // 同键异参（不同额度）→ 409
        assert409(() -> service.createPlacement("c1",
                placementReq("key-p1", "P1", 40, 1)));

        // 失败的创建（超过公告总额度）不占键：改为合法参数后同键成功
        assert409(() -> service.createPlacement("c1",
                placementReq("key-fail", "P9", 101, 2)));
        PlacementResponse retried = service.createPlacement("c1",
                placementReq("key-fail", "P9", 10, 2));
        assertEquals(3, retried.configVersion());
    }

    @Test
    @DisplayName("旧申请接口等价于 DEFAULT：预占携带 DEFAULT，三层账目中展示位层记在 DEFAULT")
    void legacyApply_equalsDefault() {
        createCampaign("req-c1", "c1", 10, 2);

        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals(Placement.DEFAULT_CODE, r.placementCode());
        assertEquals(DAY, r.utcDate());

        QuotaResponse q = service.queryQuota("c1", "v1", Placement.DEFAULT_CODE, DAY);
        assertEquals(1, q.usedTotal());
        assertEquals(1, q.usedVisitor());
        assertEquals(1, q.usedPlacement());
        assertEquals(10, q.placementDailyCap());
        assertEquals(9, q.remainingPlacement());
    }

    @Test
    @DisplayName("按展示位申请：同一事务占用公告总额、访客跨位共享上限、展示位额度三层")
    void applyPlacement_occupiesAllThreeTiers() {
        createCampaign("req-c1", "c1", 10, 5);
        service.createPlacement("c1", placementReq("req-p1", "P1", 3, 1));
        service.createPlacement("c1", placementReq("req-p2", "P2", 2, 2));

        ReservationResponse r1 = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a1", "c1", "P1", "v1"));
        assertEquals("P1", r1.placementCode());
        ReservationResponse r2 = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a2", "c1", "P2", "v1"));
        assertEquals("P2", r2.placementCode());

        // 访客跨展示位共享：两次都计入同一访客账
        QuotaResponse v1 = service.queryQuota("c1", "v1", null, DAY);
        assertEquals(2, v1.usedVisitor());
        assertEquals(3, v1.remainingVisitor());
        // 公告总账
        assertEquals(2, v1.usedTotal());
        // 展示位各自记账
        assertEquals(1, service.queryQuota("c1", null, "P1", DAY).usedPlacement());
        assertEquals(1, service.queryQuota("c1", null, "P2", DAY).usedPlacement());
    }

    @Test
    @DisplayName("任一额度已满返回 429 且三层均不增加：展示位满、访客跨位共享满、公告总满")
    void applyPlacement_429DoesNotIncreaseAnyTier() {
        // 公告总额 3，访客共享上限 2，P1 额度 1，P2 额度 3
        createCampaign("req-c1", "c1", 3, 2);
        service.createPlacement("c1", placementReq("req-p1", "P1", 1, 1));
        service.createPlacement("c1", placementReq("req-p2", "P2", 3, 2));

        // P1 占满（展示位层）
        service.applyPlacement(new ApplyPlacementExposureRequest("req-a1", "c1", "P1", "v1"));
        assert429(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a2", "c1", "P1", "v2")));
        // 被拒后三层账目不增加：v2 访客账为 0，总额仍为 1，P1 仍为 1
        assertEquals(1, service.queryQuota("c1", null, null, DAY).usedTotal());
        assertEquals(1, service.queryQuota("c1", null, "P1", DAY).usedPlacement());
        assertEquals(0, service.queryQuota("c1", "v2", null, DAY).usedVisitor());

        // 访客 v1 跨 P1/P2 达到共享上限 2
        service.applyPlacement(new ApplyPlacementExposureRequest("req-a3", "c1", "P2", "v1"));
        assert429(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a4", "c1", "P2", "v1")));
        assertEquals(2, service.queryQuota("c1", "v1", null, DAY).usedVisitor());
        assertEquals(1, service.queryQuota("c1", null, "P2", DAY).usedPlacement());

        // 公告总额 3：v3 在 P2 成功占用最后一个总额
        service.applyPlacement(new ApplyPlacementExposureRequest("req-a5", "c1", "P2", "v3"));
        assert429(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a6", "c1", "P2", "v4")));
        assertEquals(3, service.queryQuota("c1", null, null, DAY).usedTotal());
        assertEquals(2, service.queryQuota("c1", null, "P2", DAY).usedPlacement());
    }

    @Test
    @DisplayName("申请不存在的展示位返回 404 且不占任何账目")
    void applyPlacement_unknownPlacement404() {
        createCampaign("req-c1", "c1", 10, 5);
        assert404(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a1", "c1", "NOPE", "v1")));
        assertEquals(0, service.queryQuota("c1", null, null, DAY).usedTotal());
    }

    @Test
    @DisplayName("取消/到期释放三层额度；确认持续占用；三层账目均不变负")
    void cancelAndExpire_releaseAllThreeTiers() {
        createCampaign("req-c1", "c1", 3, 3);
        service.createPlacement("c1", placementReq("req-p1", "P1", 1, 1));
        service.createPlacement("c1", placementReq("req-p2", "P2", 1, 2));

        ReservationResponse p1 = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a1", "c1", "P1", "v1"));
        ReservationResponse p2 = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a2", "c1", "P2", "v1"));

        // 取消 P1：三层同时释放
        service.cancel(p1.reservationId(), new ReservationActionRequest("req-x1"));
        assertEquals(0, service.queryQuota("c1", null, "P1", DAY).usedPlacement());
        assertEquals(1, service.queryQuota("c1", null, "P2", DAY).usedPlacement());
        assertEquals(1, service.queryQuota("c1", "v1", null, DAY).usedVisitor());
        assertEquals(1, service.queryQuota("c1", null, null, DAY).usedTotal());
        // 取消释放后 P1 可再次申请
        ReservationResponse again = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a3", "c1", "P1", "v2"));
        assertEquals("RESERVED", again.status().name());

        // P2 到期：三层同时释放。重新申请的 P1（v2）创建于同一时刻，此刻也恰好到期，
        // 结算公告过期单时一并释放，因此三层账目均归零
        mutableClock().advanceMillis(60_000L);
        assert409(() -> service.confirm(p2.reservationId(), new ReservationActionRequest("req-e1")));
        assertEquals(0, service.queryQuota("c1", null, "P2", DAY).usedPlacement());
        assertEquals(0, service.queryQuota("c1", null, "P1", DAY).usedPlacement());
        assertEquals(0, service.queryQuota("c1", null, null, DAY).usedTotal());
        assertEquals(0, service.queryQuota("c1", "v1", null, DAY).usedVisitor());
        assertEquals(0, service.queryQuota("c1", "v2", null, DAY).usedVisitor());

        // 再次申请 P2 成功（到期释放了 P2 展示位额度）
        ReservationResponse afterExpiry = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a4", "c1", "P2", "v3"));
        assertEquals("RESERVED", afterExpiry.status().name());
        // 确认持续占用三层
        ReservationResponse confirmed = service.confirm(afterExpiry.reservationId(),
                new ReservationActionRequest("req-k1"));
        assertEquals("CONFIRMED", confirmed.status().name());
        assertEquals(1, service.queryQuota("c1", null, "P2", DAY).usedPlacement());
        assertEquals(1, service.queryQuota("c1", "v3", null, DAY).usedVisitor());
        assertEquals(1, service.queryQuota("c1", null, null, DAY).usedTotal());
    }

    @Test
    @DisplayName("预占固定申请日与展示位：跨日确认不迁移，历史日三层账目可查")
    void reservationPinsDayAndPlacement_acrossUtcDay() {
        mutableClock().setInstant(Instant.parse("2026-09-23T23:59:30Z"));
        createCampaign("req-c1", "c1", 10, 5);
        service.createPlacement("c1", placementReq("req-p1", "P1", 4, 1));

        ReservationResponse r = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a1", "c1", "P1", "v1"));
        assertEquals(LocalDate.of(2026, 9, 23), r.utcDate());

        mutableClock().setInstant(Instant.parse("2026-09-24T00:00:01Z"));
        service.confirm(r.reservationId(), new ReservationActionRequest("req-k1"));

        QuotaResponse oldDay = service.queryQuota("c1", "v1", "P1", LocalDate.of(2026, 9, 23));
        assertEquals(1, oldDay.usedTotal());
        assertEquals(1, oldDay.usedVisitor());
        assertEquals(1, oldDay.usedPlacement());
        QuotaResponse newDay = service.queryQuota("c1", "v1", "P1", LocalDate.of(2026, 9, 24));
        assertEquals(0, newDay.usedTotal());
        assertEquals(0, newDay.usedVisitor());
        assertEquals(0, newDay.usedPlacement());
    }

    @Test
    @DisplayName("已创建预占不受后来展示位增加影响；configVersion 仅随新增展示位递增")
    void laterPlacementDoesNotAffectExistingReservations() {
        createCampaign("req-c1", "c1", 10, 5);
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        service.createPlacement("c1", placementReq("req-p1", "P1", 2, 1));
        // 原预占仍为 DEFAULT、RESERVED，额度归属不变
        ReservationResponse detail = service.getReservation(r.reservationId());
        assertEquals(Placement.DEFAULT_CODE, detail.placementCode());
        assertEquals("RESERVED", detail.status().name());
        assertEquals(1, service.queryQuota("c1", null, Placement.DEFAULT_CODE, DAY).usedPlacement());
        assertEquals(0, service.queryQuota("c1", null, "P1", DAY).usedPlacement());
        assertEquals(2, service.queryQuota("c1", null, null, DAY).configVersion());
    }

    @Test
    @DisplayName("按展示位申请幂等：同键同参重放原预占；异参 409；429 失败不占键")
    void applyPlacement_idempotent() {
        createCampaign("req-c1", "c1", 2, 5);
        service.createPlacement("c1", placementReq("req-p1", "P1", 1, 1));

        ReservationResponse first = service.applyPlacement(
                new ApplyPlacementExposureRequest("key-1", "c1", "P1", "v1"));
        ReservationResponse replay = service.applyPlacement(
                new ApplyPlacementExposureRequest("key-1", "c1", "P1", "v1"));
        assertEquals(first.reservationId(), replay.reservationId());
        assertEquals(1, service.queryQuota("c1", null, null, DAY).usedTotal());

        // 同键异参（不同展示位）→ 409
        assert409(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("key-1", "c1", Placement.DEFAULT_CODE, "v1")));
        // 同键用于旧申请接口（指纹不同）→ 409
        assert409(() -> service.apply(new ApplyExposureRequest("key-1", "c1", "v1")));

        // P1 已满导致失败，不占键：取消释放后同键同参成功
        assert429(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("key-fail", "c1", "P1", "v2")));
        service.cancel(first.reservationId(), new ReservationActionRequest("req-x1"));
        ReservationResponse retried = service.applyPlacement(
                new ApplyPlacementExposureRequest("key-fail", "c1", "P1", "v2"));
        assertEquals("RESERVED", retried.status().name());
        assertEquals(1, service.queryQuota("c1", null, "P1", DAY).usedPlacement());
    }

    @Test
    @DisplayName("额度查询返回三层额度与预占展示位明细；旧查询（无 placementCode）保持兼容")
    void quotaQuery_threeTiersAndReservationDetails_legacyCompatible() {
        createCampaign("req-c1", "c1", 10, 5);
        service.createPlacement("c1", placementReq("req-p1", "P1", 3, 1));
        ReservationResponse r1 = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a1", "c1", "P1", "v1"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v1"));
        service.confirm(r2.reservationId(), new ReservationActionRequest("req-k1"));

        // 旧风格查询：无展示位维度，展示位字段为 null，明细返回当日全部活跃预占
        QuotaResponse legacy = service.queryQuota("c1", "v1", null, DAY);
        assertEquals(10, legacy.dailyTotalCap());
        assertEquals(2, legacy.usedTotal());
        assertEquals(5, legacy.perVisitorDailyCap());
        assertEquals(2, legacy.usedVisitor());
        assertNull(legacy.placementCode());
        assertNull(legacy.usedPlacement());
        assertEquals(2, legacy.reservations().size());
        assertTrue(legacy.reservations().stream()
                .anyMatch(d -> d.placementCode().equals("P1")
                        && d.reservationId().equals(r1.reservationId())
                        && d.status().name().equals("RESERVED")));
        assertTrue(legacy.reservations().stream()
                .anyMatch(d -> d.placementCode().equals(Placement.DEFAULT_CODE)
                        && d.status().name().equals("CONFIRMED")));

        // 展示位维度查询：仅该展示位明细
        QuotaResponse p1 = service.queryQuota("c1", "v1", "P1", DAY);
        assertEquals(3, p1.placementDailyCap());
        assertEquals(1, p1.usedPlacement());
        assertEquals(2, p1.remainingPlacement());
        assertEquals(1, p1.reservations().size());
        assertEquals("P1", p1.reservations().get(0).placementCode());

        // 查询不存在的展示位 404
        assert404(() -> service.queryQuota("c1", null, "MISSING", DAY));
    }

    @Test
    @DisplayName("HTTP 语义：新增展示位 201、版本不符 409、按展示位申请 201/429、三层额度 JSON")
    void httpSemantics_placementEndpoints() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"ch\",\"dailyTotalCap\":2,"
                                + "\"perVisitorDailyCap\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.configVersion").value(1));

        mockMvc.perform(post("/api/exposure/campaigns/ch/placements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"placementCode\":\"P1\",\"dailyCap\":1,"
                                + "\"expectedConfigVersion\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.placementCode").value("P1"))
                .andExpect(jsonPath("$.configVersion").value(2));

        // expectedConfigVersion 过期 → 409
        mockMvc.perform(post("/api/exposure/campaigns/ch/placements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"placementCode\":\"P2\",\"dailyCap\":1,"
                                + "\"expectedConfigVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 按展示位申请成功
        mockMvc.perform(post("/api/exposure/placement-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"campaignId\":\"ch\","
                                + "\"placementCode\":\"P1\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.placementCode").value("P1"))
                .andExpect(jsonPath("$.status").value("RESERVED"));

        // P1 额度 1 已满 → 429
        mockMvc.perform(post("/api/exposure/placement-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"campaignId\":\"ch\","
                                + "\"placementCode\":\"P1\",\"visitorId\":\"u2\"}"))
                .andExpect(status().isTooManyRequests());

        // 三层额度查询
        mockMvc.perform(get("/api/exposure/campaigns/ch/quota")
                        .param("visitorId", "u1")
                        .param("placementCode", "P1")
                        .param("utcDate", "2026-09-23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedTotal").value(1))
                .andExpect(jsonPath("$.usedVisitor").value(1))
                .andExpect(jsonPath("$.usedPlacement").value(1))
                .andExpect(jsonPath("$.configVersion").value(2))
                .andExpect(jsonPath("$.reservations[0].placementCode").value("P1"));

        // 参数越界（dailyCap=0）→ 400
        mockMvc.perform(post("/api/exposure/campaigns/ch/placements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h6\",\"placementCode\":\"PX\",\"dailyCap\":0,"
                                + "\"expectedConfigVersion\":2}"))
                .andExpect(status().isBadRequest());
    }

    private void assert409(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert429(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(429, ex.getStatus().value());
    }

    private void assert404(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(404, ex.getStatus().value());
    }
}
