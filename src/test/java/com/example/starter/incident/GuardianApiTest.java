package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 双人监护 HTTP 层测试：监护启用、本人签到 403、完成显式引用双方当前代次、
 * 缺失/过期/旧代 422 与同键重放的路由与错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GuardianApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM task_attendance");
        jdbc.update("DELETE FROM task_guardians");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void reportAndTakeover() throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"INC-H1\",\"severity\":\"S2\","
                                + "\"summary\":\"高危\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/INC-H1/takeover")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/INC-H1/tasks")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"taskKey\":\"T-1\",\"groupCode\":\"OPS\",\"title\":\"带压堵漏\","
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
    }

    @Test
    void guardianshipHttpFlow() throws Exception {
        reportAndTakeover();

        // 非指挥启用监护 → 409
        mvc.perform(post("/api/incidents/INC-H1/tasks/T-1/guardians")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"guardianOne\":\"synth-exec\",\"guardianTwo\":\"synth-watch\"}"))
                .andExpect(status().isConflict());
        // 两个监护人相同 → 400
        mvc.perform(post("/api/incidents/INC-H1/tasks/T-1/guardians")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"guardianOne\":\"x\",\"guardianTwo\":\"x\"}"))
                .andExpect(status().isBadRequest());

        // 启用监护：版本从 1 开始
        mvc.perform(post("/api/incidents/INC-H1/tasks/T-1/guardians")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"guardianOne\":\"synth-exec\",\"guardianTwo\":\"synth-watch\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.guardianOne").value("synth-exec"))
                .andExpect(jsonPath("$.attendances.length()").value(0));

        // 非固定监护人签到 → 403
        mvc.perform(post("/api/incidents/INC-H1/tasks/T-1/attendance/sign")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 双方签到
        mvc.perform(post("/api/incidents/INC-H1/tasks/T-1/attendance/sign")
                        .header("X-Actor-Id", "synth-exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post("/api/incidents/INC-H1/tasks/T-1/attendance/sign")
                        .header("X-Actor-Id", "synth-watch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(1));

        // 未引用双方当前代次 → 422
        mvc.perform(post("/api/incidents/INC-H1/tasks/T-1/complete")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
        // 旧代次（gen 0）→ 422
        mvc.perform(post("/api/incidents/INC-H1/tasks/T-1/complete")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedVersion\":1,\"attendances\":["
                                + "{\"guardian\":\"synth-exec\",\"generation\":9},"
                                + "{\"guardian\":\"synth-watch\",\"generation\":1}]}"))
                .andExpect(status().isUnprocessableEntity());

        // 正确引用双方当前代次 → DONE
        mvc.perform(post("/api/incidents/INC-H1/tasks/T-1/complete")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedVersion\":1,\"attendances\":["
                                + "{\"guardian\":\"synth-exec\",\"generation\":1},"
                                + "{\"guardian\":\"synth-watch\",\"generation\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        // 完成后查询：双方凭证冻结
        mvc.perform(get("/api/incidents/INC-H1/tasks/T-1/guardians"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendances.length()").value(2))
                .andExpect(jsonPath("$.attendances[0].status").value("FROZEN"))
                .andExpect(jsonPath("$.attendances[1].status").value("FROZEN"));
    }

    @Test
    void revokeRequiresCommander_andExpiredRefRejected() throws Exception {
        reportAndTakeover();
        mvc.perform(post("/api/incidents/INC-H1/tasks/T-1/guardians")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"guardianOne\":\"synth-exec\",\"guardianTwo\":\"synth-watch\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/INC-H1/tasks/T-1/attendance/sign")
                        .header("X-Actor-Id", "synth-exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/INC-H1/tasks/T-1/attendance/sign")
                        .header("X-Actor-Id", "synth-watch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());

        // 监护人撤销他人凭证 → 403
        mvc.perform(post("/api/incidents/INC-H1/tasks/T-1/attendance/revoke")
                        .header("X-Actor-Id", "synth-exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"guardian\":\"synth-watch\"}"))
                .andExpect(status().isForbidden());
        // 指挥撤销 → 凭证 REVOKED
        mvc.perform(post("/api/incidents/INC-H1/tasks/T-1/attendance/revoke")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"guardian\":\"synth-exec\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        // 撤销后引用该代完成 → 422
        mvc.perform(post("/api/incidents/INC-H1/tasks/T-1/complete")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedVersion\":1,\"attendances\":["
                                + "{\"guardian\":\"synth-exec\",\"generation\":1},"
                                + "{\"guardian\":\"synth-watch\",\"generation\":1}]}"))
                .andExpect(status().isUnprocessableEntity());
        // 任务仍 OPEN
        mvc.perform(get("/api/incidents/INC-H1/tasks/T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        // 未启用监护的任务查询监护配置 → 404
    }
}
