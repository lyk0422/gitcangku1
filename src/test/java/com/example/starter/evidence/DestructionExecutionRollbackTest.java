package com.example.starter.evidence;

import com.example.starter.evidence.dto.DestructionOrderView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 执行重查回滚测试。
 * 正常 API 在 PENDING/APPROVED 期间冻结证物，无法通过交接/借出改动入列证物；
 * 因此这里用 JdbcTemplate 在带外模拟“并发事务已改动证物状态”，
 * 验证执行事务内重查发现任一件被改动时整单 409 回滚：销毁令保持 APPROVED，
 * 其余证物不被销毁，且失败不占 commandKey。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DestructionExecutionRollbackTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DestructionService destructionService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        MvcResult result = mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private void approve(String approver, String destructionKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        MvcResult result = mockMvc.perform(
                        post("/api/destruction-orders/{key}/approvals", destructionKey)
                                .header(ACTOR_HEADER, approver)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private String approvedOrder(String custodian, List<String> evidenceKeys) throws Exception {
        String destructionKey = uniqueKey("DST");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("destructionKey", destructionKey);
        body.put("evidenceKeys", evidenceKeys);
        body.put("legalBasis", "LAW-ART-9");
        body.put("destructionMethod", "INCINERATION");
        body.put("forceIncludeBroken", false);
        MvcResult created = mockMvc.perform(post("/api/destruction-orders")
                        .header(ACTOR_HEADER, custodian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);

        approve("approver-1", destructionKey);
        approve("approver-2", destructionKey);
        return destructionKey;
    }

    private MvcResult execute(String custodian, String destructionKey, String commandKey)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(
                        post("/api/destruction-orders/{key}/execution", destructionKey)
                                .header(ACTOR_HEADER, custodian)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    @Test
    void executeRollsBackWhenAnItemWasTamperedOutOfBand() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String destructionKey = approvedOrder("alice", List.of(ev1, ev2));

        // 带外模拟并发改动：把第一件证物改为 BORROWED 并补一笔 ACTIVE 借出。
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "UPDATE evidence SET status = ?, updated_at = ? WHERE evidence_key = ?",
                EvidenceStatus.BORROWED.name(), now, ev1);
        jdbcTemplate.update("""
                        INSERT INTO loan_record
                            (loan_key, evidence_key, custodian_id, borrower_id, purpose,
                             loan_at, due_at, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                uniqueKey("LOAN"), ev1, "alice", "borrower-x", "鉴定用",
                now, now.plusHours(2), LoanStatus.ACTIVE.name(), now);

        String commandKey = uniqueKey("CMD");
        MvcResult result = execute("alice", destructionKey, commandKey);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.get("message").asText()).contains(ev1);

        // 销毁令保持 APPROVED，执行时间为空
        DestructionOrderView detail = destructionService.detail(destructionKey);
        assertThat(detail.status()).isEqualTo(DestructionStatus.APPROVED);
        assertThat(detail.executedAt()).isNull();

        // 证物状态保持原样：ev1 仍 BORROWED，ev2 未被销毁
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM evidence WHERE evidence_key = ?", String.class, ev1))
                .isEqualTo("BORROWED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM evidence WHERE evidence_key = ?", String.class, ev2))
                .isEqualTo("SEALED");

        // 失败不占键：同一 commandKey 在证物恢复合格后可成功执行
        jdbcTemplate.update("""
                        UPDATE loan_record
                        SET status = ?, seal_passed = 1, return_note = ?, returned_at = ?
                        WHERE evidence_key = ? AND status = ?
                        """,
                LoanStatus.RETURNED.name(), "ok", now, ev1, LoanStatus.ACTIVE.name());
        jdbcTemplate.update(
                "UPDATE evidence SET status = ?, updated_at = ? WHERE evidence_key = ?",
                EvidenceStatus.SEALED.name(), now, ev1);

        MvcResult retry = execute("alice", destructionKey, commandKey);
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        assertThat(destructionService.detail(destructionKey).status())
                .isEqualTo(DestructionStatus.DESTROYED);
    }
}
