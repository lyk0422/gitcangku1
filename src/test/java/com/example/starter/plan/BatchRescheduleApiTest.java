package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 批量原子改签 API 的端到端测试（真实 H2 内存库，MODE=MySQL）：
 * 多项原子成功（含同一批内形成改签链）、任一项冲突整体回滚（计划与封锁状态不变）、
 * 幂等重放与同键换集合 409、参数非法 400。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchRescheduleApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void batchReschedulesTwoPlansAtomically() throws Exception {
        String section = key("SEC");
        String old1 = key("SCH");
        String old2 = key("SCH");
        String new1 = key("SCH");
        String new2 = key("SCH");
        createPlan(old1, occ("G1", section, iso(8), iso(9)));
        publishPlan(old1, key("REQ"));
        createPlan(old2, occ("G2", section, iso(9), iso(10)));
        publishPlan(old2, key("REQ"));
        createPlan(new1, occ("G3", section, iso(11), iso(12)));
        createPlan(new2, occ("G4", section, iso(12), iso(13)));

        MvcResult result = mvc.perform(post("/api/v1/reschedules:batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"),
                                batchItem(old1, new1), batchItem(old2, new2))))
                .andExpect(status().isOk()).andReturn();
        JsonNode results = read(result).get("results");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("oldPlan").get("scheduleKey").asText()).isEqualTo(old1);
        assertThat(results.get(0).get("newPlan").get("scheduleKey").asText()).isEqualTo(new1);
        assertThat(results.get(1).get("oldPlan").get("scheduleKey").asText()).isEqualTo(old2);
        assertThat(results.get(1).get("newPlan").get("scheduleKey").asText()).isEqualTo(new2);

        assertThat(planStatus(old1)).isEqualTo("CANCELLED");
        assertThat(planStatus(old2)).isEqualTo("CANCELLED");
        assertThat(planStatus(new1)).isEqualTo("PUBLISHED");
        assertThat(planStatus(new2)).isEqualTo("PUBLISHED");

        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).hasSize(2);
    }

    @Test
    void batchCanFormRescheduleChainWithinOneRequest() throws Exception {
        String section = key("SEC");
        String p1 = key("SCH");
        String p2 = key("SCH");
        String p3 = key("SCH");
        createPlan(p1, occ("G1", section, iso(8), iso(9)));
        publishPlan(p1, key("REQ"));
        createPlan(p2, occ("G2", section, iso(9), iso(10)));
        createPlan(p3, occ("G3", section, iso(10), iso(11)));

        // 同一批内先 P1→P2（P2 变发布），再 P2→P3
        mvc.perform(post("/api/v1/reschedules:batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), batchItem(p1, p2), batchItem(p2, p3))))
                .andExpect(status().isOk());

        assertThat(planStatus(p1)).isEqualTo("CANCELLED");
        assertThat(planStatus(p2)).isEqualTo("CANCELLED");
        assertThat(planStatus(p3)).isEqualTo("PUBLISHED");
        assertChainKeys(p1, p1, p2, p3);
    }

    @Test
    void batchRollsBackEverythingWhenOneItemConflictsWorkBlock() throws Exception {
        String section = key("SEC");
        // 先登记封锁 [10:00,11:00)
        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), key("BLK"), section, iso(10), iso(11), "[]")))
                .andExpect(status().isCreated());

        String old1 = key("SCH");
        String old2 = key("SCH");
        String new1 = key("SCH");
        String new2 = key("SCH");
        createPlan(old1, occ("G1", section, iso(8), iso(9)));
        publishPlan(old1, key("REQ"));
        createPlan(old2, occ("G2", section, iso(9), iso(9, 30)));
        publishPlan(old2, key("REQ"));
        // 第一项窗外合法；第二项后继落入封锁窗口
        createPlan(new1, occ("G3", section, iso(11), iso(11, 30)));
        createPlan(new2, occ("G4", section, iso(10, 30), iso(10, 50)));

        MvcResult fail = mvc.perform(post("/api/v1/reschedules:batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"),
                                batchItem(old1, new1), batchItem(old2, new2))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(fail).get("details").get(0).get("type").asText())
                .isEqualTo("WORK_BLOCK_CONFLICT");

        // 全部计划保持原样：旧计划仍发布、后继仍草稿
        assertThat(planStatus(old1)).isEqualTo("PUBLISHED");
        assertThat(planStatus(old2)).isEqualTo("PUBLISHED");
        assertThat(planStatus(new1)).isEqualTo("DRAFT");
        assertThat(planStatus(new2)).isEqualTo("DRAFT");
        assertChainKeys(old1, old1);
        assertChainKeys(new2, new2);

        // 生效时隙仍是两张旧计划
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).hasSize(2);
    }

    @Test
    void batchRollsBackWhenOneItemConflictsThirdPartyPlan() throws Exception {
        String section = key("SEC");
        String thirdParty = key("SCH");
        createPlan(thirdParty, occ("G9", section, iso(11), iso(12)));
        publishPlan(thirdParty, key("REQ"));

        String old1 = key("SCH");
        String old2 = key("SCH");
        String new1 = key("SCH");
        String new2 = key("SCH");
        createPlan(old1, occ("G1", section, iso(8), iso(9)));
        publishPlan(old1, key("REQ"));
        createPlan(old2, occ("G2", section, iso(9), iso(10)));
        publishPlan(old2, key("REQ"));
        createPlan(new1, occ("G3", section, iso(12), iso(13)));
        // 与第三方 11:00-12:00 重叠
        createPlan(new2, occ("G4", section, iso(11, 30), iso(12, 30)));

        mvc.perform(post("/api/v1/reschedules:batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"),
                                batchItem(old1, new1), batchItem(old2, new2))))
                .andExpect(status().isUnprocessableEntity());

        assertThat(planStatus(old1)).isEqualTo("PUBLISHED");
        assertThat(planStatus(old2)).isEqualTo("PUBLISHED");
        assertThat(planStatus(new1)).isEqualTo("DRAFT");
        assertThat(planStatus(new2)).isEqualTo("DRAFT");
    }

    @Test
    void batchIdempotentReplayAndParamConflict() throws Exception {
        String section = key("SEC");
        String old1 = key("SCH");
        String new1 = key("SCH");
        createPlan(old1, occ("G1", section, iso(8), iso(9)));
        publishPlan(old1, key("REQ"));
        createPlan(new1, occ("G3", section, iso(11), iso(12)));

        String requestKey = key("REQ");
        String body = batchBody(requestKey, batchItem(old1, new1));
        MvcResult first = mvc.perform(post("/api/v1/reschedules:batch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/reschedules:batch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键换计划集合（期望版本变化）→ 409
        mvc.perform(post("/api/v1/reschedules:batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, batchItem(old1, new1, 1, 2))))
                .andExpect(status().isConflict());
    }

    @Test
    void batchRejectsInvalidPayloads() throws Exception {
        String section = key("SEC");
        // 空清单
        mvc.perform(post("/api/v1/reschedules:batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"plans\":[]}"))
                .andExpect(status().isBadRequest());
        // 新旧相同
        String same = key("SCH");
        mvc.perform(post("/api/v1/reschedules:batch").contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), batchItem(same, same))))
                .andExpect(status().isBadRequest());
        // 后继草稿在同批重复
        String dup = key("SCH");
        mvc.perform(post("/api/v1/reschedules:batch").contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"),
                                batchItem(key("SCH"), dup), batchItem(key("SCH"), dup))))
                .andExpect(status().isBadRequest());
    }

    // ---------- 辅助 ----------

    private String planStatus(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("status").asText();
    }

    private void assertChainKeys(String queryKey, String... expectedKeys) throws Exception {
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", queryKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(chain).get("chain");
        assertThat(items).hasSize(expectedKeys.length);
        for (int i = 0; i < expectedKeys.length; i++) {
            assertThat(items.get(i).get("scheduleKey").asText()).isEqualTo(expectedKeys[i]);
        }
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, occupancies)))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + requestKey + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour, int minute) {
        Instant instant = DAY.atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String createBody(String requestKey, String scheduleKey, String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "]}";
    }

    private static String batchBody(String requestKey, String... items) {
        return "{\"requestKey\":\"" + requestKey + "\",\"plans\":[" + String.join(",", items) + "]}";
    }

    private static String batchItem(String oldKey, String newKey) {
        return batchItem(oldKey, newKey, 1, 1);
    }

    private static String batchItem(String oldKey, String newKey, int oldVersion, int newVersion) {
        return "{\"oldScheduleKey\":\"" + oldKey + "\",\"newScheduleKey\":\"" + newKey
                + "\",\"expectedOldVersion\":" + oldVersion
                + ",\"expectedNewVersion\":" + newVersion + "}";
    }

    private static String blockBody(String requestKey, String blockKey, String section,
                                    String startIso, String endIso, String plansJson) {
        return "{\"requestKey\":\"" + requestKey + "\",\"blockKey\":\"" + blockKey
                + "\",\"sectionId\":\"" + section + "\",\"startUtc\":\"" + startIso
                + "\",\"endUtc\":\"" + endIso + "\",\"plans\":" + plansJson + "}";
    }
}
