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
 * 覆盖划分、生成（含并列超额字段）、查询、重复生成409、撤销重生成与422名额不足。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class RaceAdvancementApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 分组晋级全链路_含撤销与重新生成() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-adv-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());

        // 4名选手，版本2~5。
        String[][] bibTimes = {
                {"a1", "1000"}, {"a2", "1100"}, {"b1", "1050"}, {"b2", "1150"}};
        for (int index = 0; index < bibTimes.length; index++) {
            String bib = bibTimes[index][0];
            String finish = bibTimes[index][1];
            mockMvc.perform(post("/api/races/race-adv-api/runners")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"bib":"%s","finishTimeMs":%s,"expectedVersion":%d,"requestId":"req-%s"}
                                    """.formatted(bib, finish, index + 1, bib)))
                    .andExpect(status().isCreated());
        }

        // v6：分组划分。
        mockMvc.perform(post("/api/races/race-adv-api/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"req-groups","groups":[
                                  {"groupCode":"A","members":["a1","a2"]},
                                  {"groupCode":"B","members":["b1","b2"]}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(6))
                .andExpect(jsonPath("$.groups[0].groupCode").value("A"))
                .andExpect(jsonPath("$.groups[0].members[1]").value("a2"));

        mockMvc.perform(get("/api/races/race-adv-api/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[1].groupCode").value("B"));

        // v7：Q=1、W=1 → DIRECT a1/b1，WILDCARD a2。
        mockMvc.perform(post("/api/races/race-adv-api/advancement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"advancementKey":"adv-api-1","directQuota":1,"wildcardQuota":1,
                                 "expectedVersion":6,"requestId":"req-adv"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.advancementKey").value("adv-api-1"))
                .andExpect(jsonPath("$.version").value(7))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.advancedCount").value(3))
                .andExpect(jsonPath("$.expectedCount").value(3))
                .andExpect(jsonPath("$.overQuotaReasons").isArray())
                .andExpect(jsonPath("$.entries[0].bib").value("a1"))
                .andExpect(jsonPath("$.entries[0].type").value("DIRECT"))
                .andExpect(jsonPath("$.entries[1].bib").value("b1"))
                .andExpect(jsonPath("$.entries[2].bib").value("a2"))
                .andExpect(jsonPath("$.entries[2].type").value("WILDCARD"))
                .andExpect(jsonPath("$.generatedAt").exists());

        // 重复生成 409。
        mockMvc.perform(post("/api/races/race-adv-api/advancement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"advancementKey":"adv-api-2","directQuota":1,"wildcardQuota":1,
                                 "expectedVersion":7,"requestId":"req-adv-2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 未晋级清单：b2。
        mockMvc.perform(get("/api/races/race-adv-api/advancement/non-advanced"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advancementKey").value("adv-api-1"))
                .andExpect(jsonPath("$.groups[0].members").isArray())
                .andExpect(jsonPath("$.groups[1].members[0]").value("b2"));

        // v8：撤销。
        mockMvc.perform(post("/api/races/race-adv-api/advancement/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":7,"requestId":"req-revoke"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedAt").exists());

        // 生效名单查询转为404。
        mockMvc.perform(get("/api/races/race-adv-api/advancement"))
                .andExpect(status().isNotFound());

        // v9：撤销后可用新键重新生成。
        mockMvc.perform(post("/api/races/race-adv-api/advancement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"advancementKey":"adv-api-3","directQuota":2,"wildcardQuota":0,
                                 "expectedVersion":8,"requestId":"req-adv-3"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.advancementKey").value("adv-api-3"))
                .andExpect(jsonPath("$.advancedCount").value(4));

        // 旧键全局唯一，不能复用。
        mockMvc.perform(post("/api/races/race-adv-api/advancement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"advancementKey":"adv-api-1","directQuota":2,"wildcardQuota":0,
                                 "expectedVersion":9,"requestId":"req-adv-reuse"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void 名额不足返回422且响应指出分组() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-adv-422","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        for (String[] bibTime : new String[][] {
                {"a1", "1000"}, {"a2", "1100"}, {"b1", "1050"}, {"b2", "1150"}}) {
            int expected = switch (bibTime[0]) {
                case "a1" -> 1;
                case "a2" -> 2;
                case "b1" -> 3;
                default -> 4;
            };
            mockMvc.perform(post("/api/races/race-adv-422/runners")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"bib":"%s","finishTimeMs":%s,"expectedVersion":%d,"requestId":"req-%s"}
                                    """.formatted(bibTime[0], bibTime[1], expected, bibTime[0])))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/api/races/race-adv-422/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"req-groups","groups":[
                                  {"groupCode":"A","members":["a1","a2"]},
                                  {"groupCode":"B","members":["b1","b2"]}]}
                                """))
                .andExpect(status().isCreated());
        // b2 被取消资格。
        mockMvc.perform(post("/api/races/race-adv-422/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"penaltyId":"pen-dq","bib":"b2","type":"DISQUALIFY",
                                 "expectedVersion":6,"requestId":"req-dq"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-adv-422/advancement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"advancementKey":"adv-422","directQuota":2,"wildcardQuota":0,
                                 "expectedVersion":7,"requestId":"req-adv"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("groupCode=B")));
    }
}
