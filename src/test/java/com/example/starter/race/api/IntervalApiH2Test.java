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
 * 区间计时模式 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验启用模式、区间提交、名次范围 JSON 与 400/409 状态码映射。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class IntervalApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 区间模式全链路与非法请求状态码() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-iv-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.timingMode").value("SINGLE"));

        mockMvc.perform(post("/api/races/race-iv-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"A","expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-iv-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"B","expectedVersion":2,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated());

        // 启用区间模式
        mockMvc.perform(post("/api/races/race-iv-api/interval-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":3,"operator":"op-admin","requestId":"req-mode"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timingMode").value("INTERVAL"))
                .andExpect(jsonPath("$.version").value(4));

        // 操作者为空 -> 400
        mockMvc.perform(post("/api/races/race-iv-api/runners/A/intervals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t-x","checkpointCode":"FINISH","lowerMs":1,"upperMs":2,
                                 "expectedVersion":4,"operator":"","requestId":"req-x"}
                                """))
                .andExpect(status().isBadRequest());

        // 下界大于上界 -> 400
        mockMvc.perform(post("/api/races/race-iv-api/runners/A/intervals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t-bad","checkpointCode":"FINISH","lowerMs":200,"upperMs":100,
                                 "expectedVersion":4,"operator":"op","requestId":"req-bad"}
                                """))
                .andExpect(status().isBadRequest());

        // 单值计时修订不能覆盖区间模式 -> 409
        mockMvc.perform(post("/api/races/race-iv-api/timing-revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"A","finishTimeMs":5000,"expectedVersion":4,"requestId":"req-rev"}
                                """))
                .andExpect(status().isConflict());

        // 提交合成样例区间：A[100,110]、B[105,115]
        mockMvc.perform(post("/api/races/race-iv-api/runners/A/intervals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t-a-f","checkpointCode":"FINISH","lowerMs":100,"upperMs":110,
                                 "expectedVersion":4,"operator":"op","requestId":"req-ta"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lowerMs").value(100))
                .andExpect(jsonPath("$.position").value(1));
        mockMvc.perform(post("/api/races/race-iv-api/runners/B/intervals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t-b-f","checkpointCode":"FINISH","lowerMs":105,"upperMs":115,
                                 "expectedVersion":5,"operator":"op","requestId":"req-tb"}
                                """))
                .andExpect(status().isCreated());

        // 重叠者名次范围 [1,2]，不输出固定名次
        mockMvc.perform(get("/api/races/race-iv-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timingMode").value("INTERVAL"))
                .andExpect(jsonPath("$.entries[0].bib").value("A"))
                .andExpect(jsonPath("$.entries[0].rankBest").value(1))
                .andExpect(jsonPath("$.entries[0].rankWorst").value(2))
                .andExpect(jsonPath("$.entries[0].rank").doesNotExist())
                .andExpect(jsonPath("$.entries[1].rankBest").value(1))
                .andExpect(jsonPath("$.entries[1].rankWorst").value(2));

        // 单选手区间明细
        mockMvc.perform(get("/api/races/race-iv-api/runners/A/intervals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].checkpointCode").value("FINISH"))
                .andExpect(jsonPath("$.points[0].lowerMs").value(100))
                .andExpect(jsonPath("$.points[0].timingId").value("t-a-f"));
    }
}
