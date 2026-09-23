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
 * 处罚申诉 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖受理冻结、双人裁决、封榜门禁、榜单“申诉中”标记与证据查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class AppealApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 申诉受理裁决到封榜全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-ap","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-ap/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":1000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-ap/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":1000,"expectedVersion":2,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-ap/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"penaltyId":"p-1","bib":"a","type":"ADD_TIME","amountMs":500,
                                 "expectedVersion":3,"requestId":"req-p1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        // 受理申诉：201，状态 PENDING，冻结榜单版本4
        mockMvc.perform(post("/api/races/race-ap/appeals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appealKey":"ak-1","bib":"a","penaltyId":"p-1","penaltyVersion":1,
                                 "reason":"判罚有误","expectedVersion":4,"requestId":"req-ak1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.penaltyVersion").value(1))
                .andExpect(jsonPath("$.freeze.penaltyType").value("ADD_TIME"))
                .andExpect(jsonPath("$.freeze.penaltyAmountMs").value(500))
                .andExpect(jsonPath("$.freeze.totalTimeMs").value(1500))
                .andExpect(jsonPath("$.freeze.rank").value(2))
                .andExpect(jsonPath("$.freeze.leaderboardVersion").value(4));

        // 公开榜单仍按原处罚计算，a 标记“申诉中”
        mockMvc.perform(get("/api/races/race-ap/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("b"))
                .andExpect(jsonPath("$.entries[0].appealPending").value(false))
                .andExpect(jsonPath("$.entries[1].bib").value("a"))
                .andExpect(jsonPath("$.entries[1].appealPending").value(true))
                .andExpect(jsonPath("$.entries[1].penaltyMs").value(500));

        // 待决申诉禁止封榜
        mockMvc.perform(post("/api/races/race-ap/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":4,"requestId":"req-seal"}
                                """))
                .andExpect(status().isConflict());

        // 第一人建议 REPLACE 200ms
        mockMvc.perform(post("/api/races/race-ap/appeals/ak-1/recommendation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officialId":"off-1","decision":"REPLACE","replacementMs":200,
                                 "requestId":"req-r1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.firstOpinion.officialId").value("off-1"))
                .andExpect(jsonPath("$.firstOpinion.decision").value("REPLACE"))
                .andExpect(jsonPath("$.firstOpinion.replacementMs").value(200));

        // 第二人确认不同建议 -> 422
        mockMvc.perform(post("/api/races/race-ap/appeals/ak-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officialId":"off-2","action":"CONFIRM","decision":"REMOVE",
                                 "requestId":"req-c1"}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 第二人确认相同建议 -> 200，生成新榜单版本5
        mockMvc.perform(post("/api/races/race-ap/appeals/ak-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officialId":"off-2","action":"CONFIRM","decision":"REPLACE",
                                 "replacementMs":200,"requestId":"req-c2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLACED"))
                .andExpect(jsonPath("$.secondOpinion.officialId").value("off-2"))
                .andExpect(jsonPath("$.secondOpinion.action").value("CONFIRM"))
                .andExpect(jsonPath("$.newLeaderboardVersion").value(5))
                .andExpect(jsonPath("$.leaderboardBefore[1].totalTimeMs").value(1500))
                .andExpect(jsonPath("$.leaderboardAfter[1].totalTimeMs").value(1200));

        // 裁决后榜单按新罚时计算且不再标记申诉中
        mockMvc.perform(get("/api/races/race-ap/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.entries[1].appealPending").value(false))
                .andExpect(jsonPath("$.entries[1].penaltyMs").value(200))
                .andExpect(jsonPath("$.entries[1].totalTimeMs").value(1200));

        // 证据查询：列表稳定排序、单条可查、只读
        mockMvc.perform(get("/api/races/race-ap/appeals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appeals[0].appealKey").value("ak-1"))
                .andExpect(jsonPath("$.appeals[0].status").value("REPLACED"))
                .andExpect(jsonPath("$.appeals.length()").value(1));
        mockMvc.perform(get("/api/races/race-ap/appeals/ak-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freeze.leaderboardVersion").value(4));
        mockMvc.perform(get("/api/races/race-ap/appeals/ak-x"))
                .andExpect(status().isNotFound());

        // 裁决完成后允许封榜
        mockMvc.perform(post("/api/races/race-ap/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"req-seal2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
    }

    @Test
    void 申诉提交参数校验与错误语义() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-ap2","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        // 缺少必填字段 -> 400
        mockMvc.perform(post("/api/races/race-ap2/appeals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appealKey":"ak-1","bib":"a"}
                                """))
                .andExpect(status().isBadRequest());
        // 赛事不存在 -> 404
        mockMvc.perform(post("/api/races/race-x/appeals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appealKey":"ak-1","bib":"a","penaltyId":"p-1","penaltyVersion":1,
                                 "reason":"r","expectedVersion":1,"requestId":"req-ak1"}
                                """))
                .andExpect(status().isNotFound());
        // 申诉不存在 -> 404
        mockMvc.perform(post("/api/races/race-ap2/appeals/ak-x/recommendation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officialId":"off-1","decision":"UPHOLD","requestId":"req-r1"}
                                """))
                .andExpect(status().isNotFound());
    }
}
