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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分批起跑波次 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖波次登记、净计时排名、INVALID_WAVE、封榜快照与 409/422 错误分支。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class WaveApiH2Test extends AbstractRaceH2Test {

    private static final long BASE = 1_760_000_000_000L;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 波次登记净计时排名到封榜快照全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-wave-api","baseStartAt":%d,"requestId":"req-create"}
                                """.formatted(BASE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baseStartAt").value(BASE));

        mockMvc.perform(post("/api/races/race-wave-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":100000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-wave-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":60000,"expectedVersion":2,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated());

        // v4: a 波次晚 60000 起跑，净计时 40000
        mockMvc.perform(put("/api/races/race-wave-api/waves")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"waves":[{"waveKey":"wave-a","startAt":%d,"runners":["a"]}],
                                 "expectedVersion":3,"requestId":"req-waves"}
                                """.formatted(BASE + 60_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseStartAt").value(BASE))
                .andExpect(jsonPath("$.waves[0].waveKey").value("wave-a"))
                .andExpect(jsonPath("$.waves[0].runners[0]").value("a"));

        mockMvc.perform(get("/api/races/race-wave-api/waves"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.waves[0].startAt").value(BASE + 60_000L));

        mockMvc.perform(get("/api/races/race-wave-api/results"))
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[0].gunTimeMs").value(100000))
                .andExpect(jsonPath("$.entries[0].netTimeMs").value(40000))
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].rank").value(2))
                .andExpect(jsonPath("$.entries[1].netTimeMs").value(60000));

        mockMvc.perform(get("/api/races/race-wave-api/runners/a/net-time"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waveKey").value("wave-a"))
                .andExpect(jsonPath("$.netTimeMs").value(40000))
                .andExpect(jsonPath("$.status").value("RANKED"));

        // 封榜后快照固化净计时
        mockMvc.perform(post("/api/races/race-wave-api/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":4,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));

        mockMvc.perform(get("/api/races/race-wave-api/snapshot"))
                .andExpect(jsonPath("$.entries[0].waveKey").value("wave-a"))
                .andExpect(jsonPath("$.entries[0].netTimeMs").value(40000))
                .andExpect(jsonPath("$.entries[0].baseStartAt").value(BASE));

        // 封榜后修改波次 -> 409
        mockMvc.perform(put("/api/races/race-wave-api/waves")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"waves":[{"waveKey":"wave-a","startAt":%d,"runners":["a"]}],
                                 "expectedVersion":5,"requestId":"req-after"}
                                """.formatted(BASE + 60_000L)))
                .andExpect(status().isConflict());
    }

    @Test
    void 波次错误分支_409版本_422区间与400参数() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-wave-err","baseStartAt":%d,"requestId":"req-create"}
                                """.formatted(BASE)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-wave-err/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":10000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());

        // 参赛者不存在 -> 422
        mockMvc.perform(put("/api/races/race-wave-err/waves")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"waves":[{"waveKey":"w","startAt":%d,"runners":["ghost"]}],
                                 "expectedVersion":2,"requestId":"req-ghost"}
                                """.formatted(BASE + 1L)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 同一参赛者跨波次 -> 400
        mockMvc.perform(put("/api/races/race-wave-err/waves")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"waves":[
                                  {"waveKey":"w1","startAt":%d,"runners":["a"]},
                                  {"waveKey":"w2","startAt":%d,"runners":["a"]}],
                                 "expectedVersion":2,"requestId":"req-dup"}
                                """.formatted(BASE + 1L, BASE + 2L)))
                .andExpect(status().isBadRequest());

        // 版本不匹配 -> 409（不存在的版本号99）
        mockMvc.perform(put("/api/races/race-wave-err/waves")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"waves":[{"waveKey":"w","startAt":%d,"runners":["a"]}],
                                 "expectedVersion":99,"requestId":"req-ver"}
                                """.formatted(BASE + 1L)))
                .andExpect(status().isConflict());

        // 失败均不推进版本
        mockMvc.perform(get("/api/races/race-wave-err/waves"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.waves").isArray())
                .andExpect(jsonPath("$.waves").isEmpty());
    }
}
