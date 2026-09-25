package com.example.starter.race.api;

import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.MutableClockTestConfig;
import org.junit.jupiter.api.BeforeEach;
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
 * 接力 API 端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验配置/登记/交接/查询/封榜链路与 201/200/409/422 状态码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfig.class)
class RelayApiH2Test extends AbstractRaceH2Test {

    private static final String RACE = "relay-api";

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetClock() {
        MutableClockTestConfig.CLOCK.reset();
    }

    @Test
    void 接力配置登记交接犯规排名到封榜全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"relay-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());

        // 配置接力：3棒、交接区上限1000ms；版本1->2
        mockMvc.perform(post("/api/races/relay-api/relay/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legCount":3,"handoffLimitMs":1000,"expectedVersion":1,"requestId":"req-cfg"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.legCount").value(3))
                .andExpect(jsonPath("$.handoffLimitMs").value(1000));

        // 重复配置 -> 409
        mockMvc.perform(post("/api/races/relay-api/relay/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legCount":2,"handoffLimitMs":500,"expectedVersion":2,"requestId":"req-cfg2"}
                                """))
                .andExpect(status().isConflict());

        // 登记队伍A（版本2->3）
        mockMvc.perform(post("/api/races/relay-api/relay/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamKey":"A","expectedVersion":2,"requestId":"req-reg-a","members":[
                                  {"legNo":1,"bib":"A1"},{"legNo":2,"bib":"A2"},{"legNo":3,"bib":"A3"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.teamKey").value("A"))
                .andExpect(jsonPath("$.members[0].bib").value("A1"))
                .andExpect(jsonPath("$.version").value(3));

        // 接力赛拒绝个人完赛计时 -> 409
        mockMvc.perform(post("/api/races/relay-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"solo","finishTimeMs":100,"expectedVersion":3,"requestId":"req-solo"}
                                """))
                .andExpect(status().isConflict());

        // 第2棒交接：交接区1000ms 等于上限，不犯规（版本3->4）
        mockMvc.perform(post("/api/races/relay-api/relay/handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamKey":"A","legNo":2,"elapsedMillis":1000,"handoffMillis":1000,
                                 "expectedVersion":3,"requestId":"req-h2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.foul").value(false))
                .andExpect(jsonPath("$.finished").value(false))
                .andExpect(jsonPath("$.teamStatus").value("RACING"))
                .andExpect(jsonPath("$.version").value(4));

        // 累计耗时不增 -> 422
        mockMvc.perform(post("/api/races/relay-api/relay/handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamKey":"A","legNo":3,"elapsedMillis":1000,"handoffMillis":500,
                                 "expectedVersion":4,"requestId":"req-h3-bad"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 同键同参重放第2棒 -> 返回首次 200，不重复变更（版本仍为4）
        mockMvc.perform(post("/api/races/relay-api/relay/handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamKey":"A","legNo":2,"elapsedMillis":1000,"handoffMillis":1000,
                                 "expectedVersion":3,"requestId":"req-h2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));

        // 推进服务端时钟后提交第3棒，交接区1500ms 超过上限1000ms 判犯规，且为末棒完赛
        MutableClockTestConfig.CLOCK.advanceMillis(500);
        mockMvc.perform(post("/api/races/relay-api/relay/handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamKey":"A","legNo":3,"elapsedMillis":2500,"handoffMillis":1500,
                                 "expectedVersion":4,"requestId":"req-h3"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.foul").value(true))
                .andExpect(jsonPath("$.finished").value(true))
                .andExpect(jsonPath("$.totalFouls").value(1))
                .andExpect(jsonPath("$.totalElapsedMillis").value(2500))
                .andExpect(jsonPath("$.teamStatus").value("RANKED"))
                .andExpect(jsonPath("$.version").value(5));

        // 逐棒明细查询
        mockMvc.perform(get("/api/races/relay-api/relay/teams/A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RANKED"))
                .andExpect(jsonPath("$.legs.length()").value(3))
                .andExpect(jsonPath("$.legs[2].elapsedMillis").value(2500))
                .andExpect(jsonPath("$.legs[2].foul").value(true))
                .andExpect(jsonPath("$.fouls.length()").value(1))
                .andExpect(jsonPath("$.fouls[0].legNo").value(3))
                .andExpect(jsonPath("$.fouls[0].limitMillis").value(1000));

        // 犯规清单查询
        mockMvc.perform(get("/api/races/relay-api/relay/fouls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 即时排名
        mockMvc.perform(get("/api/races/relay-api/relay/standing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.legCount").value(3))
                .andExpect(jsonPath("$.teams[0].teamKey").value("A"))
                .andExpect(jsonPath("$.teams[0].rank").value(1))
                .andExpect(jsonPath("$.teams[0].foul").value(true));

        // 封榜（版本5->6）
        mockMvc.perform(post("/api/races/relay-api/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk());

        // 封榜后排名为只读快照
        mockMvc.perform(get("/api/races/relay-api/relay/standing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.sealedAt").isNumber())
                .andExpect(jsonPath("$.teams[0].rank").value(1))
                .andExpect(jsonPath("$.teams[0].totalFouls").value(1));

        // 封榜后再交接 -> 409
        mockMvc.perform(post("/api/races/relay-api/relay/handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamKey":"A","legNo":2,"elapsedMillis":9999,"handoffMillis":100,
                                 "expectedVersion":6,"requestId":"req-late"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void 参数越界返回400() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"relay-bad","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());

        // 棒次数=9 超出2~8
        mockMvc.perform(post("/api/races/relay-bad/relay/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legCount":9,"handoffLimitMs":1000,"expectedVersion":1,"requestId":"req-cfg"}
                                """))
                .andExpect(status().isBadRequest());

        // 交接区上限0 超出1~10000
        mockMvc.perform(post("/api/races/relay-bad/relay/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legCount":4,"handoffLimitMs":0,"expectedVersion":1,"requestId":"req-cfg2"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
