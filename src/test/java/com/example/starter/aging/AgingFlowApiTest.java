package com.example.starter.aging;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 主流程接口测试：完整放行链路、拒绝原因、幂等重放与看板查询。
 */
class AgingFlowApiTest extends AbstractAgingTest {

    private long createBatch(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchCode\":\"" + code + "\",\"entryAt\":\"2026-01-01T00:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchCode").value(code))
                .andExpect(jsonPath("$.targetDays").value(7))
                .andExpect(jsonPath("$.status").value("AGING"))
                .andReturn();
        return Long.parseLong(com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id").toString());
    }

    private void postTurn(long batchId, int dayIndex, String requestId, String operator,
                          int expectedStatus) throws Exception {
        LocalDate date = DAY_ONE.plusDays(dayIndex);
        mockMvc.perform(post("/api/batches/{id}/turns", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"turnDate\":\"" + date
                                + "\",\"operator\":\"" + operator + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private void postTemp(long batchId, int dayIndex, String celsius, int expectedStatus)
            throws Exception {
        LocalDate date = DAY_ONE.plusDays(dayIndex);
        mockMvc.perform(post("/api/batches/{id}/temperatures", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tempDate\":\"" + date + "\",\"celsius\":" + celsius
                                + ",\"recordedBy\":\"qa\"}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void happyPathReleaseAndBoard() throws Exception {
        long batchId = createBatch("API-001");
        for (int i = 0; i < 7; i++) {
            postTurn(batchId, i, "api001-turn-" + i, "op", 200);
            postTemp(batchId, i, "10.0", 201);
        }
        MvcResult release = mockMvc.perform(post("/api/batches/{id}/release", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api001-rel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("api001-rel"))
                .andExpect(jsonPath("$.snapshot.length()").value(7))
                .andReturn();
        long releaseId = Long.parseLong(com.jayway.jsonpath.JsonPath.read(
                release.getResponse().getContentAsString(), "$.releaseId").toString());

        // 同 requestId 重放返回同一放行记录
        mockMvc.perform(post("/api/batches/{id}/release", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api001-rel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(releaseId));
        // 已放行批次异 requestId 冲突
        mockMvc.perform(post("/api/batches/{id}/release", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api001-rel-other\"}"))
                .andExpect(status().isConflict());

        // 看板：日期轴七天、当日有效温度、放行引用与快照编号
        MvcResult board = mockMvc.perform(get("/api/batches/{id}/board", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.release.releaseId").value(releaseId))
                .andExpect(jsonPath("$.days.length()").value(7))
                .andExpect(jsonPath("$.days[0].date").value("2026-01-01"))
                .andExpect(jsonPath("$.days[6].date").value("2026-01-07"))
                .andExpect(jsonPath("$.days[0].validTemperatures[0].celsius").value(10.0))
                .andReturn();
        String body = board.getResponse().getContentAsString();
        for (int i = 0; i < 7; i++) {
            Object turnId = com.jayway.jsonpath.JsonPath.read(body, "$.days[" + i + "].turn.id");
            Object tempId = com.jayway.jsonpath.JsonPath.read(body,
                    "$.days[" + i + "].validTemperatures[0].id");
            org.assertj.core.api.Assertions.assertThat(
                    com.jayway.jsonpath.JsonPath.<Object>read(body,
                            "$.days[" + i + "].releaseSnapshot.turnId")).isEqualTo(turnId);
            org.assertj.core.api.Assertions.assertThat(
                    com.jayway.jsonpath.JsonPath.<Object>read(body,
                            "$.days[" + i + "].releaseSnapshot.temperatureId")).isEqualTo(tempId);
        }
    }
    @Test
    void missingTempDayBlocksReleaseThenBackfillPasses() throws Exception {
        long batchId = createBatch("API-002");
        for (int i = 0; i < 7; i++) {
            postTurn(batchId, i, "api002-turn-" + i, "op", 200);
            if (i != 3) {
                postTemp(batchId, i, "10.0", 201);
            }
        }
        // 第 4 天缺温度：拒绝并明确返回日期，失败不占 requestId
        mockMvc.perform(post("/api/batches/{id}/release", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api002-rel\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.missingTemperatureDates[0]").value("2026-01-04"));
        org.assertj.core.api.Assertions.assertThat(countRows("release_record")).isZero();

        // 补记当天有效读数后，同一 requestId 重新审核通过
        postTemp(batchId, 3, "9.0", 201);
        mockMvc.perform(post("/api/batches/{id}/release", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api002-rel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.length()").value(7));
    }

    @Test
    void duplicateTurnSameDayIdempotentOrConflict() throws Exception {
        long batchId = createBatch("API-003");
        postTurn(batchId, 0, "api003-turn-0", "op", 200);
        // 同 requestId 同参数重放：幂等，不新增记录
        postTurn(batchId, 0, "api003-turn-0", "op", 200);
        org.assertj.core.api.Assertions.assertThat(countRows("turn_record")).isEqualTo(1);
        // 同 requestId 异参数：409
        postTurn(batchId, 0, "api003-turn-0", "other-op", 409);
        // 同日异 requestId：409
        postTurn(batchId, 0, "api003-turn-x", "op", 409);
        org.assertj.core.api.Assertions.assertThat(countRows("turn_record")).isEqualTo(1);

        // 同日两次翻面不算两天：仅第 1 天有记录，放行仍缺其余 6 天
        postTemp(batchId, 0, "10.0", 201);
        mockMvc.perform(post("/api/batches/{id}/release", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api003-rel\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.missingTurnDates.length()").value(6))
                .andExpect(jsonPath("$.details.missingTurnDates[0]").value("2026-01-02"));
    }

    @Test
    void outOfRangeTemperatureBlocksReleaseWithDate() throws Exception {
        long batchId = createBatch("API-004");
        for (int i = 0; i < 7; i++) {
            postTurn(batchId, i, "api004-turn-" + i, "op", 200);
            postTemp(batchId, i, i == 2 ? "15.0" : "10.0", 201);
        }
        // 第 3 天温度越界：拒绝并明确返回日期
        mockMvc.perform(post("/api/batches/{id}/release", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api004-rel\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.missingTemperatureDates[0]").value("2026-01-03"));
        // 补记当天合格读数后放行通过
        postTemp(batchId, 2, "11.5", 201);
        mockMvc.perform(post("/api/batches/{id}/release", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api004-rel\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void turnDateOutsideAgingPeriodRejected() throws Exception {
        long batchId = createBatch("API-005");
        mockMvc.perform(post("/api/batches/{id}/turns", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api005-turn-x\",\"turnDate\":\"2026-01-09\","
                                + "\"operator\":\"op\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}