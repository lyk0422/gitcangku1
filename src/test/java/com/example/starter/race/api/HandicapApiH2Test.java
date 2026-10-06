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
 * HANDICAP 让分模式的端到端 HTTP 测试：建赛选模式、系数写入、
 * 精确分数排名展示、封榜后 409 与参数校验 400。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class HandicapApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 让分模式全链路_展示相同但精确排名() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-hc","requestId":"req-create","scoringMode":"HANDICAP"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scoringMode").value("HANDICAP"));

        mockMvc.perform(post("/api/races/race-hc/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"A","finishTimeMs":1000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-hc/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"B","finishTimeMs":999,"expectedVersion":2,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races/race-hc/runners/A/handicap-factor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"p":1,"q":3,"expectedVersion":3,"requestId":"req-fa"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.p").value(1))
                .andExpect(jsonPath("$.q").value(3))
                .andExpect(jsonPath("$.version").value(4));
        mockMvc.perform(post("/api/races/race-hc/runners/B/handicap-factor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"p":1,"q":3,"expectedVersion":4,"requestId":"req-fb"}
                                """))
                .andExpect(status().isOk());

        // 展示均 333 毫秒，但 B 精确 333 < A 精确 1000/3，B 先 A 后
        mockMvc.perform(get("/api/races/race-hc/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("B"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[0].effectiveDisplayMs").value(333))
                .andExpect(jsonPath("$.entries[0].effectiveNum").value(333))
                .andExpect(jsonPath("$.entries[0].effectiveDen").value(1))
                .andExpect(jsonPath("$.entries[1].bib").value("A"))
                .andExpect(jsonPath("$.entries[1].rank").value(2))
                .andExpect(jsonPath("$.entries[1].effectiveDisplayMs").value(333))
                .andExpect(jsonPath("$.entries[1].effectiveNum").value(1000))
                .andExpect(jsonPath("$.entries[1].effectiveDen").value(3));

        mockMvc.perform(post("/api/races/race-hc/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.entries[0].effectiveNum").value(333));

        // 封榜后修改系数 -> 409
        mockMvc.perform(post("/api/races/race-hc/runners/A/handicap-factor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"p":1,"q":4,"expectedVersion":6,"requestId":"req-fa2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void 系数接口错误分支_400_404_409() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-std","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scoringMode").value("STANDARD"));
        mockMvc.perform(post("/api/races/race-std/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"A","finishTimeMs":1000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());

        // STANDARD 赛事登记系数 -> 400
        mockMvc.perform(post("/api/races/race-std/runners/A/handicap-factor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"p":1,"q":2,"expectedVersion":2,"requestId":"req-f1"}
                                """))
                .andExpect(status().isBadRequest());
        // 系数越界 -> 400（Bean 校验）
        mockMvc.perform(post("/api/races/race-std/runners/A/handicap-factor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"p":0,"q":2,"expectedVersion":2,"requestId":"req-f2"}
                                """))
                .andExpect(status().isBadRequest());
        // 赛事不存在 -> 404
        mockMvc.perform(post("/api/races/race-none/runners/A/handicap-factor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"p":1,"q":2,"expectedVersion":1,"requestId":"req-f3"}
                                """))
                .andExpect(status().isNotFound());
        // 版本冲突 -> 409
        mockMvc.perform(post("/api/races/race-std/runners/A/handicap-factor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"p":1,"q":2,"expectedVersion":99,"requestId":"req-f4"}
                                """))
                .andExpect(status().isConflict());
    }
}
