package com.example.starter.disclosure;

import com.example.starter.evidence.EvidenceClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 证物披露包 API 测试（真实 H2 内存库，MODE=MySQL）：
 * 主流程、422 各失败分支（可区分原因码）、截至时刻历史重放而非生成时状态、
 * 冻结快照不变性、包版本、批量增删原子性、交付与撤销、查询与逐件诊断。
 * 每个方法使用独立案件与操作者；历史时间线钉定（交接/核验为上海墙时间、借出为 UTC）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DisclosureApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 确定性历史时间线（UTC）。 */
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 8, 0, 0);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 9, 1, 9, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 9, 1, 10, 0, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 9, 1, 11, 0, 0);
    private static final LocalDateTime T4 = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
    private static final LocalDateTime FUTURE = LocalDateTime.of(2026, 12, 31, 0, 0, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EvidenceClock evidenceClock;

    /** 每个测试方法独立案件与操作者，避免共享 H2 库相互干扰。 */
    private final String caseKey = uniqueKey("CASE");
    private final String actor = uniqueKey("actor");

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private static String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    /** UTC LocalDateTime 转 Asia/Shanghai 墙上 LocalDateTime（交接/核验/入库列按墙时间存储）。 */
    private LocalDateTime wall(LocalDateTime utc) {
        return LocalDateTime.ofInstant(utc.toInstant(ZoneOffset.UTC), SHANGHAI);
    }

    private void fixClock(LocalDateTime utc) {
        evidenceClock.setClock(Clock.fixed(utc.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    private int packageCount() {
        return jdbc.queryForList(
                "SELECT package_version FROM disclosure_package WHERE case_key = ?", caseKey)
                .size();
    }

    private int disclosureCommandCount() {
        return jdbc.queryForList(
                "SELECT command_key FROM command_log WHERE actor_id = ? AND operation LIKE 'DISCLOSURE%'",
                actor).size();
    }

    // ---------- 既有证物操作辅助 ----------

    private MvcResult intake(String custodian, String evidenceKey, String evidenceCaseKey)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", evidenceCaseKey);
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, custodian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult intake(String evidenceKey) throws Exception {
        return intake(actor, evidenceKey, caseKey);
    }

    private void initiate(String from, String evidenceKey, String toCustodian) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
        mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, from)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private void decide(String custodian, String evidenceKey, String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        mockMvc.perform(post("/api/evidence/{key}/transfers/{action}", evidenceKey, action)
                        .header(ACTOR_HEADER, custodian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private void inspect(String custodian, String evidenceKey, boolean passed, String note)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        body.put("note", note);
        mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
                        .header(ACTOR_HEADER, custodian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private void borrow(String custodian, String evidenceKey, String borrower,
                        LocalDateTime loanAt, LocalDateTime dueAt) throws Exception {
        fixClock(loanAt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", borrower);
        body.put("purpose", "鉴定用");
        body.put("dueAt", dueAt.toString());
        MvcResult result = mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, custodian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private void returnLoan(String custodian, String evidenceKey, boolean sealIntact,
                            LocalDateTime returnedAt) throws Exception {
        fixClock(returnedAt);
        String loanKey = jdbc.queryForObject(
                "SELECT loan_key FROM loan_record WHERE evidence_key = ? AND status = 'ACTIVE'",
                String.class, evidenceKey);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", loanKey);
        body.put("sealIntact", sealIntact);
        body.put("note", "归还核验");
        MvcResult result = mockMvc.perform(post("/api/evidence/{key}/loans/return", evidenceKey)
                        .header(ACTOR_HEADER, custodian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        // 归还附带的封条核验按墙时间写入，这里钉到归还 UTC 对应的墙时刻，使历史时间线确定。
        jdbc.update("""
                        UPDATE seal_inspection SET created_at = ?
                        WHERE id = (SELECT MAX(id) FROM seal_inspection WHERE evidence_key = ?)
                        """,
                wall(returnedAt), evidenceKey);
    }

    private void pinIntake(String evidenceKey, LocalDateTime utc) {
        jdbc.update("UPDATE evidence SET created_at = ?, updated_at = ? WHERE evidence_key = ?",
                wall(utc), wall(utc), evidenceKey);
    }

    private void pinTransfers(String evidenceKey, LocalDateTime createdAtUtc,
                              LocalDateTime decidedAtUtc) {
        jdbc.update("UPDATE transfer_record SET created_at = ?, decided_at = ? WHERE evidence_key = ?",
                wall(createdAtUtc),
                decidedAtUtc == null ? null : wall(decidedAtUtc),
                evidenceKey);
    }

    private void pinInspection(String evidenceKey, int ordinal, LocalDateTime createdAtUtc) {
        jdbc.update("""
                        UPDATE seal_inspection SET created_at = ?
                        WHERE id = (SELECT id FROM (
                            SELECT id FROM seal_inspection WHERE evidence_key = ? ORDER BY id LIMIT 1 OFFSET ?) t)
                        """,
                wall(createdAtUtc), evidenceKey, ordinal);
    }

    // ---------- 披露包操作辅助 ----------

    private MvcResult createPackage(String commandKey, LocalDateTime cutoff,
                                    List<String> evidenceKeys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("caseKey", caseKey);
        body.put("cutoffAt", cutoff.toString());
        body.put("evidenceKeys", evidenceKeys);
        return mockMvc.perform(post("/api/disclosure/packages")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createPackage(LocalDateTime cutoff, List<String> evidenceKeys)
            throws Exception {
        return createPackage(uniqueKey("PKGCMD"), cutoff, evidenceKeys);
    }

    private MvcResult amend(String packageKey, List<String> add, List<String> remove,
                            LocalDateTime cutoff) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("PKGCMD"));
        body.put("cutoffAt", cutoff.toString());
        body.put("addEvidenceKeys", add);
        body.put("removeEvidenceKeys", remove);
        return mockMvc.perform(post("/api/disclosure/packages/{key}/amend", packageKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult deliver(String packageKey, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(post("/api/disclosure/packages/{key}/deliver", packageKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult revoke(String packageKey, String commandKey, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/disclosure/packages/{key}/revoke", packageKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode getPackageJson(String packageKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/disclosure/packages/{key}", packageKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode createOk(LocalDateTime cutoff, List<String> keys) throws Exception {
        MvcResult result = createPackage(cutoff, keys);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString()).isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode errorBody(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ---------- 主流程 ----------

    @Test
    void createPackageFreezesOrderedItemsWithChainVersionAndSnapshot() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake(ev2);
        intake(ev1);
        pinIntake(ev1, T0);
        pinIntake(ev2, T0);

        // 乱序 + 重复传入，服务端必须规范化去重升序。
        JsonNode pkg = createOk(FUTURE, List.of(ev2, ev1, ev2));
        assertThat(pkg.get("packageVersion").asInt()).isEqualTo(1);
        assertThat(pkg.get("basedOnVersion").isNull()).isTrue();
        assertThat(pkg.get("status").asText()).isEqualTo("GENERATED");
        assertThat(pkg.get("evidenceCount").asInt()).isEqualTo(2);
        String packageKey = pkg.get("packageKey").asText();
        assertThat(packageKey).hasSize(64);

        JsonNode content = getPackageJson(packageKey);
        assertThat(content.get("items")).hasSize(2);
        List<String> sortedKeys = List.of(ev1, ev2).stream().sorted().toList();
        assertThat(content.get("items").get(0).get("evidenceKey").asText())
                .isEqualTo(sortedKeys.get(0));
        assertThat(content.get("items").get(1).get("evidenceKey").asText())
                .isEqualTo(sortedKeys.get(1));
        JsonNode item = content.get("items").get(0);
        assertThat(item.get("itemOrder").asInt()).isZero();
        assertThat(item.get("chainVersion").asText()).isEqualTo("T0-L0-I0");
        assertThat(item.get("custodianSnapshot").asText()).isEqualTo(actor);
        assertThat(item.get("sealSnapshot").asText()).isEqualTo("INTACT");
        assertThat(item.get("lastInspectionPassed").isNull()).isTrue();
    }

    @Test
    void createPackageReplaysAcceptedTransferIntoChainVersionAndPosition() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T0);
        initiate(actor, ev, "bob");
        decide("bob", ev, "accept");
        pinTransfers(ev, T1, T3);
        inspect("bob", ev, true, "例行核验");
        pinInspection(ev, 0, T3);

        // 截至 T4：交接已接受、核验已通过，位置推进为 bob，链版本 T1-L0-I1。
        JsonNode pkg = createOk(T4, List.of(ev));
        JsonNode item = getPackageJson(pkg.get("packageKey").asText()).get("items").get(0);
        assertThat(item.get("chainVersion").asText()).isEqualTo("T1-L0-I1");
        assertThat(item.get("custodianSnapshot").asText()).isEqualTo("bob");
        assertThat(item.get("lastInspectionPassed").asBoolean()).isTrue();
    }

    @Test
    void cancelledTransferBeforeCutoffKeepsCustodianAndChainContinuous() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T0);
        initiate(actor, ev, "bob");
        decide(actor, ev, "cancel");
        pinTransfers(ev, T1, T2);

        // 截至 T4：交接已取消，保管人位置保持为发起人，链连续。
        JsonNode pkg = createOk(T4, List.of(ev));
        JsonNode item = getPackageJson(pkg.get("packageKey").asText()).get("items").get(0);
        assertThat(item.get("chainVersion").asText()).isEqualTo("T1-L0-I0");
        assertThat(item.get("custodianSnapshot").asText()).isEqualTo(actor);
    }

    @Test
    void createPackageIdempotentReplayReturnsSamePackageAndSingleVersion() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T0);
        String commandKey = uniqueKey("PKGCMD");

        MvcResult first = createPackage(commandKey, FUTURE, List.of(ev));
        MvcResult replay = createPackage(commandKey, FUTURE, List.of(ev));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(packageCount()).isEqualTo(1);
    }

    @Test
    void createPackageSameCommandKeyDifferentParamsReturns409AndOccupiesNoVersion() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake(ev1);
        intake(ev2);
        pinIntake(ev1, T0);
        pinIntake(ev2, T0);
        String commandKey = uniqueKey("PKGCMD");

        assertThat(createPackage(commandKey, FUTURE, List.of(ev1)).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(createPackage(commandKey, FUTURE, List.of(ev2)).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(packageCount()).isEqualTo(1);
    }

    // ---------- 失败分支 ----------

    @Test
    void createPackageWithMissingEvidenceReturns404() throws Exception {
        MvcResult result = createPackage(FUTURE, List.of(uniqueKey("MISSING")));
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(packageCount()).isZero();
        assertThat(disclosureCommandCount()).isZero();
    }

    @Test
    void createPackageWithForeignCaseEvidenceReturns422WithReasonCode() throws Exception {
        String ev = uniqueKey("EV");
        intake(actor, ev, "OTHER-CASE-" + uniqueKey("X"));
        pinIntake(ev, T0);

        MvcResult result = createPackage(FUTURE, List.of(ev));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(errorBody(result).get("reasonCode").asText()).isEqualTo("FOREIGN_CASE");
        assertThat(packageCount()).isZero();
    }

    @Test
    void createPackageWithCutoffBeforeIntakeReturns422() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T3);

        MvcResult result = createPackage(T1, List.of(ev));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(errorBody(result).get("reasonCode").asText()).isEqualTo("INTAKE_AFTER_CUTOFF");
    }

    @Test
    void createPackageWithPendingTransferAtCutoffReturns422EvenIfAcceptedNow() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T0);
        initiate(actor, ev, "bob");
        decide("bob", ev, "accept");
        // 历史时间线：T1 发起，T3 接受；截至 T2 时交接尚未决定，尽管当前链显示 ACCEPTED。
        pinTransfers(ev, T1, T3);

        MvcResult result = createPackage(T2, List.of(ev));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(errorBody(result).get("reasonCode").asText())
                .isEqualTo("PENDING_TRANSFER_AT_CUTOFF");
        assertThat(packageCount()).isZero();
    }

    @Test
    void createPackageWithActiveLoanAtCutoffReturns422EvenIfReturnedNow() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T0);
        // T1 借出、T3 归还（loan 时间为可控 UTC 时钟）；截至 T2 时借出未归还，尽管当前已归还。
        borrow(actor, ev, "bob", T1, T1.plusHours(2));
        returnLoan(actor, ev, true, T3);

        MvcResult result = createPackage(T2, List.of(ev));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(errorBody(result).get("reasonCode").asText()).isEqualTo("ACTIVE_LOAN_AT_CUTOFF");

        // 同一证物截至 T4（已归还）即可成功，链版本记录这次借出与归还核验。
        JsonNode pkg = createOk(T4, List.of(ev));
        JsonNode item = getPackageJson(pkg.get("packageKey").asText()).get("items").get(0);
        assertThat(item.get("chainVersion").asText()).isEqualTo("T0-L1-I1");
    }

    @Test
    void createPackageWithBrokenSealAtCutoffReturns422() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T0);
        inspect(actor, ev, false, "封条破损");
        pinInspection(ev, 0, T3);

        MvcResult result = createPackage(T4, List.of(ev));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(errorBody(result).get("reasonCode").asText()).isEqualTo("SEAL_BROKEN_AT_CUTOFF");
    }

    @Test
    void brokenSealIsTerminalSoLaterPassingInspectionStillFailsAtLaterCutoff() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T0);
        inspect(actor, ev, false, "封条破损");
        pinInspection(ev, 0, T2);
        // 破损后再通过复核也不能恢复封签（终态语义）。
        inspect(actor, ev, true, "试图恢复");
        pinInspection(ev, 1, T3);

        MvcResult result = createPackage(T4, List.of(ev));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(errorBody(result).get("reasonCode").asText()).isEqualTo("SEAL_BROKEN_AT_CUTOFF");
    }

    @Test
    void createPackageUsesCutoffStateSoBrokenNowButIntactBeforeCutoffSucceeds() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T0);
        // 当前证物已 SEAL_BROKEN（终态），但失败核验发生在 T3；截至 T2 封签正常。
        inspect(actor, ev, false, "后来破损");
        pinInspection(ev, 0, T3);

        JsonNode pkg = createOk(T2, List.of(ev));
        JsonNode item = getPackageJson(pkg.get("packageKey").asText()).get("items").get(0);
        assertThat(item.get("sealSnapshot").asText()).isEqualTo("INTACT");
        assertThat(item.get("inspectionCount").asInt()).isZero();
    }

    @Test
    void createPackageWithCustodyGapAtCutoffReturns422() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T0);
        // 真实链：actor -> bob 已接受，位置推进到 bob。
        initiate(actor, ev, "bob");
        decide("bob", ev, "accept");
        pinTransfers(ev, T1, T1.plusMinutes(30));
        // 注入一条不连续的历史交接（from=dave 与位置 bob 不连续），钉在截至时刻前。
        jdbc.update("""
                        INSERT INTO transfer_record
                            (evidence_key, from_custodian, to_custodian, status, initiated_by,
                             created_at, decided_at)
                        VALUES (?, 'dave', 'erin', 'ACCEPTED', 'dave', ?, ?)
                        """,
                ev, wall(T2), wall(T2.plusMinutes(30)));

        MvcResult result = createPackage(T4, List.of(ev));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(errorBody(result).get("reasonCode").asText()).isEqualTo("CUSTODY_GAP_AT_CUTOFF");
    }

    @Test
    void createPackageRollsBackWholeBatchWhenAnyItemFails() throws Exception {
        String good = uniqueKey("EV");
        String broken = uniqueKey("EV");
        intake(good);
        intake(broken);
        pinIntake(good, T0);
        pinIntake(broken, T0);
        inspect(actor, broken, false, "破损");
        pinInspection(broken, 0, T3);

        // 整包含一件截至时刻封签异常证物：整包不生成，不留半成品。
        MvcResult result = createPackage(T4, List.of(good, broken));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(packageCount()).isZero();
        assertThat(jdbc.queryForList(
                "SELECT i.id FROM disclosure_package_item i JOIN disclosure_package p ON i.package_key = p.package_key WHERE p.case_key = ?",
                caseKey)).isEmpty();
        assertThat(disclosureCommandCount()).isZero();
    }

    @Test
    void createPackageWithEmptyEvidenceListReturns400() throws Exception {
        MvcResult result = createPackage(uniqueKey("PKGCMD"), FUTURE, List.of());
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    // ---------- 冻结不变性 ----------

    @Test
    void frozenPackageIsNotChangedByLaterTransferInspectionOrLoan() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T0);
        initiate(actor, ev, "bob");
        decide("bob", ev, "accept");
        pinTransfers(ev, T1, T1.plusMinutes(30));
        inspect("bob", ev, true, "建包前核验");
        pinInspection(ev, 0, T2);

        JsonNode created = createOk(T4, List.of(ev));
        String packageKey = created.get("packageKey").asText();
        MvcResult summaryBefore = mockMvc.perform(
                get("/api/disclosure/packages/{key}/summary", packageKey)).andReturn();
        assertThat(summaryBefore.getResponse().getStatus()).isEqualTo(200);
        String summaryJsonBefore = summaryBefore.getResponse().getContentAsString();

        // 建包后再迁移、复核、借出并归还（这些都发生在冻结之后）。
        initiate("bob", ev, "carol");
        decide("carol", ev, "accept");
        inspect("carol", ev, true, "建包后核验");
        LocalDateTime loanAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1);
        borrow("carol", ev, "dave", loanAt, loanAt.plusHours(1));
        returnLoan("carol", ev, true, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(2));

        JsonNode after = getPackageJson(packageKey);
        assertThat(after.get("pkg").get("status").asText()).isEqualTo("GENERATED");
        JsonNode frozenItem = after.get("items").get(0);
        assertThat(frozenItem.get("chainVersion").asText()).isEqualTo("T1-L0-I1");
        assertThat(frozenItem.get("custodianSnapshot").asText()).isEqualTo("bob");
        MvcResult summaryAfter = mockMvc.perform(
                get("/api/disclosure/packages/{key}/summary", packageKey)).andReturn();
        // 冻结摘要原样不变。
        assertThat(summaryAfter.getResponse().getContentAsString()).isEqualTo(summaryJsonBefore);
    }

    // ---------- 增删修订与版本 ----------

    @Test
    void amendAddsAndRemovesEvidenceProducingHigherVersionsWithoutChangingOlder() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        intake(ev1);
        intake(ev2);
        intake(ev3);
        pinIntake(ev1, T0);
        pinIntake(ev2, T0);
        pinIntake(ev3, T0);

        JsonNode v1 = createOk(FUTURE, List.of(ev1));
        String key1 = v1.get("packageKey").asText();

        MvcResult addResult = amend(key1, List.of(ev2), List.of(), FUTURE);
        assertThat(addResult.getResponse().getStatus()).isEqualTo(201);
        JsonNode v2 = objectMapper.readTree(addResult.getResponse().getContentAsString());
        assertThat(v2.get("packageVersion").asInt()).isEqualTo(2);
        assertThat(v2.get("basedOnVersion").asInt()).isEqualTo(1);
        String key2 = v2.get("packageKey").asText();
        assertThat(getPackageJson(key2).get("items")).hasSize(2);

        MvcResult removeResult = amend(key2, List.of(ev3), List.of(ev1), FUTURE);
        assertThat(removeResult.getResponse().getStatus()).isEqualTo(201);
        JsonNode v3 = objectMapper.readTree(removeResult.getResponse().getContentAsString());
        assertThat(v3.get("packageVersion").asInt()).isEqualTo(3);
        assertThat(v3.get("basedOnVersion").asInt()).isEqualTo(2);
        JsonNode v3Items = getPackageJson(v3.get("packageKey").asText()).get("items");
        List<String> actualKeys = new java.util.ArrayList<>();
        v3Items.forEach(item -> actualKeys.add(item.get("evidenceKey").asText()));
        List<String> expectedFinal = List.of(ev2, ev3).stream().sorted().toList();
        assertThat(actualKeys).isEqualTo(expectedFinal);

        // 旧版本包内容不被修订改变。
        assertThat(getPackageJson(key1).get("items")).hasSize(1);
        assertThat(getPackageJson(key2).get("items")).hasSize(2);

        // 案件版本列表按版本顺序返回三版。
        MvcResult list = mockMvc.perform(get("/api/disclosure/cases/{caseKey}/packages", caseKey))
                .andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        JsonNode versions = objectMapper.readTree(list.getResponse().getContentAsString());
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).get("packageVersion").asInt()).isEqualTo(1);
        assertThat(versions.get(2).get("packageVersion").asInt()).isEqualTo(3);
    }

    @Test
    void amendFailureLeavesExistingVersionsUntouchedAndDoesNotConsumeVersionNumber() throws Exception {
        String good = uniqueKey("EV");
        String broken = uniqueKey("EV");
        intake(good);
        intake(broken);
        pinIntake(good, T0);
        pinIntake(broken, T0);
        inspect(actor, broken, false, "破损");
        pinInspection(broken, 0, T3);

        JsonNode v1 = createOk(T2, List.of(good));
        String key1 = v1.get("packageKey").asText();

        // 增入一件截至 T4 封签异常的证物：整包校验失败，不生成 v2，v1 不变。
        MvcResult failed = amend(key1, List.of(broken), List.of(), T4);
        assertThat(failed.getResponse().getStatus()).isEqualTo(422);
        assertThat(errorBody(failed).get("reasonCode").asText()).isEqualTo("SEAL_BROKEN_AT_CUTOFF");
        assertThat(packageCount()).isEqualTo(1);
        assertThat(getPackageJson(key1).get("items")).hasSize(1);

        // 失败不占版本：下一次成功修订（无增无删，同集合重新固化）为 v2。
        MvcResult retry = amend(key1, List.of(), List.of(), T2);
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(objectMapper.readTree(retry.getResponse().getContentAsString())
                .get("packageVersion").asInt()).isEqualTo(2);
    }

    @Test
    void amendRejectsDuplicateAddNonMemberRemoveAndEmptyFinalSetWith400() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake(ev1);
        intake(ev2);
        pinIntake(ev1, T0);
        pinIntake(ev2, T0);
        String key1 = createOk(FUTURE, List.of(ev1)).get("packageKey").asText();

        assertThat(amend(key1, List.of(ev1), List.of(), FUTURE).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(amend(key1, List.of(), List.of(ev2), FUTURE).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(amend(key1, List.of(), List.of(ev1), FUTURE).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(packageCount()).isEqualTo(1);
    }

    @Test
    void amendRevokedPackageReturns409() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T0);
        String key1 = createOk(FUTURE, List.of(ev)).get("packageKey").asText();
        assertThat(revoke(key1, uniqueKey("PKGCMD"), "信息有误").getResponse().getStatus())
                .isEqualTo(200);

        assertThat(amend(key1, List.of(), List.of(), FUTURE).getResponse().getStatus())
                .isEqualTo(409);
    }

    // ---------- 交付与撤销 ----------

    @Test
    void deliveredPackageCannotBeRevokedButHigherRevisionCanBeCreated() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T0);
        String key1 = createOk(FUTURE, List.of(ev)).get("packageKey").asText();

        String deliverCmd = uniqueKey("PKGCMD");
        assertThat(deliver(key1, deliverCmd).getResponse().getStatus()).isEqualTo(200);
        assertThat(getPackageJson(key1).get("pkg").get("status").asText()).isEqualTo("DELIVERED");

        // 已交付不可撤销。
        assertThat(revoke(key1, uniqueKey("PKGCMD"), "想撤销").getResponse().getStatus())
                .isEqualTo(409);
        // 重复交付 409。
        assertThat(deliver(key1, uniqueKey("PKGCMD")).getResponse().getStatus()).isEqualTo(409);
        // 交付命令同键重放返回首次结果。
        MvcResult replay = deliver(key1, deliverCmd);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("DELIVERED");

        // 发现问题可对已交付包创建更高修订包。
        MvcResult higher = amend(key1, List.of(), List.of(), FUTURE);
        assertThat(higher.getResponse().getStatus()).isEqualTo(201);
        JsonNode v2 = objectMapper.readTree(higher.getResponse().getContentAsString());
        assertThat(v2.get("packageVersion").asInt()).isEqualTo(2);
        assertThat(v2.get("basedOnVersion").asInt()).isEqualTo(1);
    }

    @Test
    void revokeOnlyGeneratedPackageWritesReasonAndExposesRecord() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T0);
        String key1 = createOk(FUTURE, List.of(ev)).get("packageKey").asText();

        String revokeCmd = uniqueKey("PKGCMD");
        MvcResult revoked = revoke(key1, revokeCmd, "证物信息需要更正");
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(revoked.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("REVOKED");
        assertThat(body.get("revokeReason").asText()).isEqualTo("证物信息需要更正");
        assertThat(body.get("revokedBy").asText()).isEqualTo(actor);

        MvcResult record = mockMvc.perform(
                get("/api/disclosure/packages/{key}/revocation", key1)).andReturn();
        assertThat(record.getResponse().getStatus()).isEqualTo(200);
        JsonNode recordBody = objectMapper.readTree(record.getResponse().getContentAsString());
        assertThat(recordBody.get("revokeReason").asText()).isEqualTo("证物信息需要更正");
        assertThat(recordBody.get("status").asText()).isEqualTo("REVOKED");

        // 已撤销不能再次撤销。
        assertThat(revoke(key1, uniqueKey("PKGCMD"), "再次撤销").getResponse().getStatus())
                .isEqualTo(409);
        // 撤销命令同键改参 409、同参重放返回首次结果。
        MvcResult replay = revoke(key1, revokeCmd, "证物信息需要更正");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("REVOKED");
    }

    @Test
    void revocationOfNonRevokedPackageReturns404() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T0);
        String key1 = createOk(FUTURE, List.of(ev)).get("packageKey").asText();
        MvcResult result = mockMvc.perform(
                get("/api/disclosure/packages/{key}/revocation", key1)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void operateOnMissingPackageReturns404() throws Exception {
        String missing = uniqueKey("MISSING-PKG");
        assertThat(mockMvc.perform(get("/api/disclosure/packages/{key}", missing)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(deliver(missing, uniqueKey("PKGCMD")).getResponse().getStatus()).isEqualTo(404);
        assertThat(revoke(missing, uniqueKey("PKGCMD"), "x").getResponse().getStatus())
                .isEqualTo(404);
    }

    // ---------- 逐件链诊断 ----------

    @Test
    void diagnoseItemReportsCutoffReasonAndFrozenSnapshot() throws Exception {
        String ev = uniqueKey("EV");
        intake(ev);
        pinIntake(ev, T0);
        borrow(actor, ev, "bob", T1, T1.plusHours(2));
        returnLoan(actor, ev, true, T3);

        // 包截至 T4（借出已归还）成功冻结。
        String packageKey = createOk(T4, List.of(ev)).get("packageKey").asText();

        // 用 T2 覆盖诊断：尽管当前已归还，截至 T2 仍借出未归还。
        MvcResult diagnosis = mockMvc.perform(get(
                        "/api/disclosure/packages/{key}/items/{ev}/diagnosis", packageKey, ev)
                        .param("cutoffAt", T2.toString()))
                .andReturn();
        assertThat(diagnosis.getResponse().getStatus()).isEqualTo(200);
        JsonNode diag = objectMapper.readTree(diagnosis.getResponse().getContentAsString());
        assertThat(diag.get("ok").asBoolean()).isFalse();
        assertThat(diag.get("reasonCode").asText()).isEqualTo("ACTIVE_LOAN_AT_CUTOFF");
        assertThat(diag.get("frozenSnapshot").get("sealSnapshot").asText()).isEqualTo("INTACT");

        // 默认按包自身 cutoff 诊断则通过。
        MvcResult defaultDiagnosis = mockMvc.perform(get(
                "/api/disclosure/packages/{key}/items/{ev}/diagnosis", packageKey, ev)).andReturn();
        JsonNode okDiag = objectMapper.readTree(defaultDiagnosis.getResponse().getContentAsString());
        assertThat(okDiag.get("ok").asBoolean()).isTrue();
        assertThat(okDiag.get("chainVersion").asText()).isEqualTo("T0-L1-I1");
    }
}
