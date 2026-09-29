package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 外部通报回执 HTTP 层测试：升级携带机构、查询回执视图、回执录入、
 * 同 requestId 重放与改参 409、关键机构逾期降级 409（带缺失清单）及补回执后放行。
 * 时间推进使用可控 Clock。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class NotificationApiTest {

    private static final Instant T0 = Instant.parse("2026-09-29T11:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-09-29T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM incident_notification_acks");
        jdbc.update("DELETE FROM incident_notification_agencies");
        jdbc.update("DELETE FROM incident_notifications");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void escalateWithAgencies(String incidentKey) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey + "\",\"severity\":\"S1\","
                                + "\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T11:06:00Z"));
        mvc.perform(post("/api/incidents/{k}/escalations/check", incidentKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"agencies\":["
                                + "{\"agencyKey\":\"yi\",\"critical\":false,"
                                + "\"ackDeadline\":\"2026-09-29T12:00:00Z\"},"
                                + "{\"agencyKey\":\"jia\",\"critical\":true,"
                                + "\"ackDeadline\":\"2026-09-29T12:00:00Z\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.status").value("OPEN"));
    }

    @Test
    void notificationHttpFlow() throws Exception {
        escalateWithAgencies("INC-NH-1");

        // 查询：机构按键稳定排序，初始均未回执、未逾期
        mvc.perform(get("/api/incidents/{k}/notifications", "INC-NH-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.acknowledgedCount").value(0))
                .andExpect(jsonPath("$.agencies[0].agencyKey").value("jia"))
                .andExpect(jsonPath("$.agencies[0].critical").value(true))
                .andExpect(jsonPath("$.agencies[1].agencyKey").value("yi"))
                .andExpect(jsonPath("$.overdueCritical").isArray())
                .andExpect(jsonPath("$.followUp").isArray());

        // 11:30 jia 首份回执
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T11:30:00Z"));
        mvc.perform(post("/api/incidents/{k}/notifications/ack", "INC-NH-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"agencyKey\":\"jia\","
                                + "\"signer\":\"zhang\",\"content\":\"已知悉\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedCount").value(1))
                .andExpect(jsonPath("$.agencies[0].signer").value("zhang"))
                .andExpect(jsonPath("$.agencies[0].acknowledged").value(true));

        // 12:00 整：jia 已回执不缺失；yi 逾期进入待跟进，关键缺失清单为空
        ((ControllableClock) clock).setInstant(DEADLINE);
        mvc.perform(get("/api/incidents/{k}/notifications", "INC-NH-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdueCritical").isArray())
                .andExpect(jsonPath("$.followUp[0]").value("yi"));

        // yi 回执后降级放行
        mvc.perform(post("/api/incidents/{k}/notifications/ack", "INC-NH-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"agencyKey\":\"yi\","
                                + "\"signer\":\"wang\",\"content\":\"yi 回执\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedCount").value(2));
        mvc.perform(post("/api/incidents/{k}/status", "INC-NH-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTAINED"));
    }

    @Test
    void downgradeBlockedListsMissingCritical_thenRetry() throws Exception {
        escalateWithAgencies("INC-NH-2");
        ((ControllableClock) clock).setInstant(DEADLINE);
        // 降级受阻：409 + 缺失机构清单 [jia]
        mvc.perform(post("/api/incidents/{k}/status", "INC-NH-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.details[0]").value("jia"));

        // jia 补回执（普通机构 yi 仍逾期，但不阻止）
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T12:10:00Z"));
        mvc.perform(post("/api/incidents/{k}/notifications/ack", "INC-NH-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"agencyKey\":\"jia\","
                                + "\"signer\":\"zhang\",\"content\":\"已知悉\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/status", "INC-NH-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTAINED"));
    }

    @Test
    void ackIdempotencyAndErrors() throws Exception {
        escalateWithAgencies("INC-NH-3");
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T11:30:00Z"));
        String requestId = key();
        String body = "{\"requestId\":\"" + requestId + "\",\"agencyKey\":\"jia\","
                + "\"signer\":\"zhang\",\"content\":\"已知悉\"}";
        String first = mvc.perform(post("/api/incidents/{k}/notifications/ack", "INC-NH-3")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 同 requestId 重放原响应
        mvc.perform(post("/api/incidents/{k}/notifications/ack", "INC-NH-3")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(content().json(first));
        // 换机构 → 409
        mvc.perform(post("/api/incidents/{k}/notifications/ack", "INC-NH-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"agencyKey\":\"yi\","
                                + "\"signer\":\"zhang\",\"content\":\"已知悉\"}"))
                .andExpect(status().isConflict());
        // 换内容 → 409
        mvc.perform(post("/api/incidents/{k}/notifications/ack", "INC-NH-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"agencyKey\":\"jia\","
                                + "\"signer\":\"zhang\",\"content\":\"改了\"}"))
                .andExpect(status().isConflict());
        // 缺字段 → 400；未知机构 → 404；无通报事件查询 → 404
        mvc.perform(post("/api/incidents/{k}/notifications/ack", "INC-NH-3")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/incidents/{k}/notifications/ack", "INC-NH-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"agencyKey\":\"nobody\","
                                + "\"signer\":\"z\",\"content\":\"c\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/INC-404/notifications"))
                .andExpect(status().isNotFound());
    }
}
