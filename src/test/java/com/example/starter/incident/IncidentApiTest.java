package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 事件指挥 API 测试：主流程、失败分支、状态机边界与幂等语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IncidentApiTest {

    @Autowired
    private MockMvc mvc;

    private static String key() {
        return "INC-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ResultActions report(String incidentKey) throws Exception {
        return mvc.perform(post("/api/incidents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"incidentKey":"%s","severity":"S2","summary":"数据库连接池耗尽","reporter":"ops-bot"}
                        """.formatted(incidentKey)));
    }

    private ResultActions takeCommand(String incidentKey, String actor, String commandKey) throws Exception {
        return mvc.perform(post("/api/incidents/{key}/take-command", incidentKey)
                .header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandKey\":\"%s\"}".formatted(commandKey)));
    }

    private ResultActions appendAction(String incidentKey, String actor, String commandKey,
            String actionKey, String type, String description) throws Exception {
        return mvc.perform(post("/api/incidents/{key}/actions", incidentKey)
                .header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"commandKey":"%s","actionKey":"%s","occurredAt":"2026-09-21T08:00:00Z","type":"%s","description":"%s"}
                        """.formatted(commandKey, actionKey, type, description)));
    }

    private ResultActions changeStatus(String incidentKey, String actor, String commandKey, String target)
            throws Exception {
        return mvc.perform(post("/api/incidents/{key}/status", incidentKey)
                .header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandKey\":\"%s\",\"targetStatus\":\"%s\"}".formatted(commandKey, target)));
    }

    private ResultActions initiateHandover(String incidentKey, String actor, String commandKey, String target)
            throws Exception {
        return mvc.perform(post("/api/incidents/{key}/handovers", incidentKey)
                .header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandKey\":\"%s\",\"targetCommanderId\":\"%s\"}".formatted(commandKey, target)));
    }

    private ResultActions acceptHandover(String incidentKey, String actor, String commandKey) throws Exception {
        return mvc.perform(post("/api/incidents/{key}/handovers/accept", incidentKey)
                .header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandKey\":\"%s\"}".formatted(commandKey)));
    }

    /** 推进到 COMMANDING 并返回事件键。 */
    private String commandingIncident(String actor) throws Exception {
        String key = key();
        report(key).andExpect(status().isCreated());
        takeCommand(key, actor, "cmd-take-" + key).andExpect(status().isOk());
        return key;
    }

    @Test
    void happyPathFullLifecycle() throws Exception {
        String key = key();
        report(key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REPORTED"))
                .andExpect(jsonPath("$.severity").value("S2"))
                .andExpect(jsonPath("$.commanderId").doesNotExist());

        takeCommand(key, "alice", "cmd-take-" + key).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMANDING"))
                .andExpect(jsonPath("$.commanderId").value("alice"));

        appendAction(key, "alice", "cmd-act-1", "act-1", "MITIGATE", "扩容连接池")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actionKey").value("act-1"))
                .andExpect(jsonPath("$.actorId").value("alice"))
                .andExpect(jsonPath("$.occurredAt").value("2026-09-21T08:00:00Z"));

        changeStatus(key, "alice", "cmd-st-1", "CONTAINED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTAINED"));
        changeStatus(key, "alice", "cmd-st-2", "RESOLVED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        changeStatus(key, "alice", "cmd-st-3", "CLOSED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mvc.perform(get("/api/incidents/{key}", key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.commanderId").value("alice"));

        mvc.perform(get("/api/incidents/{key}/history", key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.incident.status").value("CLOSED"))
                .andExpect(jsonPath("$.actions.length()").value(1))
                .andExpect(jsonPath("$.actions[0].actionKey").value("act-1"))
                .andExpect(jsonPath("$.events[0].eventType").value("REPORTED"))
                .andExpect(jsonPath("$.events[1].eventType").value("TOOK_COMMAND"))
                .andExpect(jsonPath("$.events[2].eventType").value("ACTION_APPENDED"))
                .andExpect(jsonPath("$.events[3].eventType").value("STATUS_CHANGED"))
                .andExpect(jsonPath("$.events[3].toStatus").value("CONTAINED"))
                .andExpect(jsonPath("$.events[4].toStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.events[5].toStatus").value("CLOSED"));
    }

    @Test
    void reportRejectsInvalidParamsAndDuplicateKey() throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"\",\"severity\":\"S2\",\"summary\":\"x\",\"reporter\":\"y\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"K1\",\"severity\":\"S9\",\"summary\":\"x\",\"reporter\":\"y\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"K1\",\"severity\":\"S1\",\"reporter\":\"y\"}"))
                .andExpect(status().isBadRequest());

        String key = key();
        report(key).andExpect(status().isCreated());
        report(key).andExpect(status().isConflict());
    }

    @Test
    void unknownIncidentReturns404() throws Exception {
        String key = key();
        mvc.perform(get("/api/incidents/{key}", key)).andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{key}/history", key)).andExpect(status().isNotFound());
        takeCommand(key, "alice", "cmd-x").andExpect(status().isNotFound());
        appendAction(key, "alice", "cmd-x", "act-x", "T", "d").andExpect(status().isNotFound());
        changeStatus(key, "alice", "cmd-x", "CONTAINED").andExpect(status().isNotFound());
        initiateHandover(key, "alice", "cmd-x", "bob").andExpect(status().isNotFound());
        acceptHandover(key, "bob", "cmd-x").andExpect(status().isNotFound());
    }

    @Test
    void missingActorHeaderReturns400() throws Exception {
        String key = commandingIncident("alice");
        mvc.perform(post("/api/incidents/{key}/actions", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"c\",\"actionKey\":\"a\",\"occurredAt\":\"2026-09-21T08:00:00Z\",\"type\":\"T\",\"description\":\"d\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void takeCommandOnlyOnce() throws Exception {
        String key = commandingIncident("alice");
        takeCommand(key, "bob", "cmd-take-2-" + key).andExpect(status().isConflict());
    }

    @Test
    void onlyCommanderCanOperate() throws Exception {
        String key = commandingIncident("alice");
        appendAction(key, "bob", "cmd-a1", "act-1", "T", "d").andExpect(status().isConflict());
        changeStatus(key, "bob", "cmd-s1", "CONTAINED").andExpect(status().isConflict());
        initiateHandover(key, "bob", "cmd-h1", "carol").andExpect(status().isConflict());
    }

    @Test
    void illegalTransitionsReturn422() throws Exception {
        String key = commandingIncident("alice");
        // 跳转：COMMANDING -> RESOLVED
        changeStatus(key, "alice", "cmd-j1", "RESOLVED").andExpect(status().isUnprocessableEntity());
        // 跳转：COMMANDING -> CLOSED
        changeStatus(key, "alice", "cmd-j2", "CLOSED").andExpect(status().isUnprocessableEntity());
        // 回退目标：COMMANDING -> REPORTED
        changeStatus(key, "alice", "cmd-j3", "REPORTED").andExpect(status().isUnprocessableEntity());

        changeStatus(key, "alice", "cmd-ok1", "CONTAINED").andExpect(status().isOk());
        // 回退：CONTAINED -> COMMANDING
        changeStatus(key, "alice", "cmd-j4", "COMMANDING").andExpect(status().isUnprocessableEntity());
        // 原地：CONTAINED -> CONTAINED
        changeStatus(key, "alice", "cmd-j5", "CONTAINED").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void handoverIsTwoStepAndSwitchesCommandAtomically() throws Exception {
        String key = commandingIncident("alice");

        initiateHandover(key, "alice", "cmd-h1", "bob").andExpect(status().isOk())
                .andExpect(jsonPath("$.commanderId").value("alice"))
                .andExpect(jsonPath("$.pendingCommanderId").value("bob"));

        // 待接受期间目标人不能操作，原指挥人仍可处置
        appendAction(key, "bob", "cmd-a-b", "act-b", "T", "d").andExpect(status().isConflict());
        appendAction(key, "alice", "cmd-a-a", "act-a", "T", "d").andExpect(status().isCreated());

        // 非目标人不能接受
        acceptHandover(key, "carol", "cmd-acc-c").andExpect(status().isConflict());

        acceptHandover(key, "bob", "cmd-acc-b").andExpect(status().isOk())
                .andExpect(jsonPath("$.commanderId").value("bob"))
                .andExpect(jsonPath("$.pendingCommanderId").doesNotExist());

        // 旧指挥人失去权限，新指挥人接管
        appendAction(key, "alice", "cmd-a-a2", "act-a2", "T", "d").andExpect(status().isConflict());
        appendAction(key, "bob", "cmd-a-b2", "act-b2", "T", "d").andExpect(status().isCreated());

        mvc.perform(get("/api/incidents/{key}/history", key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.events[2].eventType").value("HANDOVER_INITIATED"))
                .andExpect(jsonPath("$.events[4].eventType").value("HANDOVER_ACCEPTED"))
                .andExpect(jsonPath("$.events[4].fromCommanderId").value("alice"))
                .andExpect(jsonPath("$.events[4].toCommanderId").value("bob"));
    }

    @Test
    void handoverConstraints() throws Exception {
        String key = commandingIncident("alice");
        // 目标与当前指挥人相同 -> 400
        initiateHandover(key, "alice", "cmd-h-self", "alice").andExpect(status().isBadRequest());

        initiateHandover(key, "alice", "cmd-h-1", "bob").andExpect(status().isOk());
        // 已有待接受交接 -> 409
        initiateHandover(key, "alice", "cmd-h-2", "carol").andExpect(status().isConflict());

        // 推进到 RESOLVED 后不能发起或接受交接
        changeStatus(key, "alice", "cmd-s-1", "CONTAINED").andExpect(status().isOk());
        changeStatus(key, "alice", "cmd-s-2", "RESOLVED").andExpect(status().isOk());
        initiateHandover(key, "alice", "cmd-h-3", "carol").andExpect(status().isConflict());
        acceptHandover(key, "bob", "cmd-acc-1").andExpect(status().isConflict());

        // 关闭后同样禁止
        changeStatus(key, "alice", "cmd-s-3", "CLOSED").andExpect(status().isOk());
        initiateHandover(key, "alice", "cmd-h-4", "carol").andExpect(status().isConflict());
        acceptHandover(key, "bob", "cmd-acc-2").andExpect(status().isConflict());
    }

    @Test
    void acceptWithoutPendingHandoverReturns409() throws Exception {
        String key = commandingIncident("alice");
        acceptHandover(key, "bob", "cmd-acc-none").andExpect(status().isConflict());
    }

    @Test
    void actionKeyIdempotentWithinIncident() throws Exception {
        String key = commandingIncident("alice");
        appendAction(key, "alice", "cmd-a1", "act-1", "MITIGATE", "扩容")
                .andExpect(status().isCreated());

        // 同 actionKey 同内容（新 commandKey）-> 幂等返回已有记录，不新增
        appendAction(key, "alice", "cmd-a2", "act-1", "MITIGATE", "扩容")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionKey").value("act-1"));
        // 同 actionKey 不同内容 -> 409
        appendAction(key, "alice", "cmd-a3", "act-1", "MITIGATE", "不同的说明")
                .andExpect(status().isConflict());

        mvc.perform(get("/api/incidents/{key}/history", key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.actions.length()").value(1));
    }

    @Test
    void closedIncidentRejectsNewActions() throws Exception {
        String key = commandingIncident("alice");
        changeStatus(key, "alice", "cmd-s1", "CONTAINED").andExpect(status().isOk());
        changeStatus(key, "alice", "cmd-s2", "RESOLVED").andExpect(status().isOk());
        // RESOLVED 仍允许追加
        appendAction(key, "alice", "cmd-a1", "act-1", "VERIFY", "回归验证").andExpect(status().isCreated());
        changeStatus(key, "alice", "cmd-s3", "CLOSED").andExpect(status().isOk());
        // CLOSED 禁止追加
        appendAction(key, "alice", "cmd-a2", "act-2", "NOTE", "关闭后补录").andExpect(status().isConflict());
    }

    @Test
    void commandKeyReplayReturnsFirstResultAndConflictOnDifferentParams() throws Exception {
        String key = key();
        report(key).andExpect(status().isCreated());

        // 同键同参重放 -> 返回首次结果
        takeCommand(key, "alice", "cmd-take").andExpect(status().isOk())
                .andExpect(jsonPath("$.commanderId").value("alice"));
        takeCommand(key, "alice", "cmd-take").andExpect(status().isOk())
                .andExpect(jsonPath("$.commanderId").value("alice"));
        // 同键改参（换操作人）-> 409
        takeCommand(key, "bob", "cmd-take").andExpect(status().isConflict());

        // 状态变更重放返回首次结果（事件已推进也不报错）
        changeStatus(key, "alice", "cmd-st", "CONTAINED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTAINED"));
        changeStatus(key, "alice", "cmd-st", "CONTAINED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTAINED"));
        // 同键改参（换目标状态）-> 409
        changeStatus(key, "alice", "cmd-st", "RESOLVED").andExpect(status().isConflict());

        // 跨操作复用同一 commandKey -> 409
        initiateHandover(key, "alice", "cmd-st", "bob").andExpect(status().isConflict());

        // 处置记录命令重放
        appendAction(key, "alice", "cmd-act", "act-1", "T", "d").andExpect(status().isCreated());
        appendAction(key, "alice", "cmd-act", "act-1", "T", "d").andExpect(status().isCreated());
        appendAction(key, "alice", "cmd-act", "act-9", "T", "d").andExpect(status().isConflict());

        mvc.perform(get("/api/incidents/{key}/history", key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.actions.length()").value(1));
    }
}
