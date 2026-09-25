package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 站台长度调整与 PLATFORM_RISK 风险联动测试（H2 内存库，固定业务时钟）：
 * 下调回查未来已发布计划、原长度快照固化、风险持续门禁、合规变更解除风险、
 * 站台操作幂等边界。固定时钟 2026-10-01（Asia/Shanghai），“未来”计划运营日为 2026-10-05。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformRiskApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate FUTURE_DAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate PAST_DAY = LocalDate.of(2026, 9, 25);

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedBusinessClock() {
            return Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), SH);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 下调回查与风险标记 ----------

    @Test
    void downgradeMarksOnlyFuturePublishedPlans() throws Exception {
        String platform = key("PLA");
        createPlatform(platform, 8);
        // 未来已发布：应被标记
        String future = key("SCH");
        createPlan(future, FUTURE_DAY, occ("G1", key("SEC"), iso(FUTURE_DAY, 8), iso(FUTURE_DAY, 9)));
        updateConsist(future, FUTURE_DAY, 1, 6, platform);
        publishPlan(future, key("REQ"));
        // 过去已发布：不回查
        String past = key("SCH");
        createPlan(past, PAST_DAY, occ("G2", key("SEC"), iso(PAST_DAY, 8), iso(PAST_DAY, 9)));
        updateConsist(past, PAST_DAY, 1, 6, platform);
        publishPlan(past, key("REQ"));
        // 未来草稿：不标记（发布时由门禁拦截）
        String draft = key("SCH");
        createPlan(draft, FUTURE_DAY, occ("G3", key("SEC"), iso(FUTURE_DAY, 10), iso(FUTURE_DAY, 11)));
        updateConsist(draft, FUTURE_DAY, 1, 6, platform);

        MvcResult adjusted = mvc.perform(put("/api/v1/platforms/{code}/length", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lengthBody(key("REQ"), "op-1", 5)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(adjusted);
        assertThat(body.get("previousLength").asInt()).isEqualTo(8);
        assertThat(body.get("effectiveLength").asInt()).isEqualTo(5);
        assertThat(body.get("affectedScheduleKeys")).hasSize(1);
        assertThat(body.get("affectedScheduleKeys").get(0).asText()).isEqualTo(future);

        // 风险计划不自动取消，仍为已发布
        JsonNode futurePlan = read(mvc.perform(get("/api/v1/plans/{key}", future))
                .andExpect(status().isOk()).andReturn());
        assertThat(futurePlan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(futurePlan.get("platformRisk").asBoolean()).isTrue();

        // 风险快照固化原长度
        JsonNode risks = read(mvc.perform(get("/api/v1/plans/{key}/platform-risk", future))
                .andExpect(status().isOk()).andReturn());
        assertThat(risks).hasSize(1);
        assertThat(risks.get(0).get("platformCode").asText()).isEqualTo(platform);
        assertThat(risks.get(0).get("previousLength").asInt()).isEqualTo(8);
        assertThat(risks.get(0).get("newLength").asInt()).isEqualTo(5);
        assertThat(risks.get(0).get("consistLength").asInt()).isEqualTo(6);
        assertThat(risks.get(0).get("resolvedAt").isNull()).isTrue();

        // 过去计划与草稿不受影响
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", past))
                .andExpect(status().isOk()).andReturn()).get("platformRisk").asBoolean()).isFalse();
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", draft))
                .andExpect(status().isOk()).andReturn()).get("platformRisk").asBoolean()).isFalse();

        // 站台占用仍包含风险计划（未自动取消）
        JsonNode occupancy = read(mvc.perform(get("/api/v1/platforms/{code}/occupancy", platform)
                        .param("date", FUTURE_DAY.toString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(occupancy).hasSize(1);
        assertThat(occupancy.get(0).get("scheduleKey").asText()).isEqualTo(future);
    }

    @Test
    void secondDowngradeKeepsOriginalLengthSnapshot() throws Exception {
        String platform = key("PLA");
        createPlatform(platform, 8);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, FUTURE_DAY,
                occ("G1", key("SEC"), iso(FUTURE_DAY, 8), iso(FUTURE_DAY, 9)));
        updateConsist(scheduleKey, FUTURE_DAY, 1, 7, platform);
        publishPlan(scheduleKey, key("REQ"));

        adjustLength(platform, 6);
        MvcResult second = mvc.perform(put("/api/v1/platforms/{code}/length", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lengthBody(key("REQ"), "op-1", 5)))
                .andExpect(status().isOk()).andReturn();
        // 已有未解除风险，不重复列入受影响计划
        assertThat(read(second).get("affectedScheduleKeys")).isEmpty();

        // 仍只有一条风险快照：原长度固化为首次下调前的 8，下调后长度刷新为 5
        JsonNode risks = read(mvc.perform(get("/api/v1/plans/{key}/platform-risk", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(risks).hasSize(1);
        assertThat(risks.get(0).get("previousLength").asInt()).isEqualTo(8);
        assertThat(risks.get(0).get("newLength").asInt()).isEqualTo(5);
        assertThat(risks.get(0).get("resolvedAt").isNull()).isTrue();
    }

    // ---------- 风险持续门禁与解除 ----------

    @Test
    void riskPlanGateAndResolutionByShortening() throws Exception {
        String platform = key("PLA");
        createPlatform(platform, 8);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, FUTURE_DAY,
                occ("G1", key("SEC"), iso(FUTURE_DAY, 8), iso(FUTURE_DAY, 9)));
        updateConsist(scheduleKey, FUTURE_DAY, 1, 6, platform);
        publishPlan(scheduleKey, key("REQ"));
        adjustLength(platform, 5);

        // 风险计划只能缩短编组或替换合格站台：维持超长编组 → 422，风险保留
        MvcResult rejected = mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), "op-1", 2, 6, platform)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(rejected).get("code").asText()).isEqualTo("PLATFORM_CONFLICT");
        JsonNode after = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(after.get("version").asInt()).isEqualTo(2);
        assertThat(after.get("platformRisk").asBoolean()).isTrue();

        // 缩短编组至合规 → 解除风险，计划保持已发布，版本加一
        MvcResult resolved = mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), "op-1", 2, 5, platform)))
                .andExpect(status().isOk()).andReturn();
        JsonNode resolvedBody = read(resolved);
        assertThat(resolvedBody.get("version").asInt()).isEqualTo(3);
        assertThat(resolvedBody.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(resolvedBody.get("platformRisk").asBoolean()).isFalse();
        JsonNode risks = read(mvc.perform(get("/api/v1/plans/{key}/platform-risk", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(risks).hasSize(1);
        assertThat(risks.get(0).get("resolvedAt").isNull()).isFalse();

        // 风险解除后恢复常态：已发布计划不可再变更编组
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), "op-1", 3, 4, platform)))
                .andExpect(status().isConflict());
    }

    @Test
    void riskResolvedByReplacingWithQualifiedPlatform() throws Exception {
        String platform = key("PLA");
        String qualified = key("PLB");
        createPlatform(platform, 8);
        createPlatform(qualified, 10);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, FUTURE_DAY,
                occ("G1", key("SEC"), iso(FUTURE_DAY, 8), iso(FUTURE_DAY, 9)));
        updateConsist(scheduleKey, FUTURE_DAY, 1, 6, platform);
        publishPlan(scheduleKey, key("REQ"));
        adjustLength(platform, 5);

        // 替换为合格站台 → 解除风险
        MvcResult resolved = mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), "op-1", 2, 6, qualified)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(resolved);
        assertThat(body.get("platformRisk").asBoolean()).isFalse();
        assertThat(body.get("platformCodes").get(0).asText()).isEqualTo(qualified);
    }

    @Test
    void cancelResolvesRisk() throws Exception {
        String platform = key("PLA");
        createPlatform(platform, 8);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, FUTURE_DAY,
                occ("G1", key("SEC"), iso(FUTURE_DAY, 8), iso(FUTURE_DAY, 9)));
        updateConsist(scheduleKey, FUTURE_DAY, 1, 6, platform);
        publishPlan(scheduleKey, key("REQ"));
        adjustLength(platform, 5);

        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        JsonNode plan = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(plan.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(plan.get("platformRisk").asBoolean()).isFalse();
        JsonNode risks = read(mvc.perform(get("/api/v1/plans/{key}/platform-risk", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(risks.get(0).get("resolvedAt").isNull()).isFalse();
    }

    @Test
    void draftPublishGateAfterDowngrade() throws Exception {
        String platform = key("PLA");
        createPlatform(platform, 6);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, FUTURE_DAY,
                occ("G1", key("SEC"), iso(FUTURE_DAY, 8), iso(FUTURE_DAY, 9)));
        updateConsist(scheduleKey, FUTURE_DAY, 1, 6, platform);

        // 下调不标记草稿，但发布门禁持续拦截
        MvcResult adjusted = mvc.perform(put("/api/v1/platforms/{code}/length", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lengthBody(key("REQ"), "op-1", 5)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(adjusted).get("affectedScheduleKeys")).isEmpty();
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity());

        // 站台恢复合格长度后可发布
        adjustLength(platform, 6);
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    // ---------- 站台操作幂等与校验 ----------

    @Test
    void platformCreateValidationAndIdempotency() throws Exception {
        String code = key("PLA");
        String requestKey = key("REQ");
        // 长度非正
        mvc.perform(post("/api/v1/platforms").contentType(MediaType.APPLICATION_JSON)
                        .content(platformBody(key("REQ"), "op-1", code, 0)))
                .andExpect(status().isBadRequest());

        MvcResult first = mvc.perform(post("/api/v1/platforms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(platformBody(requestKey, "op-1", code, 8)))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放
        MvcResult replay = mvc.perform(post("/api/v1/platforms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(platformBody(requestKey, "op-1", code, 8)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        // 同键异参 → 409
        mvc.perform(post("/api/v1/platforms").contentType(MediaType.APPLICATION_JSON)
                        .content(platformBody(requestKey, "op-1", code, 9)))
                .andExpect(status().isConflict());
        // 站台代码重复（新幂等键）→ 409 PLATFORM_EXISTS
        MvcResult duplicate = mvc.perform(post("/api/v1/platforms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(platformBody(key("REQ"), "op-1", code, 8)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(duplicate).get("code").asText()).isEqualTo("PLATFORM_EXISTS");
    }

    @Test
    void platformLengthIdempotencyAndNotFound() throws Exception {
        String platform = key("PLA");
        createPlatform(platform, 8);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, FUTURE_DAY,
                occ("G1", key("SEC"), iso(FUTURE_DAY, 8), iso(FUTURE_DAY, 9)));
        updateConsist(scheduleKey, FUTURE_DAY, 1, 6, platform);
        publishPlan(scheduleKey, key("REQ"));

        String requestKey = key("REQ");
        MvcResult first = mvc.perform(put("/api/v1/platforms/{code}/length", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lengthBody(requestKey, "op-1", 5)))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放：不重复标记风险
        MvcResult replay = mvc.perform(put("/api/v1/platforms/{code}/length", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lengthBody(requestKey, "op-1", 5)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        JsonNode risks = read(mvc.perform(get("/api/v1/plans/{key}/platform-risk", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(risks).hasSize(1);
        // 同键异参 → 409
        mvc.perform(put("/api/v1/platforms/{code}/length", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lengthBody(requestKey, "op-1", 4)))
                .andExpect(status().isConflict());
        // 站台不存在 → 404
        mvc.perform(put("/api/v1/platforms/{code}/length", key("PLX"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lengthBody(key("REQ"), "op-1", 5)))
                .andExpect(status().isNotFound());
        // 风险快照查询：计划不存在 → 404
        mvc.perform(get("/api/v1/plans/{key}/platform-risk", key("SCH")))
                .andExpect(status().isNotFound());
    }

    // ---------- 辅助 ----------

    private void createPlatform(String code, int effectiveLength) throws Exception {
        mvc.perform(post("/api/v1/platforms").contentType(MediaType.APPLICATION_JSON)
                        .content(platformBody(key("REQ"), "op-1", code, effectiveLength)))
                .andExpect(status().isCreated());
    }

    private void adjustLength(String code, int effectiveLength) throws Exception {
        mvc.perform(put("/api/v1/platforms/{code}/length", code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lengthBody(key("REQ"), "op-1", effectiveLength)))
                .andExpect(status().isOk());
    }

    private void createPlan(String scheduleKey, LocalDate opDate, String... occupancies)
            throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + opDate
                                + "\",\"occupancies\":[" + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private void updateConsist(String scheduleKey, LocalDate opDate, int expectedVersion,
                               int consistLength, String platform) throws Exception {
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), "op-1", expectedVersion, consistLength,
                                platform)))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 指定运营日 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String consistBody(String requestKey, String operator, int expectedVersion,
                                      int consistLength, String platform) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + expectedVersion
                + ",\"consistLength\":" + consistLength
                + ",\"cars\":[\"1\",\"2\"],\"platformCodes\":[\"" + platform + "\"]}";
    }

    private static String platformBody(String requestKey, String operator, String code,
                                       int effectiveLength) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"code\":\"" + code + "\",\"effectiveLength\":" + effectiveLength + "}";
    }

    private static String lengthBody(String requestKey, String operator, int effectiveLength) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"effectiveLength\":" + effectiveLength + "}";
    }
}
