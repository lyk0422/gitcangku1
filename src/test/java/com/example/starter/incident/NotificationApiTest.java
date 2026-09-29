package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * 外部通报回执 HTTP 层测试：验证升级携带机构、回执提交与查询路由、JSON 结构、
 * 稳定排序、400/404/409 语义及 requestId 同键重放返回完全一致的响应。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class NotificationApiTest {

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");
    private static final String DEADLINE = "2026-09-29T12:00:00Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM incident_notification_receipts");
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

    private void setupEvent(String ik) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + ik + "\",\"severity\":\"S1\",\"summary\":\"s\","
                                + "\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        ((ControllableClock) clock).setInstant(T0.plus(1, ChronoUnit.HOURS));
        mvc.perform(post("/api/incidents/{k}/escalations/check", ik)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"agencies\":["
                                + "{\"agencyCode\":\"甲\",\"critical\":true,\"ackDeadline\":\""
                                + DEADLINE + "\"},"
                                + "{\"agencyCode\":\"乙\",\"critical\":false,\"ackDeadline\":\""
                                + DEADLINE + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.status").value("OPEN"));
    }

    @Test
    void notificationHttpFlow_andDowngradeBlocked() throws Exception {
        setupEvent("INC-NA-1");

        // 查询：稳定排序甲、乙，均缺失
        mvc.perform(get("/api/incidents/{k}/notifications", "INC-NA-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agencies.length()").value(2))
                .andExpect(jsonPath("$.agencies[0].agencyCode").value("甲"))
                .andExpect(jsonPath("$.agencies[0].critical").value(true))
                .andExpect(jsonPath("$.agencies[0].missing").value(true))
                .andExpect(jsonPath("$.agencies[0].overdue").value(false))
                .andExpect(jsonPath("$.agencies[1].agencyCode").value("乙"))
                .andExpect(jsonPath("$.missingCritical[0]").value("甲"));

        // 12:00 整降级受阻：返回缺失关键机构
        ((ControllableClock) clock).setInstant(Instant.parse(DEADLINE));
        mvc.perform(post("/api/incidents/{k}/status", "INC-NA-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.details[0]").value("甲"));

        // 甲补回执
        mvc.perform(post("/api/incidents/{k}/notifications/receipts", "INC-NA-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agencyCode\":\"甲\",\"requestId\":\"" + key()
                                + "\",\"signer\":\"甲值班长\",\"content\":\"已接管\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missing").value(false))
                .andExpect(jsonPath("$.signer").value("甲值班长"));

        // 重新降级成功（乙逾期仅待跟进）
        mvc.perform(post("/api/incidents/{k}/status", "INC-NA-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/incidents/{k}/notifications", "INC-NA-1"))
                .andExpect(jsonPath("$.overdueCritical").isArray())
                .andExpect(jsonPath("$.overdueCritical.length()").value(0))
                .andExpect(jsonPath("$.followUp[0]").value("乙"));
    }

    @Test
    void receiptReplaySameBody_conflictOnChange_404UnknownAgency() throws Exception {
        setupEvent("INC-NA-2");
        String rid = key();
        String body = "{\"agencyCode\":\"甲\",\"requestId\":\"" + rid
                + "\",\"signer\":\"甲值班长\",\"content\":\"内容A\"}";
        String first = mvc.perform(post("/api/incidents/{k}/notifications/receipts", "INC-NA-2")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // 同键原报文重放：响应完全一致
        mvc.perform(post("/api/incidents/{k}/notifications/receipts", "INC-NA-2")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(content().json(first));

        // 换机构 → 409
        mvc.perform(post("/api/incidents/{k}/notifications/receipts", "INC-NA-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agencyCode\":\"乙\",\"requestId\":\"" + rid
                                + "\",\"signer\":\"甲值班长\",\"content\":\"内容A\"}"))
                .andExpect(status().isConflict());
        // 改内容 → 409
        mvc.perform(post("/api/incidents/{k}/notifications/receipts", "INC-NA-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agencyCode\":\"甲\",\"requestId\":\"" + rid
                                + "\",\"signer\":\"甲值班长\",\"content\":\"内容B\"}"))
                .andExpect(status().isConflict());

        // 未知机构 → 404；缺字段 → 400；未知事件查询 → 404
        mvc.perform(post("/api/incidents/{k}/notifications/receipts", "INC-NA-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agencyCode\":\"丙\",\"requestId\":\"" + key()
                                + "\",\"signer\":\"s\",\"content\":\"c\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/incidents/{k}/notifications/receipts", "INC-NA-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agencyCode\":\"甲\",\"signer\":\"s\",\"content\":\"c\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/incidents/INC-404/notifications"))
                .andExpect(status().isNotFound());
    }
}
