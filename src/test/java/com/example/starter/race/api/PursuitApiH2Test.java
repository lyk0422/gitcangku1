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
 * 追逐赛（PURSUIT）HTTP 端到端测试：合成样例全链路、未交会422、
 * 参数校验400与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class PursuitApiH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-pursuit-api";

    @Autowired
    private MockMvc mockMvc;

    /** 建赛、登记 a/b、启用 PURSUIT（一对：a 起始0mm 追 b 起始1000mm）。 */
    private void seedPursuitRace() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"raceId\":\"" + RACE + "\",\"requestId\":\"req-create\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("STANDARD"));
        mockMvc.perform(post("/api/races/" + RACE + "/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bib\":\"a\",\"expectedVersion\":1,\"requestId\":\"req-reg-a\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/" + RACE + "/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bib\":\"b\",\"expectedVersion\":2,\"requestId\":\"req-reg-b\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/" + RACE + "/pursuit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":3,
                                 "pairs":[{"pairId":"pair-1","pursuerBib":"a","pursuedBib":"b",
                                           "pursuerStartMm":0,"pursuedStartMm":1000}],
                                 "operator":"op-api","requestId":"req-enable"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("PURSUIT"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.pairs[0].pursuerStartMm").value(0))
                .andExpect(jsonPath("$.pairs[0].pursuedStartMm").value(1000));
    }

    private void submitSamples(String bib, int expectedVersion, long progressMm, String requestId)
            throws Exception {
        mockMvc.perform(post("/api/races/" + RACE + "/pursuit/trajectories/" + bib + "/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":%d,"expectedTrajectoryVersion":0,
                                 "samples":[{"elapsedMs":0,"progressMm":0},
                                            {"elapsedMs":10000,"progressMm":%d}],
                                 "operator":"op-api","requestId":"%s"}
                                """.formatted(expectedVersion, progressMm, requestId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trajectoryVersion").value(1));
    }

    @Test
    void 合成样例全链路_最早交会10秒() throws Exception {
        seedPursuitRace();
        submitSamples("a", 4, 3_000, "req-sa");
        submitSamples("b", 5, 2_000, "req-sb");

        String rulingBody = """
                {"rulingId":"ruling-1","pairId":"pair-1",
                 "pursuerTrajectoryVersion":1,"pursuedTrajectoryVersion":1,
                 "operator":"op-api","requestId":"req-ruling"}
                """;
        mockMvc.perform(post("/api/races/" + RACE + "/pursuit/rulings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rulingBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.catchTimeNum").value("10000"))
                .andExpect(jsonPath("$.catchTimeDen").value("1"))
                .andExpect(jsonPath("$.status").value("CURRENT"));

        // 同键同参重放：原样返回首次响应
        mockMvc.perform(post("/api/races/" + RACE + "/pursuit/rulings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rulingBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.catchTimeNum").value("10000"))
                .andExpect(jsonPath("$.rulingId").value("ruling-1"));

        mockMvc.perform(get("/api/races/" + RACE + "/pursuit/rulings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulings[0].catchTimeNum").value("10000"))
                .andExpect(jsonPath("$.rulings[0].catchTimeDen").value("1"));

        // 封榜：所有追逐对已有当前裁定 -> 200
        mockMvc.perform(post("/api/races/" + RACE + "/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":7,\"requestId\":\"req-seal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
    }

    @Test
    void 合成样例_A仅前进2999_裁定返回422() throws Exception {
        seedPursuitRace();
        submitSamples("a", 4, 2_999, "req-sa");
        submitSamples("b", 5, 2_000, "req-sb");

        mockMvc.perform(post("/api/races/" + RACE + "/pursuit/rulings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rulingId":"ruling-1","pairId":"pair-1",
                                 "pursuerTrajectoryVersion":1,"pursuedTrajectoryVersion":1,
                                 "operator":"op-api","requestId":"req-ruling"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        mockMvc.perform(get("/api/races/" + RACE + "/pursuit/rulings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulings").isEmpty());
    }

    @Test
    void 操作者为空或缺参数返回400() throws Exception {
        seedPursuitRace();
        // operator 空白 -> 400
        mockMvc.perform(post("/api/races/" + RACE + "/pursuit/trajectories/a/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":4,"expectedTrajectoryVersion":0,
                                 "samples":[{"elapsedMs":0,"progressMm":0}],
                                 "operator":" ","requestId":"req-sa"}
                                """))
                .andExpect(status().isBadRequest());
        // 采样超过10点 -> 400
        StringBuilder samples = new StringBuilder("[{\"elapsedMs\":0,\"progressMm\":0}");
        for (int i = 1; i <= 10; i++) {
            samples.append(",{\"elapsedMs\":").append(i).append(",\"progressMm\":").append(i)
                    .append("}");
        }
        samples.append("]");
        mockMvc.perform(post("/api/races/" + RACE + "/pursuit/trajectories/a/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":4,\"expectedTrajectoryVersion\":0,"
                                + "\"samples\":" + samples
                                + ",\"operator\":\"op-api\",\"requestId\":\"req-sa\"}"))
                .andExpect(status().isBadRequest());
        // 缺少 requestId -> 400
        mockMvc.perform(post("/api/races/" + RACE + "/pursuit/rulings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rulingId":"ruling-1","pairId":"pair-1",
                                 "pursuerTrajectoryVersion":1,"pursuedTrajectoryVersion":1,
                                 "operator":"op-api"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 同键异参返回409() throws Exception {
        seedPursuitRace();
        submitSamples("a", 4, 3_000, "req-sa");
        // 同 requestId 不同参数 -> 409
        mockMvc.perform(post("/api/races/" + RACE + "/pursuit/trajectories/a/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":4,"expectedTrajectoryVersion":0,
                                 "samples":[{"elapsedMs":0,"progressMm":0},
                                            {"elapsedMs":10000,"progressMm":4000}],
                                 "operator":"op-api","requestId":"req-sa"}
                                """))
                .andExpect(status().isConflict());
    }
}
