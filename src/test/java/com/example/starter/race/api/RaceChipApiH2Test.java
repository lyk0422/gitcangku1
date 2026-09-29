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
 * 芯片更换与退赛能力的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖登记带芯片、读数 UTC 归属、更换 201、新芯片占用 409、归属失败 422、
 * 退赛与封榜固化芯片、历史/绑定只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class RaceChipApiH2Test extends AbstractRaceH2Test {

    private static final long BASE = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 芯片更换读数归属退赛封榜全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"rc","requestId":"z0"}
                                """))
                .andExpect(status().isCreated());

        // a 发放芯片 CHIP-A，b 占用 CHIP-BUSY
        mockMvc.perform(post("/api/races/rc/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":9000,"chipCode":"CHIP-A",
                                 "expectedVersion":1,"requestId":"z1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chipCode").value("CHIP-A"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/races/rc/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":8000,"chipCode":"CHIP-BUSY",
                                 "expectedVersion":2,"requestId":"z2"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races/rc/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[
                                  {"checkpointCode":"p1","position":1},
                                  {"checkpointCode":"p2","position":2}],
                                 "expectedVersion":3,"requestId":"z3"}
                                """))
                .andExpect(status().isCreated());

        // a 在 BASE+1000 由 CHIP-A 读取 p1
        mockMvc.perform(post("/api/races/rc/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"za1","checkpointCode":"p1","elapsedMillis":100,
                                 "chipCode":"CHIP-A","readAtMs":%d,
                                 "expectedVersion":4,"requestId":"z4"}
                                """.formatted(BASE + 1_000L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chipCode").value("CHIP-A"));

        // 用错芯片在该时刻读数 -> 422
        mockMvc.perform(post("/api/races/rc/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"za2bad","checkpointCode":"p2","elapsedMillis":200,
                                 "chipCode":"CHIP-BUSY","readAtMs":%d,
                                 "expectedVersion":5,"requestId":"z5bad"}
                                """.formatted(BASE + 2_000L)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 更换到被 b 占用的 CHIP-BUSY -> 409
        mockMvc.perform(post("/api/races/rc/runners/a/chip-replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementId":"r-busy","oldChipCode":"CHIP-A",
                                 "newChipCode":"CHIP-BUSY","effectiveAtMs":%d,
                                 "referee":"judge-1","expectedVersion":5,"requestId":"z6busy"}
                                """.formatted(BASE + 5_000L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 合法更换为 CHIP-C（生效 BASE+5000）-> 201，固化重算前后归属
        mockMvc.perform(post("/api/races/rc/runners/a/chip-replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementId":"r-1","oldChipCode":"CHIP-A",
                                 "newChipCode":"CHIP-C","effectiveAtMs":%d,
                                 "referee":"judge-1","expectedVersion":5,"requestId":"z6"}
                                """.formatted(BASE + 5_000L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.oldChipCode").value("CHIP-A"))
                .andExpect(jsonPath("$.newChipCode").value("CHIP-C"))
                .andExpect(jsonPath("$.beforeAttribution[0].chipCode").value("CHIP-A"))
                .andExpect(jsonPath("$.afterAttribution[0].chipCode").value("CHIP-A"));

        // 生效后读数必须用 CHIP-C：CHIP-A 被拒 422，CHIP-C 成功
        mockMvc.perform(post("/api/races/rc/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"za2bad2","checkpointCode":"p2","elapsedMillis":200,
                                 "chipCode":"CHIP-A","readAtMs":%d,
                                 "expectedVersion":6,"requestId":"z7bad"}
                                """.formatted(BASE + 6_000L)))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/races/rc/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"za2","checkpointCode":"p2","elapsedMillis":200,
                                 "chipCode":"CHIP-C","readAtMs":%d,
                                 "expectedVersion":6,"requestId":"z7"}
                                """.formatted(BASE + 6_000L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chipCode").value("CHIP-C"));

        // 更换历史只读：当前 CHIP-C，一条固化记录
        mockMvc.perform(get("/api/races/rc/runners/a/chip-replacements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentChipCode").value("CHIP-C"))
                .andExpect(jsonPath("$.replacements.length()").value(1))
                .andExpect(jsonPath("$.replacements[0].referee").value("judge-1"));

        // 绑定诊断：a->CHIP-C, b->CHIP-BUSY，按芯片代码排序
        mockMvc.perform(get("/api/races/rc/chip-bindings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindings.length()").value(2))
                .andExpect(jsonPath("$.bindings[0].chipCode").value("CHIP-BUSY"))
                .andExpect(jsonPath("$.bindings[1].chipCode").value("CHIP-C"));

        // 封榜（expectedVersion=7），快照固化 p1=CHIP-A、p2=CHIP-C
        mockMvc.perform(post("/api/races/rc/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":7,"requestId":"z8"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
        mockMvc.perform(get("/api/races/rc/runners/a/timings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoints[0].chipCode").value("CHIP-A"))
                .andExpect(jsonPath("$.checkpoints[1].chipCode").value("CHIP-C"));

        // 封榜后更换 -> 409
        mockMvc.perform(post("/api/races/rc/runners/a/chip-replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementId":"r-late","oldChipCode":"CHIP-C",
                                 "newChipCode":"CHIP-LATE","effectiveAtMs":%d,
                                 "referee":"judge-1","expectedVersion":8,"requestId":"z9"}
                                """.formatted(BASE + 9_000L)))
                .andExpect(status().isConflict());
    }

    @Test
    void 退赛后释放芯片且榜单状态为WITHDRAWN() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"rw","requestId":"w0"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/rw/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":9000,"chipCode":"CHIP-W",
                                 "expectedVersion":1,"requestId":"w1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/rw/runners/a/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"referee":"judge-w","expectedVersion":2,"requestId":"w2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        mockMvc.perform(get("/api/races/rw/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.entries[0].rank").doesNotExist());

        // 芯片已释放：可发放给另一名新选手
        mockMvc.perform(post("/api/races/rw/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"c","finishTimeMs":7000,"chipCode":"CHIP-W",
                                 "expectedVersion":3,"requestId":"w3"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/races/rw/chip-bindings"))
                .andExpect(jsonPath("$.bindings.length()").value(1))
                .andExpect(jsonPath("$.bindings[0].bib").value("c"));
    }
}
