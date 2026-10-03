package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.web.dto.Requests.ConfirmRequest;
import com.example.starter.web.dto.Requests.CreateDirectiveRequest;
import com.example.starter.web.dto.Requests.ReadingRequest;
import com.example.starter.web.dto.Requests.ReconcileRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 限发接口端到端测试：真实 H2 库，覆盖主流程、失败分支、幂等与迟到更正。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class DirectiveApiTest {

    private static final String START = "2026-10-04T00:00:00Z";
    private static final String END = "2026-10-04T01:00:00Z";
    private static final String[] TICKS = {
        "2026-10-04T00:00:00Z", "2026-10-04T00:15:00Z", "2026-10-04T00:30:00Z", "2026-10-04T00:45:00Z"
    };
    private static final Instant BEFORE = Instant.parse("2026-10-03T12:00:00Z");
    private static final Instant AFTER = Instant.parse("2026-10-04T02:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM reconciliation_difference");
        jdbc.update("DELETE FROM reconciliation_sample");
        jdbc.update("DELETE FROM reconciliation");
        jdbc.update("DELETE FROM directive_sample");
        jdbc.update("DELETE FROM curtailment_directive");
        clock.set(BEFORE);
    }

    private long createDirective(String unit, String start, String end, int cap) throws Exception {
        MvcResult result = mvc.perform(post("/api/directives")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new CreateDirectiveRequest(unit, Instant.parse(start), Instant.parse(end), cap))))
                .andExpect(status().isCreated()).andReturn();
        return om.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode confirmOk(long id, String requestId, String by) throws Exception {
        MvcResult result = mvc.perform(post("/api/directives/{id}/confirm", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ConfirmRequest(requestId, by))))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    private void reportOk(long id, String tick, int kw) throws Exception {
        mvc.perform(post("/api/directives/{id}/readings", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ReadingRequest(Instant.parse(tick), kw))))
                .andExpect(status().isCreated());
    }

    private void reportFour(long id, int kw) throws Exception {
        for (String tick : TICKS) {
            reportOk(id, tick, kw);
        }
    }

    private JsonNode reconcileOk(long id, String requestId) throws Exception {
        MvcResult result = mvc.perform(post("/api/directives/{id}/reconcile", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ReconcileRequest(requestId, "auditor-1"))))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    private int rowCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void createDirectiveRejectsOverlapAndUnknownUnit() throws Exception {
        long id = createDirective("SYNTH-U1", START, END, 100);
        JsonNode created = getJson("/api/directives/" + id);
        assertThat(created.get("status").asText()).isEqualTo("ISSUED");
        assertThat(created.get("directiveVersion").asInt()).isEqualTo(1);

        // 同机组区间重叠（左闭右开，[00:30,01:30) 与 [00:00,01:00) 重叠）→ 409
        mvc.perform(post("/api/directives").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new CreateDirectiveRequest("SYNTH-U1",
                                Instant.parse("2026-10-04T00:30:00Z"), Instant.parse("2026-10-04T01:30:00Z"), 100))))
                .andExpect(status().isConflict());
        // 相邻区间 [01:00,02:00) 不重叠 → 201
        createDirective("SYNTH-U1", "2026-10-04T01:00:00Z", "2026-10-04T02:00:00Z", 100);
        // 未知机组 → 404
        mvc.perform(post("/api/directives").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new CreateDirectiveRequest("NO-SUCH-UNIT",
                                Instant.parse("2026-10-05T00:00:00Z"), Instant.parse("2026-10-05T01:00:00Z"), 100))))
                .andExpect(status().isNotFound());
        // 区间长度非 15 分钟整数倍 → 422
        mvc.perform(post("/api/directives").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new CreateDirectiveRequest("SYNTH-U2",
                                Instant.parse("2026-10-04T00:00:00Z"), Instant.parse("2026-10-04T00:40:00Z"), 100))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void confirmIsIdempotentAndRejectsConflicts() throws Exception {
        long id = createDirective("SYNTH-U1", START, END, 100);
        JsonNode confirmed = confirmOk(id, "req-c1", "operator-a");
        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("confirmedBy").asText()).isEqualTo("operator-a");

        // 同 requestId 同参重放 → 返回原结果
        JsonNode replay = confirmOk(id, "req-c1", "operator-a");
        assertThat(replay.get("status").asText()).isEqualTo("CONFIRMED");
        // 同 requestId 异参 → 409
        mvc.perform(post("/api/directives/{id}/confirm", id).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ConfirmRequest("req-c1", "operator-b"))))
                .andExpect(status().isConflict());
        // 不同 requestId 重复确认 → 409
        mvc.perform(post("/api/directives/{id}/confirm", id).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ConfirmRequest("req-c2", "operator-a"))))
                .andExpect(status().isConflict());
    }

    @Test
    void confirmMustPrecedeWindowStart() throws Exception {
        long id = createDirective("SYNTH-U1", START, END, 100);
        clock.set(Instant.parse(START));
        mvc.perform(post("/api/directives/{id}/confirm", id).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ConfirmRequest("req-late", "operator-a"))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(getJson("/api/directives/" + id).get("status").asText()).isEqualTo("ISSUED");
    }

    @Test
    void cancelExcludesConfirm() throws Exception {
        long cancelled = createDirective("SYNTH-U1", START, END, 100);
        mvc.perform(post("/api/directives/{id}/cancel", cancelled)).andExpect(status().isOk());
        assertThat(getJson("/api/directives/" + cancelled).get("status").asText()).isEqualTo("CANCELLED");
        // 重复撤销幂等返回当前状态
        mvc.perform(post("/api/directives/{id}/cancel", cancelled)).andExpect(status().isOk());
        // 已撤销不能确认
        mvc.perform(post("/api/directives/{id}/confirm", cancelled).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ConfirmRequest("req-x", "operator-a"))))
                .andExpect(status().isConflict());

        long confirmed = createDirective("SYNTH-U2", START, END, 100);
        confirmOk(confirmed, "req-c", "operator-a");
        // 已确认不能撤销
        mvc.perform(post("/api/directives/{id}/cancel", confirmed)).andExpect(status().isConflict());
    }
    @Test
    void readingRequiresConfirmationAndAlignedTick() throws Exception {
        long id = createDirective("SYNTH-U1", START, END, 100);
        // 未确认不能上报
        mvc.perform(post("/api/directives/{id}/readings", id).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ReadingRequest(Instant.parse(TICKS[0]), 80))))
                .andExpect(status().isUnprocessableEntity());
        confirmOk(id, "req-c", "operator-a");
        // 刻度未按 15 分钟对齐
        mvc.perform(post("/api/directives/{id}/readings", id).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ReadingRequest(Instant.parse("2026-10-04T00:07:00Z"), 80))))
                .andExpect(status().isUnprocessableEntity());
        // 刻度落在区间外（终点为开区间）
        mvc.perform(post("/api/directives/{id}/readings", id).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ReadingRequest(Instant.parse(END), 80))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(rowCount("directive_sample")).isZero();
    }

    @Test
    void reconcileFourAlignedReadingsYields80Kwh() throws Exception {
        long id = createDirective("SYNTH-U1", START, END, 100);
        confirmOk(id, "req-c", "operator-a");
        reportFour(id, 80);
        clock.set(AFTER);

        JsonNode recon = reconcileOk(id, "req-r1");
        assertThat(recon.get("reconVersion").asInt()).isEqualTo(1);
        assertThat(recon.get("directiveVersion").asInt()).isEqualTo(1);
        assertThat(recon.get("totalEnergyKwh").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(recon.get("samples")).hasSize(4);
        assertThat(recon.get("samples").get(0).get("energyKwh").decimalValue()).isEqualByComparingTo("20.00");
        // 对账后指令结束
        assertThat(getJson("/api/directives/" + id).get("status").asText()).isEqualTo("ENDED");
    }

    @Test
    void reconcileRejectsExceedingSampleWithoutPartialResult() throws Exception {
        long id = createDirective("SYNTH-U1", START, END, 100);
        confirmOk(id, "req-c", "operator-a");
        reportOk(id, TICKS[0], 80);
        reportOk(id, TICKS[1], 80);
        reportOk(id, TICKS[2], 80);
        reportOk(id, TICKS[3], 101);
        clock.set(AFTER);

        MvcResult result = mvc.perform(post("/api/directives/{id}/reconcile", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ReconcileRequest("req-r1", "auditor-1"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode body = om.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("issues")).hasSize(1);
        assertThat(body.get("issues").get(0).get("code").asText()).isEqualTo("EXCEEDS_CAP");
        assertThat(body.get("issues").get(0).get("tickUtc").asText()).isEqualTo(TICKS[3]);
        // 整单拒绝，不生成部分结果
        assertThat(rowCount("reconciliation")).isZero();
        assertThat(rowCount("reconciliation_sample")).isZero();
    }

    @Test
    void reconcileRejectsMissingSampleAndUnconfirmed() throws Exception {
        long id = createDirective("SYNTH-U1", START, END, 100);
        confirmOk(id, "req-c", "operator-a");
        reportOk(id, TICKS[0], 80);
        reportOk(id, TICKS[1], 80);
        reportOk(id, TICKS[3], 80);
        clock.set(AFTER);

        MvcResult result = mvc.perform(post("/api/directives/{id}/reconcile", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ReconcileRequest("req-r1", "auditor-1"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode issues = om.readTree(result.getResponse().getContentAsString()).get("issues");
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).get("code").asText()).isEqualTo("MISSING_SAMPLE");
        assertThat(issues.get(0).get("tickUtc").asText()).isEqualTo(TICKS[2]);
        assertThat(rowCount("reconciliation")).isZero();

        // 指令未确认不能对账
        long unconfirmed = createDirective("SYNTH-U2", START, END, 100);
        MvcResult rejected = mvc.perform(post("/api/directives/{id}/reconcile", unconfirmed)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ReconcileRequest("req-r2", "auditor-1"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(om.readTree(rejected.getResponse().getContentAsString())
                .get("issues").get(0).get("code").asText()).isEqualTo("NOT_CONFIRMED");
    }

    @Test
    void reconcileRequiresFinishedWindow() throws Exception {
        long id = createDirective("SYNTH-U1", START, END, 100);
        confirmOk(id, "req-c", "operator-a");
        reportFour(id, 80);
        // 时钟仍在区间结束之前
        MvcResult result = mvc.perform(post("/api/directives/{id}/reconcile", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ReconcileRequest("req-r1", "auditor-1"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(om.readTree(result.getResponse().getContentAsString())
                .get("issues").get(0).get("code").asText()).isEqualTo("WINDOW_NOT_FINISHED");
    }

    @Test
    void reconcileIsIdempotentAndNeverDoubleCounts() throws Exception {
        long id = createDirective("SYNTH-U1", START, END, 100);
        confirmOk(id, "req-c", "operator-a");
        reportFour(id, 80);
        clock.set(AFTER);

        JsonNode first = reconcileOk(id, "req-r1");
        // 同 requestId 同参重放 → 返回原对账单
        JsonNode replay = reconcileOk(id, "req-r1");
        assertThat(replay.get("id").asLong()).isEqualTo(first.get("id").asLong());
        // 同 requestId 异参 → 409
        mvc.perform(post("/api/directives/{id}/reconcile", id).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ReconcileRequest("req-r1", "auditor-2"))))
                .andExpect(status().isConflict());
        // 无更正时换 requestId 再对账 → 返回既有版本，不重复累计
        JsonNode again = reconcileOk(id, "req-r2");
        assertThat(again.get("reconVersion").asInt()).isEqualTo(1);
        assertThat(rowCount("reconciliation")).isEqualTo(1);
        assertThat(rowCount("reconciliation_difference")).isZero();
    }

    @Test
    void lateCorrectionAppendsVersionAndDifference() throws Exception {
        long id = createDirective("SYNTH-U1", START, END, 100);
        confirmOk(id, "req-c", "operator-a");
        reportFour(id, 80);
        clock.set(AFTER);
        JsonNode v1 = reconcileOk(id, "req-r1");
        assertThat(v1.get("totalEnergyKwh").decimalValue()).isEqualByComparingTo("80.00");

        // 迟到更正：tick0 由 80 改为 90，追加采样版本
        reportOk(id, TICKS[0], 90);
        // 已结束指令不因更正恢复为运行中
        assertThat(getJson("/api/directives/" + id).get("status").asText()).isEqualTo("ENDED");

        JsonNode v2 = reconcileOk(id, "req-r2");
        assertThat(v2.get("reconVersion").asInt()).isEqualTo(2);
        assertThat(v2.get("totalEnergyKwh").decimalValue()).isEqualByComparingTo("82.50");

        // 原采样与原对账保留
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM directive_sample WHERE directive_id = ? AND tick_utc = ?",
                Integer.class, id, java.sql.Timestamp.from(Instant.parse(TICKS[0])))).isEqualTo(2);
        JsonNode recons = getJson("/api/directives/" + id + "/reconciliations");
        assertThat(recons).hasSize(2);
        assertThat(recons.get(0).get("samples").get(0).get("avgPowerKw").asInt()).isEqualTo(80);
        assertThat(recons.get(0).get("samples").get(0).get("sampleVersion").asInt()).isEqualTo(1);
        assertThat(recons.get(1).get("samples").get(0).get("avgPowerKw").asInt()).isEqualTo(90);
        assertThat(recons.get(1).get("samples").get(0).get("sampleVersion").asInt()).isEqualTo(2);

        // 差额链：+2.5 kWh
        JsonNode diffs = getJson("/api/directives/" + id + "/differences");
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).get("deltaKwh").decimalValue()).isEqualByComparingTo("2.50");
        assertThat(diffs.get(0).get("previousReconciliationId").asLong()).isEqualTo(v1.get("id").asLong());

        // 查询接口可见最新采样版本
        JsonNode readings = getJson("/api/directives/" + id + "/readings");
        assertThat(readings).hasSize(4);
        assertThat(readings.get(0).get("sampleVersion").asInt()).isEqualTo(2);
        assertThat(readings.get(0).get("avgPowerKw").asInt()).isEqualTo(90);
    }
}