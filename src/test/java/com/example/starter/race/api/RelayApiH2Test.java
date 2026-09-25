package com.example.starter.race.api;

import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.AdjustableClockTestConfig;
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
 * 接力 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验状态码（201/200/400/409/422）、JSON 字段与犯规判定结果。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdjustableClockTestConfig.class)
class RelayApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 配置交接到排名查询全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"relay-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races/relay-api/relay-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legCount":2,"exchangeLimitMs":100,
                                 "teams":[{"teamKey":"T1","runners":["a1","a2"]},
                                          {"teamKey":"T2","runners":["b1","b2"]}],
                                 "expectedVersion":1,"requestId":"req-config"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.legCount").value(2))
                .andExpect(jsonPath("$.teams[0]").value("T1"));

        // 末棒交接：交接区用时150ms超过上限100ms，判犯规但仍生成完赛记录
        mockMvc.perform(post("/api/races/relay-api/relay-handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamKey":"T1","leg":2,"receiver":"a2","elapsedMillis":3000,
                                 "zoneMillis":150,"expectedVersion":2,"requestId":"req-h1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.foul").value(true))
                .andExpect(jsonPath("$.teamFoulCount").value(1))
                .andExpect(jsonPath("$.finished").value(true))
                .andExpect(jsonPath("$.totalMillis").value(3000))
                .andExpect(jsonPath("$.teamStatus").value("RANKED"))
                .andExpect(jsonPath("$.version").value(3));

        // T2 正常完赛，总用时更短排第一
        mockMvc.perform(post("/api/races/relay-api/relay-handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamKey":"T2","leg":2,"receiver":"b2","elapsedMillis":2500,
                                 "zoneMillis":80,"expectedVersion":3,"requestId":"req-h2"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.foul").value(false));

        mockMvc.perform(get("/api/races/relay-api/relay-standing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].teamKey").value("T2"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[0].hasFouls").value(false))
                .andExpect(jsonPath("$.entries[1].teamKey").value("T1"))
                .andExpect(jsonPath("$.entries[1].rank").value(2))
                .andExpect(jsonPath("$.entries[1].hasFouls").value(true))
                .andExpect(jsonPath("$.entries[1].foulCount").value(1));

        mockMvc.perform(get("/api/races/relay-api/relay-teams/T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RANKED"))
                .andExpect(jsonPath("$.totalMillis").value(3000))
                .andExpect(jsonPath("$.legs[0].runner").value("a1"))
                .andExpect(jsonPath("$.legs[0].elapsedMillis").value(3000))
                .andExpect(jsonPath("$.legs[1].runner").value("a2"))
                .andExpect(jsonPath("$.legs[1].zoneMillis").value(150))
                .andExpect(jsonPath("$.legs[1].foul").value(true));

        mockMvc.perform(get("/api/races/relay-api/relay-fouls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fouls[0].teamKey").value("T1"))
                .andExpect(jsonPath("$.fouls[0].leg").value(2))
                .andExpect(jsonPath("$.fouls[0].zoneMillis").value(150))
                .andExpect(jsonPath("$.fouls[0].limitMillis").value(100));
    }

    @Test
    void 校验失败与冲突的HTTP状态码() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"relay-err","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/relay-err/relay-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legCount":2,"exchangeLimitMs":100,
                                 "teams":[{"teamKey":"T1","runners":["a1","a2"]}],
                                 "expectedVersion":1,"requestId":"req-config"}
                                """))
                .andExpect(status().isCreated());

        // 交接棒次不大于1：Bean 校验 400
        mockMvc.perform(post("/api/races/relay-err/relay-handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamKey":"T1","leg":1,"receiver":"a1","elapsedMillis":1000,
                                 "zoneMillis":50,"expectedVersion":2,"requestId":"req-bad-leg"}
                                """))
                .andExpect(status().isBadRequest());

        // 接棒选手与登记不一致：422
        mockMvc.perform(post("/api/races/relay-err/relay-handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamKey":"T1","leg":2,"receiver":"zz","elapsedMillis":1000,
                                 "zoneMillis":50,"expectedVersion":2,"requestId":"req-bad-recv"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 版本冲突：409
        mockMvc.perform(post("/api/races/relay-err/relay-handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamKey":"T1","leg":2,"receiver":"a2","elapsedMillis":1000,
                                 "zoneMillis":50,"expectedVersion":9,"requestId":"req-bad-ver"}
                                """))
                .andExpect(status().isConflict());

        // 正常提交后重复同一交接：409
        mockMvc.perform(post("/api/races/relay-err/relay-handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamKey":"T1","leg":2,"receiver":"a2","elapsedMillis":1000,
                                 "zoneMillis":50,"expectedVersion":2,"requestId":"req-ok"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/relay-err/relay-handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamKey":"T1","leg":2,"receiver":"a2","elapsedMillis":1100,
                                 "zoneMillis":50,"expectedVersion":3,"requestId":"req-dup"}
                                """))
                .andExpect(status().isConflict());

        // 未登记队伍明细：404
        mockMvc.perform(get("/api/races/relay-err/relay-teams/T9"))
                .andExpect(status().isNotFound());
    }
}
