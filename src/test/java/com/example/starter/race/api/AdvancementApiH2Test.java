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
 * 分组晋级 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验状态码（201/200/404/409/422）、JSON 字段与快照冻结。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class AdvancementApiH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-adv-api";

    @Autowired
    private MockMvc mockMvc;

    private void createRaceWithRunners() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-adv-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        String[] bibs = {"a1", "a2", "a3", "b1", "b2", "b3"};
        long[] times = {100, 200, 300, 150, 250, 350};
        for (int i = 0; i < bibs.length; i++) {
            mockMvc.perform(post("/api/races/" + RACE + "/runners")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"bib":"%s","finishTimeMs":%d,"expectedVersion":%d,"requestId":"req-reg-%s"}
                                    """.formatted(bibs[i], times[i], i + 1, bibs[i])))
                    .andExpect(status().isCreated());
        }
    }

    private void assignGroups(int expectedVersion) throws Exception {
        mockMvc.perform(post("/api/races/" + RACE + "/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":%d,"requestId":"req-groups",
                                 "groups":[{"groupCode":"A","bibs":["a1","a2","a3"]},
                                           {"groupCode":"B","bibs":["b1","b2","b3"]}]}
                                """.formatted(expectedVersion)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(expectedVersion + 1))
                .andExpect(jsonPath("$.groups[0].groupCode").value("A"))
                .andExpect(jsonPath("$.groups[0].bibs[0]").value("a1"));
    }

    @Test
    void 分组到晋级名单全链路() throws Exception {
        createRaceWithRunners();
        assignGroups(7);

        mockMvc.perform(get("/api/races/" + RACE + "/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[1].groupCode").value("B"))
                .andExpect(jsonPath("$.groups[1].bibs[2]").value("b3"));

        // Q=1、W=1：a1、b1 直接晋级；补位池 a2=200、b2=250、a3=300、b3=350 取 a2
        mockMvc.perform(post("/api/races/" + RACE + "/advancement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"advancementKey":"adv-api-1","quotaPerGroup":1,"wildcardCount":1,
                                 "expectedVersion":8,"requestId":"req-gen"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.advancementKey").value("adv-api-1"))
                .andExpect(jsonPath("$.version").value(9))
                .andExpect(jsonPath("$.expectedCount").value(3))
                .andExpect(jsonPath("$.actualCount").value(3))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.entries[0].bib").value("a1"))
                .andExpect(jsonPath("$.entries[0].type").value("DIRECT"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[1].bib").value("b1"))
                .andExpect(jsonPath("$.entries[2].bib").value("a2"))
                .andExpect(jsonPath("$.entries[2].type").value("WILDCARD"));

        // 同 requestId 同参重放：返回首次结果
        mockMvc.perform(post("/api/races/" + RACE + "/advancement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"advancementKey":"adv-api-1","quotaPerGroup":1,"wildcardCount":1,
                                 "expectedVersion":8,"requestId":"req-gen"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actualCount").value(3));

        // 重复生成（新键）-> 409
        mockMvc.perform(post("/api/races/" + RACE + "/advancement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"advancementKey":"adv-api-2","quotaPerGroup":1,"wildcardCount":0,
                                 "expectedVersion":9,"requestId":"req-gen-2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        mockMvc.perform(get("/api/races/" + RACE + "/advancement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[2].bib").value("a2"));

        mockMvc.perform(get("/api/races/" + RACE + "/advancement-non-advanced"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advancementKey").value("adv-api-1"))
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].bib").value("a3"))
                .andExpect(jsonPath("$.entries[0].status").value("RANKED"));

        // 生成后计时修订不改写快照
        mockMvc.perform(post("/api/races/" + RACE + "/timing-revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a1","finishTimeMs":9999,"expectedVersion":9,"requestId":"req-rev-a1"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/races/" + RACE + "/advancement"))
                .andExpect(jsonPath("$.entries[0].totalTimeMs").value(100));

        // 撤销后生效名单 404，原快照按键仍可查且状态 REVOKED
        mockMvc.perform(post("/api/races/" + RACE + "/advancement/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":10,"requestId":"req-revoke"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedAt").exists());
        mockMvc.perform(get("/api/races/" + RACE + "/advancement"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/races/" + RACE + "/advancement/adv-api-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.entries[0].bib").value("a1"));

        // 撤销后可重新生成
        mockMvc.perform(post("/api/races/" + RACE + "/advancement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"advancementKey":"adv-api-3","quotaPerGroup":2,"wildcardCount":0,
                                 "expectedVersion":11,"requestId":"req-gen-3"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actualCount").value(4));
    }

    @Test
    void 有效选手不足返回422且不生成名单() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-adv-422","requestId":"req-create-422"}
                                """))
                .andExpect(status().isCreated());
        String[][] runners = {{"a1", "100"}, {"a2", "200"}, {"b1", "150"}};
        for (int i = 0; i < runners.length; i++) {
            mockMvc.perform(post("/api/races/race-adv-422/runners")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"bib":"%s","finishTimeMs":%s,"expectedVersion":%d,"requestId":"req-r-%s"}
                                    """.formatted(runners[i][0], runners[i][1], i + 1, runners[i][0])))
                    .andExpect(status().isCreated());
        }
        // b2 无完赛计时
        mockMvc.perform(post("/api/races/race-adv-422/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b2","expectedVersion":4,"requestId":"req-r-b2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-adv-422/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"req-g-422",
                                 "groups":[{"groupCode":"A","bibs":["a1","a2"]},
                                           {"groupCode":"B","bibs":["b1","b2"]}]}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races/race-adv-422/advancement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"advancementKey":"adv-422","quotaPerGroup":2,"wildcardCount":0,
                                 "expectedVersion":6,"requestId":"req-gen-422"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("B")));

        mockMvc.perform(get("/api/races/race-adv-422/advancement"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 分组划分请求校验返回400() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-adv-400","requestId":"req-create-400"}
                                """))
                .andExpect(status().isCreated());
        // 缺少 groups 字段 -> 400（Bean 校验）
        mockMvc.perform(post("/api/races/race-adv-400/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":1,"requestId":"req-g-400"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}
