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
 * 分批起跑波次能力的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖波次登记、净计时榜单、清单/净计时查询、422/409/404 分支与封榜固化。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class RaceWaveApiH2Test extends AbstractRaceH2Test {

    /** 赛事基准起跑时刻：2026-01-01T00:00:00Z。 */
    private static final long BASE = 1_767_225_600_000L;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 波次登记净计时查询修改到封榜全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r","baseStartMs":%d,"requestId":"c0"}
                                """.formatted(BASE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baseStartMs").value(BASE));

        // 三个选手，枪声完赛均 5000ms（版本到4）
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":5000,"expectedVersion":1,"requestId":"c1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":5000,"expectedVersion":2,"requestId":"c2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"c","finishTimeMs":5000,"expectedVersion":3,"requestId":"c3"}
                                """))
                .andExpect(status().isCreated());

        // 登记两个波次：w1 基准同时刻(a)，w2 晚 2000ms(b)，c 无波次（版本到5）
        mockMvc.perform(post("/api/races/r/waves")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":4,"requestId":"c4","waves":[
                                  {"waveKey":"w1","startMs":%d,"bibs":["a"]},
                                  {"waveKey":"w2","startMs":%d,"bibs":["b"]}]}
                                """.formatted(BASE, BASE + 2000L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.waves[0].waveKey").value("w1"))
                .andExpect(jsonPath("$.waves[1].bibs[0]").value("b"));

        // 榜单按净计时：b=3000 第一；a、c 均 5000 并列
        mockMvc.perform(get("/api/races/r/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("b"))
                .andExpect(jsonPath("$.entries[0].netTimeMs").value(3000))
                .andExpect(jsonPath("$.entries[1].bib").value("a"))
                .andExpect(jsonPath("$.entries[2].bib").value("c"))
                .andExpect(jsonPath("$.entries[2].netTimeMs").value(5000));

        // 参赛者净计时查询
        mockMvc.perform(get("/api/races/r/runners/b/net-time"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waveKey").value("w2"))
                .andExpect(jsonPath("$.netTimeMs").value(3000))
                .andExpect(jsonPath("$.baseStartMs").value(BASE));
        mockMvc.perform(get("/api/races/r/runners/z/net-time"))
                .andExpect(status().isNotFound());

        // 重复登记波次：409
        mockMvc.perform(post("/api/races/r/waves")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"c5","waves":[
                                  {"waveKey":"w3","startMs":%d,"bibs":["c"]}]}
                                """.formatted(BASE)))
                .andExpect(status().isConflict());

        // 把 w2 起跑改成晚于基准 6000ms：b 净计时 5000-6000=-1000 标记 INVALID_WAVE（版本到6）
        mockMvc.perform(put("/api/races/r/waves/w2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startMs":%d,"bibs":["b"],"expectedVersion":5,"requestId":"c6"}
                                """.formatted(BASE + 6000L)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/races/r/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[2].bib").value("b"))
                .andExpect(jsonPath("$.entries[2].status").value("INVALID_WAVE"))
                .andExpect(jsonPath("$.entries[2].netTimeMs").doesNotExist())
                .andExpect(jsonPath("$.entries[2].invalidReason").value("INVALID_WAVE"));

        // 封榜（版本到7）后快照固化，随后改波次 409
        mockMvc.perform(post("/api/races/r/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":6,"requestId":"c7"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[2].status").value("INVALID_WAVE"))
                .andExpect(jsonPath("$.entries[2].waveKey").value("w2"));
        mockMvc.perform(put("/api/races/r/waves/w2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startMs":%d,"bibs":["b"],"expectedVersion":7,"requestId":"c8"}
                                """.formatted(BASE)))
                .andExpect(status().isConflict());
        // 封榜后净计时查询仍返回快照内容
        mockMvc.perform(get("/api/races/r/runners/b/net-time"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVALID_WAVE"))
                .andExpect(jsonPath("$.waveKey").value("w2"));
    }

    @Test
    void 波次区间与版本冲突返回约定状态码() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r2","baseStartMs":%d,"requestId":"d0"}
                                """.formatted(BASE)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r2/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":5000,"expectedVersion":1,"requestId":"d1"}
                                """))
                .andExpect(status().isCreated());

        // Bean Validation：waves 为空 → 400
        mockMvc.perform(post("/api/races/r2/waves")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"requestId":"d2","waves":[]}
                                """))
                .andExpect(status().isBadRequest());

        // 版本不匹配 → 409
        mockMvc.perform(post("/api/races/r2/waves")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":9,"requestId":"d3","waves":[
                                  {"waveKey":"w1","startMs":%d,"bibs":["a"]}]}
                                """.formatted(BASE)))
                .andExpect(status().isConflict());

        // 不存在的赛事 → 404
        mockMvc.perform(get("/api/races/nope/waves"))
                .andExpect(status().isNotFound());
    }
}
