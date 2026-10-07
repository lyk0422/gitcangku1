package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePoolRequest;
import com.example.starter.exposure.web.CreatePoolRequest.PoolMemberRequest;
import com.example.starter.exposure.web.PoolResponse;
import com.example.starter.exposure.web.PoolResponse.PoolMemberView;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SelectExposureRequest;
import com.example.starter.exposure.web.SelectExposureResponse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 缺额轮转池 H2（MODE=MySQL）集成测试：确定性缺额轮转、待决阻塞、
 * 缺额提交/丢弃、额度联合核验、幂等与 HTTP 语义。
 */
@SpringBootTest(properties = "spring.datasource.url="
        + "jdbc:h2:mem:exposure_pool_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
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
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM rotation_pool_pending_deficit");
        jdbc.update("DELETE FROM rotation_pool_member");
        jdbc.update("DELETE FROM rotation_pool");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createCampaign(String requestId, String campaignId, int total, int perVisitor) {
        service.createCampaign(new CreateCampaignRequest(requestId, campaignId, total, perVisitor));
    }

    private PoolResponse createPool(String requestId, String poolId, List<PoolMemberRequest> members) {
        return service.createPool(new CreatePoolRequest(requestId, "tester", poolId, members));
    }

    private SelectExposureResponse select(String requestId, String poolId, String visitorId) {
        return service.selectFromPool(poolId,
                new SelectExposureRequest(requestId, "tester", visitorId, 1));
    }

    private Map<String, Long> committedDeficits(String poolId) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (PoolMemberView member : service.getPool(poolId).members()) {
            result.put(member.campaignId(), member.committedDeficit());
        }
        return result;
    }

    @Test
    @DisplayName("题干场景：A权2、B权1，首选取A；取消后仍选A；确认后下一轮选B")
    void specScenario_cancelKeepsPosition_confirmRotates() {
        createCampaign("req-ca", "A", 10, 10);
        createCampaign("req-cb", "B", 10, 10);
        createPool("req-p", "pool", List.of(
                new PoolMemberRequest("A", 2), new PoolMemberRequest("B", 1)));

        // 第一次：临时缺额 A=2、B=1，选 A；保存新缺额 A=-1、B=1
        SelectExposureResponse s1 = select("req-s1", "pool", "v1");
        assertEquals("A", s1.campaignId());
        assertEquals(Map.of("A", -1L, "B", 1L), s1.deficits());
        assertEquals("pool", s1.reservation().poolId());
        assertEquals("RESERVED", s1.reservation().status().name());
        assertEquals(BASE.toEpochMilli() + 60_000L, s1.reservation().expiresAtUtc());

        // 取消：丢弃临时缺额，已提交缺额仍为 0，额度释放
        service.cancel(s1.reservation().reservationId(), new ReservationActionRequest("req-x1"));
        assertEquals(Map.of("A", 0L, "B", 0L), committedDeficits("pool"));
        assertNull(service.getPool("pool").pendingReservationId());
        assertEquals(0, service.queryQuota("A", "v1", DAY).usedTotal());

        // 重选同一位置：仍选 A
        SelectExposureResponse s2 = select("req-s2", "pool", "v1");
        assertEquals("A", s2.campaignId());
        assertEquals(Map.of("A", -1L, "B", 1L), s2.deficits());

        // 确认：缺额正式提交；下一轮临时缺额 A=1、B=2，选 B
        service.confirm(s2.reservation().reservationId(), new ReservationActionRequest("req-k2"));
        assertEquals(Map.of("A", -1L, "B", 1L), committedDeficits("pool"));

        SelectExposureResponse s3 = select("req-s3", "pool", "v1");
        assertEquals("B", s3.campaignId());
        assertEquals(Map.of("A", 1L, "B", -1L), s3.deficits());
        service.confirm(s3.reservation().reservationId(), new ReservationActionRequest("req-k3"));
        assertEquals(Map.of("A", 1L, "B", -1L), committedDeficits("pool"));

        // 再下一轮：临时缺额 A=3、B=0，回到 A
        SelectExposureResponse s4 = select("req-s4", "pool", "v1");
        assertEquals("A", s4.campaignId());
    }

    @Test
    @DisplayName("到 60 秒未确认：释放额度且不推进缺额，下一次重选同一位置")
    void expiry_releasesQuotaAndDoesNotAdvanceDeficit() {
        createCampaign("req-ca", "A", 10, 10);
        createCampaign("req-cb", "B", 10, 10);
        createPool("req-p", "pool", List.of(
                new PoolMemberRequest("A", 2), new PoolMemberRequest("B", 1)));

        SelectExposureResponse s1 = select("req-s1", "pool", "v1");
        assertEquals("A", s1.campaignId());
        assertEquals(1, service.queryQuota("A", "v1", DAY).usedTotal());

        mutableClock().advanceMillis(60_000L);

        // 池查询触发到期结算：待决清除、额度释放、缺额不推进
        PoolResponse pool = service.getPool("pool");
        assertNull(pool.pendingReservationId());
        assertEquals(Map.of("A", 0L, "B", 0L), committedDeficits("pool"));
        assertEquals(0, service.queryQuota("A", "v1", DAY).usedTotal());
        assertEquals("EXPIRED",
                service.getReservation(s1.reservation().reservationId()).status().name());

        // 缺额未推进：重选仍是 A
        SelectExposureResponse s2 = select("req-s2", "pool", "v1");
        assertEquals("A", s2.campaignId());
        assertEquals(Map.of("A", -1L, "B", 1L), s2.deficits());
    }

    @Test
    @DisplayName("同分取公告号字典序小者")
    void tie_picksLexicographicallySmallerCampaign() {
        createCampaign("req-ca", "ta", 10, 10);
        createCampaign("req-cb", "tb", 10, 10);
        createPool("req-p", "pool", List.of(
                new PoolMemberRequest("tb", 1), new PoolMemberRequest("ta", 1)));

        SelectExposureResponse s1 = select("req-s1", "pool", "v1");
        assertEquals("ta", s1.campaignId());
        assertEquals(Map.of("ta", -1L, "tb", 1L), s1.deficits());
    }

    @Test
    @DisplayName("成员当天额度不足被跳过：总额度与访客额度分别核验")
    void ineligibleMemberSkipped_totalAndVisitorCaps() {
        // 总额度不足：ca 总额度 1，第一次选中并确认后当天不可再选
        createCampaign("req-ca", "ca", 1, 5);
        createCampaign("req-cb", "cb", 10, 10);
        createPool("req-p", "pool", List.of(
                new PoolMemberRequest("ca", 1), new PoolMemberRequest("cb", 1)));

        SelectExposureResponse s1 = select("req-s1", "pool", "v1");
        assertEquals("ca", s1.campaignId());
        service.confirm(s1.reservation().reservationId(), new ReservationActionRequest("req-k1"));
        assertEquals(Map.of("ca", -1L, "cb", 1L), committedDeficits("pool"));

        // ca 总额度已满被跳过，即使临时缺额相同也选 cb
        SelectExposureResponse s2 = select("req-s2", "pool", "v1");
        assertEquals("cb", s2.campaignId());
        assertEquals(Map.of("ca", 0L, "cb", 0L), s2.deficits());
    }

    @Test
    @DisplayName("访客额度不足被跳过；其他访客不受影响")
    void ineligibleMemberSkipped_visitorCap() {
        createCampaign("req-cx", "cx", 10, 1);
        createCampaign("req-cy", "cy", 10, 10);
        createPool("req-p", "pool", List.of(
                new PoolMemberRequest("cx", 1), new PoolMemberRequest("cy", 1)));

        SelectExposureResponse s1 = select("req-s1", "pool", "v1");
        assertEquals("cx", s1.campaignId());
        service.confirm(s1.reservation().reservationId(), new ReservationActionRequest("req-k1"));

        // v1 在 cx 的访客额度已满 → 选 cy
        SelectExposureResponse s2 = select("req-s2", "pool", "v1");
        assertEquals("cy", s2.campaignId());
        service.confirm(s2.reservation().reservationId(), new ReservationActionRequest("req-k2"));

        // v2 在 cx 无占用 → 同分取字典序小者 cx
        SelectExposureResponse s3 = select("req-s3", "pool", "v2");
        assertEquals("cx", s3.campaignId());
    }

    @Test
    @DisplayName("全部成员不可选返回 429，临时缺额增加不保存")
    void allIneligible_429_andDeficitsNotSaved() {
        createCampaign("req-ca", "da", 1, 5);
        createCampaign("req-cb", "db", 1, 5);
        createPool("req-p", "pool", List.of(
                new PoolMemberRequest("da", 1), new PoolMemberRequest("db", 1)));

        SelectExposureResponse s1 = select("req-s1", "pool", "v1");
        assertEquals("da", s1.campaignId());
        service.confirm(s1.reservation().reservationId(), new ReservationActionRequest("req-k1"));
        SelectExposureResponse s2 = select("req-s2", "pool", "v1");
        assertEquals("db", s2.campaignId());
        service.confirm(s2.reservation().reservationId(), new ReservationActionRequest("req-k2"));
        assertEquals(Map.of("da", 0L, "db", 0L), committedDeficits("pool"));

        // 两成员当天总额度均已满 → 429，缺额保持提交值不变、无待决
        assert429(() -> select("req-s3", "pool", "v1"));
        assertEquals(Map.of("da", 0L, "db", 0L), committedDeficits("pool"));
        assertNull(service.getPool("pool").pendingReservationId());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rotation_pool_pending_deficit WHERE pool_id = 'pool'",
                Integer.class));
    }

    @Test
    @DisplayName("RESERVED 期间池禁止另一轮转选择：409；确认/取消后放行")
    void pendingSelection_blocksNextSelect_409() {
        createCampaign("req-ca", "A", 10, 10);
        createCampaign("req-cb", "B", 10, 10);
        createPool("req-p", "pool", List.of(
                new PoolMemberRequest("A", 2), new PoolMemberRequest("B", 1)));

        SelectExposureResponse s1 = select("req-s1", "pool", "v1");
        // 不同访客、不同 requestId 的第二次选择被阻塞
        assert409(() -> select("req-s2", "pool", "v2"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE pool_id = 'pool'",
                Integer.class));

        // 取消待决后可再次选择
        service.cancel(s1.reservation().reservationId(), new ReservationActionRequest("req-x1"));
        SelectExposureResponse s2 = select("req-s2", "pool", "v2");
        assertEquals("A", s2.campaignId());
        assertEquals("v2", s2.visitorId());
    }

    @Test
    @DisplayName("期望版本不符返回 409 且不占幂等键，改正后可成功")
    void versionMismatch_409_andKeyNotOccupied() {
        createCampaign("req-ca", "A", 10, 10);
        createCampaign("req-cb", "B", 10, 10);
        createPool("req-p", "pool", List.of(
                new PoolMemberRequest("A", 2), new PoolMemberRequest("B", 1)));

        assert409(() -> service.selectFromPool("pool",
                new SelectExposureRequest("req-v", "tester", "v1", 2)));
        // 失败不占键：同键改正版本后成功
        SelectExposureResponse s = service.selectFromPool("pool",
                new SelectExposureRequest("req-v", "tester", "v1", 1));
        assertEquals("A", s.campaignId());
    }

    @Test
    @DisplayName("池校验：重复成员 400、成员公告不存在 404、重复池 409、缺失资源 404")
    void poolValidation_400_404_409() {
        createCampaign("req-ca", "A", 10, 10);
        createCampaign("req-cb", "B", 10, 10);
        assert400(() -> createPool("req-p1", "pool", List.of(
                new PoolMemberRequest("A", 1), new PoolMemberRequest("A", 2))));
        assert404(() -> createPool("req-p2", "pool", List.of(
                new PoolMemberRequest("A", 1), new PoolMemberRequest("ghost", 1))));

        createPool("req-p3", "pool", List.of(
                new PoolMemberRequest("A", 1), new PoolMemberRequest("B", 1)));
        assert409(() -> createPool("req-p4", "pool", List.of(
                new PoolMemberRequest("A", 1), new PoolMemberRequest("B", 1))));
        assert404(() -> service.getPool("nope"));
        assert404(() -> select("req-s1", "nope", "v1"));
    }

    @Test
    @DisplayName("非法参数经 HTTP 返回 400：成员数越界、权重越界、操作者为空")
    void poolValidation_http400() throws Exception {
        mockMvc.perform(post("/api/exposure/pools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"operator\":\"op\",\"poolId\":\"p1\","
                                + "\"members\":[{\"campaignId\":\"A\",\"weight\":1}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/exposure/pools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"operator\":\"op\",\"poolId\":\"p2\","
                                + "\"members\":[{\"campaignId\":\"A\",\"weight\":1},"
                                + "{\"campaignId\":\"B\",\"weight\":1},"
                                + "{\"campaignId\":\"C\",\"weight\":1},"
                                + "{\"campaignId\":\"D\",\"weight\":1},"
                                + "{\"campaignId\":\"E\",\"weight\":1},"
                                + "{\"campaignId\":\"F\",\"weight\":1}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/exposure/pools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"operator\":\"op\",\"poolId\":\"p3\","
                                + "\"members\":[{\"campaignId\":\"A\",\"weight\":0},"
                                + "{\"campaignId\":\"B\",\"weight\":11}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/exposure/pools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"operator\":\"\",\"poolId\":\"p4\","
                                + "\"members\":[{\"campaignId\":\"A\",\"weight\":1},"
                                + "{\"campaignId\":\"B\",\"weight\":1}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/exposure/pools/p5/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"operator\":\"op\",\"visitorId\":\"v1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("幂等：同键重放完整响应不重新选；异参 409；失败不占键")
    void idempotency_replay_conflict_failureNotOccupied() {
        createCampaign("req-ca", "A", 10, 10);
        createCampaign("req-cb", "B", 10, 10);
        PoolResponse pool = createPool("req-p", "pool", List.of(
                new PoolMemberRequest("A", 2), new PoolMemberRequest("B", 1)));

        // 建池同键同参重放：相同响应；同键异参 409；同键跨操作 409
        PoolResponse replay = createPool("req-p", "pool", List.of(
                new PoolMemberRequest("A", 2), new PoolMemberRequest("B", 1)));
        assertEquals(pool, replay);
        assert409(() -> createPool("req-p", "pool", List.of(
                new PoolMemberRequest("A", 3), new PoolMemberRequest("B", 1))));
        assert409(() -> service.apply(new ApplyExposureRequest("req-p", "A", "v1")));

        // 选择同键重放：冻结首次选择结果，不重新选、不重复占额度
        SelectExposureResponse s1 = select("req-s1", "pool", "v1");
        SelectExposureResponse s1Replay = select("req-s1", "pool", "v1");
        assertEquals(s1, s1Replay);
        assertEquals(s1.reservation().reservationId(), s1Replay.reservation().reservationId());
        assertEquals(1, service.queryQuota("A", "v1", DAY).usedTotal());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE pool_id = 'pool'",
                Integer.class));

        // 同键异参（不同访客/不同操作者/不同期望版本）→ 409
        assert409(() -> select("req-s1", "pool", "v2"));
        assert409(() -> service.selectFromPool("pool",
                new SelectExposureRequest("req-s1", "other", "v1", 1)));
        assert409(() -> service.selectFromPool("pool",
                new SelectExposureRequest("req-s1", "tester", "v1", 2)));

        // 失败不占键：待决期间 409 的键，取消后可复用成功
        assert409(() -> select("req-s2", "pool", "v2"));
        service.cancel(s1.reservation().reservationId(), new ReservationActionRequest("req-x1"));
        SelectExposureResponse s2 = select("req-s2", "pool", "v2");
        assertEquals("A", s2.campaignId());
    }

    @Test
    @DisplayName("普通曝光与池选择联合核验同一两级额度，互不超卖")
    void normalApplySharesQuotaWithPoolSelection() {
        createCampaign("req-ca", "na", 1, 5);
        createCampaign("req-cb", "nb", 5, 5);
        createPool("req-p", "pool", List.of(
                new PoolMemberRequest("na", 1), new PoolMemberRequest("nb", 1)));

        // 池选择占用 na 当天唯一总额度 → 普通申请 na 返回 429
        SelectExposureResponse s1 = select("req-s1", "pool", "v1");
        assertEquals("na", s1.campaignId());
        assert429(() -> service.apply(new ApplyExposureRequest("req-a1", "na", "v2")));

        // 取消释放后普通申请成功；na 再次满员 → 池选择跳过 na 选 nb
        service.cancel(s1.reservation().reservationId(), new ReservationActionRequest("req-x1"));
        ReservationResponse normal = service.apply(new ApplyExposureRequest("req-a2", "na", "v2"));
        assertEquals("RESERVED", normal.status().name());
        SelectExposureResponse s2 = select("req-s2", "pool", "v1");
        assertEquals("nb", s2.campaignId());
    }

    @Test
    @DisplayName("跨日不清池缺额，预占仍归申请日")
    void crossDay_poolDeficitsPersistAndReservationKeepsApplyDay() {
        createCampaign("req-ca", "pa", 10, 10);
        createCampaign("req-cb", "pb", 10, 10);
        createPool("req-p", "pool", List.of(
                new PoolMemberRequest("pa", 2), new PoolMemberRequest("pb", 1)));

        SelectExposureResponse s1 = select("req-s1", "pool", "v1");
        assertEquals("pa", s1.campaignId());
        assertEquals(DAY, s1.reservation().utcDate());
        service.confirm(s1.reservation().reservationId(), new ReservationActionRequest("req-k1"));
        assertEquals(Map.of("pa", -1L, "pb", 1L), committedDeficits("pool"));

        // 推进 26 小时到次日：缺额不清零，按提交缺额继续轮转 → pb
        mutableClock().advanceMillis(26 * 3_600_000L);
        LocalDate nextDay = DAY.plusDays(1);
        SelectExposureResponse s2 = select("req-s2", "pool", "v1");
        assertEquals("pb", s2.campaignId());
        assertEquals(nextDay, s2.reservation().utcDate());
        assertEquals(Map.of("pa", 1L, "pb", -1L), s2.deficits());

        // 历史日账目保留，新日额度独立
        assertEquals(1, service.queryQuota("pa", "v1", DAY).usedTotal());
        assertEquals(0, service.queryQuota("pa", "v1", nextDay).usedTotal());
        assertEquals(1, service.queryQuota("pb", "v1", nextDay).usedTotal());
    }

    @Test
    @DisplayName("HTTP 语义：建池 201、池明细 200、选择 201、缺失池 404")
    void httpSemantics_poolEndpoints() throws Exception {
        createCampaign("req-ca", "ha", 10, 10);
        createCampaign("req-cb", "hb", 10, 10);

        mockMvc.perform(post("/api/exposure/pools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"operator\":\"op\",\"poolId\":\"hp\","
                                + "\"members\":[{\"campaignId\":\"ha\",\"weight\":2},"
                                + "{\"campaignId\":\"hb\",\"weight\":1}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.poolId").value("hp"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.members[0].campaignId").value("ha"))
                .andExpect(jsonPath("$.members[0].committedDeficit").value(0));

        mockMvc.perform(get("/api/exposure/pools/hp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingReservationId").doesNotExist());

        mockMvc.perform(post("/api/exposure/pools/hp/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"operator\":\"op\","
                                + "\"visitorId\":\"v1\",\"expectedVersion\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campaignId").value("ha"))
                .andExpect(jsonPath("$.reservation.status").value("RESERVED"))
                .andExpect(jsonPath("$.reservation.poolId").value("hp"))
                .andExpect(jsonPath("$.deficits.ha").value(-1))
                .andExpect(jsonPath("$.deficits.hb").value(1));

        mockMvc.perform(get("/api/exposure/pools/nope"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/exposure/pools/nope/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"operator\":\"op\","
                                + "\"visitorId\":\"v1\",\"expectedVersion\":1}"))
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
