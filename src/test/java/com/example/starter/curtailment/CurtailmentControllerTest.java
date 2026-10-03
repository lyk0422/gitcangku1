package com.example.starter.curtailment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 端到端：合成电站 100 kW 上限场景全流程，含幂等重放、异参 409、整次拒绝与详情查询。
 */
@AutoConfigureMockMvc
class CurtailmentControllerTest extends AbstractCurtailmentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullSyntheticFlowOverHttp() throws Exception {
        Instant start = clock.instant().plusSeconds(3600);
        Instant end = start.plusSeconds(3600);
        // 创建指令
        MvcResult created = mockMvc.perform(post("/api/v1/instructions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitId":"HYDRO-UNIT-01","startUtc":"%s","endUtc":"%s","maxPowerKw":100}
                                """.formatted(start, end)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
        // 确认（区间开始前）
        mockMvc.perform(post("/api/v1/instructions/{id}/confirm", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"http-confirm-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.version").value(1));
        // 同 requestId 同参重放
        mockMvc.perform(post("/api/v1/instructions/{id}/confirm", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"http-confirm-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        // 上报四个 80 kW 采样
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/instructions/{id}/samples", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"http-sample-%d\",\"tickStart\":\"%s\",\"avgPowerKw\":80}"
                                    .formatted(i, start.plusSeconds(900L * i))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sampleVersion").value(1));
        }
        // 对账：80 kWh
        mockMvc.perform(post("/api/v1/instructions/{id}/reconciliations", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-recon-1\",\"windowStart\":\"%s\"}".formatted(start)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalEnergyKwh").value(80.000))
                .andExpect(jsonPath("$.instructionVersion").value(1))
                .andExpect(jsonPath("$.ticks.length()").value(4));
        // 迟到更正：第一刻度 80 -> 70，差额 -2.5 kWh
        mockMvc.perform(post("/api/v1/instructions/{id}/samples", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-fix-1\",\"tickStart\":\"%s\",\"avgPowerKw\":70}"
                                .formatted(start)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sampleVersion").value(2));
        // 详情：版本、采样刻度、差额链可见，原对账不变
        mockMvc.perform(get("/api/v1/instructions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instruction.version").value(2))
                .andExpect(jsonPath("$.samples.length()").value(5))
                .andExpect(jsonPath("$.reconciliations[0].totalEnergyKwh").value(80.000))
                .andExpect(jsonPath("$.adjustments.length()").value(1))
                .andExpect(jsonPath("$.adjustments[0].deltaEnergyKwh").value(-2.500));
    }

    @Test
    void reconcileRejectsWholeWindowWhenAnySampleIs101() throws Exception {
        Instant start = clock.instant().plusSeconds(3600);
        long id = createAndConfirm(start);
        for (int i = 0; i < 4; i++) {
            int power = i == 2 ? 101 : 80;
            mockMvc.perform(post("/api/v1/instructions/{id}/samples", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"s101-%d\",\"tickStart\":\"%s\",\"avgPowerKw\":%d}"
                                    .formatted(i, start.plusSeconds(900L * i), power)))
                    .andExpect(status().isCreated());
        }
        // 任一采样 101 kW：整次拒绝，返回问题刻度，不生成部分结果
        mockMvc.perform(post("/api/v1/instructions/{id}/reconciliations", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"recon-101\",\"windowStart\":\"%s\"}".formatted(start)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECONCILE_REJECTED"))
                .andExpect(jsonPath("$.problems.length()").value(1))
                .andExpect(jsonPath("$.problems[0].reason").value("EXCEEDS_CAP"));
        mockMvc.perform(get("/api/v1/instructions/{id}", id))
                .andExpect(jsonPath("$.reconciliations.length()").value(0));
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        Instant start = clock.instant().plusSeconds(3600);
        long id = createAndConfirm(start);
        mockMvc.perform(post("/api/v1/instructions/{id}/samples", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"dup-1\",\"tickStart\":\"%s\",\"avgPowerKw\":80}".formatted(start)))
                .andExpect(status().isCreated());
        // 同 requestId 异参：409
        mockMvc.perform(post("/api/v1/instructions/{id}/samples", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"dup-1\",\"tickStart\":\"%s\",\"avgPowerKw\":90}".formatted(start)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // 同 requestId 同参：重放首次结果
        mockMvc.perform(post("/api/v1/instructions/{id}/samples", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"dup-1\",\"tickStart\":\"%s\",\"avgPowerKw\":80}".formatted(start)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sampleVersion").value(1));
        mockMvc.perform(get("/api/v1/instructions/{id}", id))
                .andExpect(jsonPath("$.samples.length()").value(1));
    }

    @Test
    void unknownInstructionReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/instructions/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** 创建并确认一条 100 kW 上限的一小时指令，返回指令 id。 */
    private long createAndConfirm(Instant start) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/instructions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitId":"HYDRO-UNIT-01","startUtc":"%s","endUtc":"%s","maxPowerKw":100}
                                """.formatted(start, start.plusSeconds(3600))))
                .andExpect(status().isCreated())
                .andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(post("/api/v1/instructions/{id}/confirm", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"confirm-" + id + "\"}"))
                .andExpect(status().isOk());
        return id;
    }
}