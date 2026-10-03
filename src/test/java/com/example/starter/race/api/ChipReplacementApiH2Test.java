package com.example.starter.race.api;

import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 芯片更换与退赛的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖主流程、历史/明细只读查询与 409/422/404 失败分支。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class ChipReplacementApiH2Test extends AbstractRaceH2Test {

    private static final long T = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();

    @Autowired
    private MockMvc mockMvc;

    private void createRace(String raceId) throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"raceId\":\"" + raceId + "\",\"requestId\":\"c0\"}"))
                .andExpect(status().isCreated());
    }

    private void registerWithChip(String raceId, String bib, String chip, int version, String reqId)
            throws Exception {
        mockMvc.perform(post("/api/races/" + raceId + "/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bib\":\"" + bib + "\",\"finishTimeMs\":10000,"
                                + "\"chipCode\":\"" + chip + "\","
                                + "\"expectedVersion\":" + version + ",\"requestId\":\"" + reqId + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void 芯片更换到退赛全链路() throws Exception {
        createRace("r");
        registerWithChip("r", "a", "CHIP-A", 1, "c1");
        mockMvc.perform(post("/api/races/r/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkpoints\":[{\"checkpointCode\":\"p1\",\"position\":1}],"
                                + "\"expectedVersion\":2,\"requestId\":\"c2\"}"))
                .andExpect(status().isCreated());
        // 分段读数归属当前生效芯片 CHIP-A
        mockMvc.perform(post("/api/races/r/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timingId\":\"t1\",\"checkpointCode\":\"p1\",\"elapsedMillis\":1000,"
                                + "\"expectedVersion\":3,\"requestId\":\"c3\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chipCode").value("CHIP-A"));

        // 未来生效的芯片更换：201，响应固化芯片、时刻、裁判与版本
        mockMvc.perform(post("/api/races/r/runners/a/chip-replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"replacementId\":\"repl-1\",\"oldChipCode\":\"CHIP-A\","
                                + "\"newChipCode\":\"CHIP-B\",\"effectiveAt\":" + (T + 1000) + ","
                                + "\"referee\":\"ref-1\",\"operator\":\"op-1\","
                                + "\"expectedVersion\":4,\"requestId\":\"c4\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.oldChipCode").value("CHIP-A"))
                .andExpect(jsonPath("$.newChipCode").value("CHIP-B"))
                .andExpect(jsonPath("$.effectiveAt").value(T + 1000))
                .andExpect(jsonPath("$.referee").value("ref-1"))
                .andExpect(jsonPath("$.reassignedCount").value(0))
                .andExpect(jsonPath("$.version").value(5));

        // 历史与明细查询：只读、稳定返回
        mockMvc.perform(get("/api/races/r/runners/a/chip-replacements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.replacements[0].replacementId").value("repl-1"))
                .andExpect(jsonPath("$.replacements[0].newChipCode").value("CHIP-B"));
        mockMvc.perform(get("/api/races/r/chip-replacements/repl-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacement.oldChipCode").value("CHIP-A"))
                .andExpect(jsonPath("$.details").isArray());
        // 生效前读数在分段查询中仍归旧芯片
        mockMvc.perform(get("/api/races/r/runners/a/timings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoints[0].chipCode").value("CHIP-A"));

        // 退赛后禁止更换：409
        mockMvc.perform(post("/api/races/r/runners/a/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":5,\"operator\":\"op-1\",\"requestId\":\"c5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawnAt").value(T))
                .andExpect(jsonPath("$.version").value(6));
        mockMvc.perform(post("/api/races/r/runners/a/chip-replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"replacementId\":\"repl-2\",\"oldChipCode\":\"CHIP-B\","
                                + "\"newChipCode\":\"CHIP-C\",\"effectiveAt\":" + (T + 2000) + ","
                                + "\"referee\":\"ref-1\",\"operator\":\"op-1\","
                                + "\"expectedVersion\":6,\"requestId\":\"c6\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
        // 成绩状态为 WITHDRAWN
        mockMvc.perform(get("/api/races/r/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].status").value("WITHDRAWN"));
    }

    @Test
    void 失败分支返回可区分的409与422() throws Exception {
        createRace("r2");
        registerWithChip("r2", "a", "CHIP-A", 1, "c1");
        registerWithChip("r2", "b", "CHIP-B", 2, "c2");
        mockMvc.perform(post("/api/races/r2/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkpoints\":[{\"checkpointCode\":\"p1\",\"position\":1}],"
                                + "\"expectedVersion\":3,\"requestId\":\"c3\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r2/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timingId\":\"t1\",\"checkpointCode\":\"p1\",\"elapsedMillis\":1000,"
                                + "\"expectedVersion\":4,\"requestId\":\"c4\"}"))
                .andExpect(status().isCreated());

        // 新芯片已绑定：409
        mockMvc.perform(post("/api/races/r2/runners/a/chip-replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"replacementId\":\"repl-1\",\"oldChipCode\":\"CHIP-A\","
                                + "\"newChipCode\":\"CHIP-B\",\"effectiveAt\":" + T + ","
                                + "\"referee\":\"ref-1\",\"operator\":\"op-1\","
                                + "\"expectedVersion\":5,\"requestId\":\"c5\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
        // 替换时刻早于已接收分段读数：422
        mockMvc.perform(post("/api/races/r2/runners/a/chip-replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"replacementId\":\"repl-2\",\"oldChipCode\":\"CHIP-A\","
                                + "\"newChipCode\":\"CHIP-C\",\"effectiveAt\":" + (T - 1) + ","
                                + "\"referee\":\"ref-1\",\"operator\":\"op-1\","
                                + "\"expectedVersion\":5,\"requestId\":\"c6\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));
        // 不存在的选手：404
        mockMvc.perform(post("/api/races/r2/runners/nobody/chip-replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"replacementId\":\"repl-3\",\"oldChipCode\":\"CHIP-A\","
                                + "\"newChipCode\":\"CHIP-C\",\"effectiveAt\":" + T + ","
                                + "\"referee\":\"ref-1\",\"operator\":\"op-1\","
                                + "\"expectedVersion\":5,\"requestId\":\"c7\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        // 不存在的赛事：404
        mockMvc.perform(get("/api/races/no-such/chip-replacements/repl-1"))
                .andExpect(status().isNotFound());
        // 失败请求不占键：c6 修正参数后成功
        mockMvc.perform(post("/api/races/r2/runners/a/chip-replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"replacementId\":\"repl-2\",\"oldChipCode\":\"CHIP-A\","
                                + "\"newChipCode\":\"CHIP-C\",\"effectiveAt\":" + (T + 1000) + ","
                                + "\"referee\":\"ref-1\",\"operator\":\"op-1\","
                                + "\"expectedVersion\":5,\"requestId\":\"c6\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(6));
    }
}