package com.example.starter.evidence;

import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageItemView;
import com.example.starter.evidence.dto.PackageView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 披露包历史快照真实 H2 数据库测试：直接向只追加历史表插入精确时间线（UTC/上海墙钟分列），
 * 验证按截至 UTC 时刻而非生成时当前状态重放，以及固化后新增迁移/复核/链记录不改写包。
 */
@SpringBootTest
class DisclosurePackageHistoryH2Test {

    // UTC 时刻；Shanghai 墙钟列统一 +8 小时写入。
    private static final LocalDateTime T00_UTC = LocalDateTime.parse("2026-09-20T00:00:00");
    private static final LocalDateTime T02_UTC = LocalDateTime.parse("2026-09-20T02:00:00");
    private static final LocalDateTime T03_UTC = LocalDateTime.parse("2026-09-20T03:00:00");
    private static final LocalDateTime T04_UTC = LocalDateTime.parse("2026-09-20T04:00:00");
    private static final LocalDateTime T05_UTC = LocalDateTime.parse("2026-09-20T05:00:00");
    private static final LocalDateTime T06_UTC = LocalDateTime.parse("2026-09-20T06:00:00");
    private static final LocalDateTime T07_UTC = LocalDateTime.parse("2026-09-20T07:00:00");
    private static final LocalDateTime T09_UTC = LocalDateTime.parse("2026-09-20T09:00:00");
    private static final LocalDateTime T10_UTC = LocalDateTime.parse("2026-09-20T10:00:00");
    private static final LocalDateTime T12_UTC = LocalDateTime.parse("2026-09-20T12:00:00");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DisclosurePackageService packageService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private LocalDateTime sh(LocalDateTime utc) {
        return utc.plusHours(8);
    }

    private void insertEvidence(String key, String caseKey, String custodian, EvidenceStatus status,
                                LocalDateTime createdAtUtc) {
        jdbc.update("""
                        INSERT INTO evidence
                            (evidence_key, case_key, category, seal_no, custodian_id, status, created_at, updated_at)
                        VALUES (?, ?, 'DOCUMENT', ?, ?, ?, ?, ?)
                        """,
                key, caseKey, "SEAL-" + key, custodian, status.name(),
                sh(createdAtUtc), sh(createdAtUtc));
    }

    private void insertTransfer(String evidenceKey, String from, String to, TransferStatus status,
                                LocalDateTime initiatedUtc, LocalDateTime decidedUtc) {
        jdbc.update("""
                        INSERT INTO transfer_record
                            (evidence_key, from_custodian, to_custodian, status, initiated_by, created_at, decided_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                evidenceKey, from, to, status.name(), from, sh(initiatedUtc),
                decidedUtc == null ? null : sh(decidedUtc));
    }

    private void insertLoan(String loanKey, String evidenceKey, String custodian, String borrower,
                            LocalDateTime loanAtUtc, LocalDateTime returnedUtc) {
        jdbc.update("""
                        INSERT INTO loan_record
                            (loan_key, evidence_key, custodian_id, borrower_id, purpose,
                             loan_at, due_at, status, seal_passed, return_note, returned_at, created_at)
                        VALUES (?, ?, ?, ?, '鉴定', ?, ?, ?, ?, ?, ?, ?)
                        """,
                loanKey, evidenceKey, custodian, borrower,
                loanAtUtc, loanAtUtc.plusHours(3),
                returnedUtc == null ? LoanStatus.ACTIVE.name() : LoanStatus.RETURNED.name(),
                returnedUtc == null ? null : 1,
                returnedUtc == null ? null : "归还完好",
                returnedUtc,
                sh(loanAtUtc));
    }

    private void insertInspection(String evidenceKey, String inspector, boolean passed,
                                  LocalDateTime atUtc) {
        jdbc.update("""
                        INSERT INTO seal_inspection (evidence_key, inspector_id, passed, note, created_at)
                        VALUES (?, ?, ?, '备注', ?)
                        """,
                evidenceKey, inspector, passed ? 1 : 0, sh(atUtc));
    }

    private StoredResponse create(String actor, String caseKey, List<String> keys,
                                 LocalDateTime asOfUtc) {
        return packageService.createPackage(actor, caseKey,
                new PackageCreateRequest(uniqueKey("CMD"), keys, asOfUtc), "hash-" + UUID.randomUUID());
    }

    @Test
    void replayByAsOfNotCurrentStateAndFreezeSnapshot() {
        String caseKey = uniqueKey("CASE");
        // A：02Z 接受交接 alice->bob；04Z 借出、06Z 归还；03Z 核验通过。
        String a = uniqueKey("EV-A");
        insertEvidence(a, caseKey, "bob", EvidenceStatus.SEALED, T00_UTC);
        insertTransfer(a, "alice", "bob", TransferStatus.ACCEPTED, T02_UTC, T02_UTC.plusMinutes(5));
        insertInspection(a, "bob", true, T03_UTC);
        insertLoan(uniqueKey("LOAN"), a, "bob", "dave", T04_UTC, T06_UTC);
        // B：当前封签已异常（09Z 失败核验），但 05Z 时封签正常。
        String b = uniqueKey("EV-B");
        insertEvidence(b, caseKey, "carol", EvidenceStatus.SEAL_BROKEN, T00_UTC);
        insertInspection(b, "carol", false, T09_UTC);

        // 05Z 建包：A 借出未归还 -> 422 ACTIVE_LOAN_AT_AS_OF；整包不生成。
        assertThatThrownBy(() -> create("officer", caseKey, List.of(a, b), T05_UTC))
                .isInstanceOfSatisfying(PackageReplayException.class, ex -> {
                    assertThat(ex.failureView().diagnoses()).hasSize(1);
                    assertThat(ex.failureView().diagnoses().get(0).evidenceKey()).isEqualTo(a);
                    assertThat(ex.failureView().diagnoses().get(0).code())
                            .isEqualTo(ItemDiagnosisCode.ACTIVE_LOAN_AT_AS_OF);
                });
        // 失败不留半成品：案件下无任何版本。
        assertThat(packageService.listPackages(caseKey)).isEmpty();

        // 07Z 建包：A 已归还、B 截至时刻封签正常（虽当前已异常）-> 成功。
        StoredResponse stored = create("officer", caseKey, List.of(b, a), T07_UTC);
        assertThat(stored.status()).isEqualTo(201);

        PackageView pkg = packageService.getLatestPackage(caseKey);
        assertThat(pkg.packageVersion()).isEqualTo(1);
        assertThat(pkg.status()).isEqualTo(PackageStatus.GENERATED);
        assertThat(pkg.items()).hasSize(2);
        // 规范化排序：a < b，件次与请求顺序无关。
        PackageItemView itemA = pkg.items().get(0);
        PackageItemView itemB = pkg.items().get(1);
        assertThat(itemA.evidenceKey()).isEqualTo(a);
        assertThat(itemA.position()).isZero();
        assertThat(itemA.custodianAtAsOf()).isEqualTo("bob");
        assertThat(itemA.chainVersion()).isEqualTo(3);
        assertThat(itemA.summary().acceptedTransferCount()).isEqualTo(1);
        assertThat(itemA.summary().returnedLoanCountUpToAsOf()).isEqualTo(1);
        assertThat(itemA.sealSnapshot().intactAtAsOf()).isTrue();
        // B 截至 07Z 封签正常：09Z 的失败核验不得计入。
        assertThat(itemB.evidenceKey()).isEqualTo(b);
        assertThat(itemB.custodianAtAsOf()).isEqualTo("carol");
        assertThat(itemB.sealSnapshot().intactAtAsOf()).isTrue();
        assertThat(itemB.sealSnapshot().inspectionsUpToAsOf()).isZero();

        // 固化后再迁移、复核失败、新增借出与链记录，包内容不得改变。
        insertTransfer(a, "bob", "erin", TransferStatus.ACCEPTED, T09_UTC, T09_UTC.plusMinutes(2));
        insertInspection(a, "erin", false, T10_UTC);
        insertLoan(uniqueKey("LOAN"), a, "erin", "frank", T10_UTC, null);
        jdbc.update("UPDATE evidence SET custodian_id = 'erin', status = ?, updated_at = ? WHERE evidence_key = ?",
                EvidenceStatus.BORROWED.name(), sh(T10_UTC), a);

        PackageView refetched = packageService.getPackage(caseKey, 1);
        assertThat(refetched.items()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(pkg.items());
        PackageItemView frozenA = packageService.getItemDiagnosis(caseKey, 1, a);
        assertThat(frozenA.custodianAtAsOf()).isEqualTo("bob");
        assertThat(frozenA.chainVersion()).isEqualTo(3);
        assertThat(frozenA.sealSnapshot().intactAtAsOf()).isTrue();
    }

    @Test
    void pendingAtAsOfEvenThoughAcceptedNowIsRejectedThenAcceptedAtLaterAsOf() {
        String caseKey = uniqueKey("CASE");
        String c = uniqueKey("EV-C");
        insertEvidence(c, caseKey, "bob", EvidenceStatus.SEALED, T00_UTC);
        // 03Z 发起、10Z 才接受：05Z 时待核验，12Z 时已完成。
        insertTransfer(c, "alice", "bob", TransferStatus.ACCEPTED, T03_UTC, T10_UTC);

        assertThatThrownBy(() -> create("officer", caseKey, List.of(c), T05_UTC))
                .isInstanceOfSatisfying(PackageReplayException.class, ex ->
                        assertThat(ex.failureView().diagnoses().get(0).code())
                                .isEqualTo(ItemDiagnosisCode.PENDING_TRANSFER_AT_AS_OF));

        StoredResponse stored = create("officer", caseKey, List.of(c), T12_UTC);
        assertThat(stored.status()).isEqualTo(201);
        PackageItemView item = packageService.getItemDiagnosis(caseKey, 1, c);
        assertThat(item.custodianAtAsOf()).isEqualTo("bob");
        assertThat(item.diagnosis()).isEqualTo(ItemDiagnosisCode.OK);
    }

    @Test
    void chainGapBeforeIntakeAndCustodianMismatchAreDistinct() {
        String caseKey = uniqueKey("CASE");
        String d = uniqueKey("EV-D");
        insertEvidence(d, caseKey, "bob", EvidenceStatus.SEALED, T04_UTC);

        // 截至时刻早于入库。
        assertThatThrownBy(() -> create("officer", caseKey, List.of(d), T02_UTC))
                .isInstanceOfSatisfying(PackageReplayException.class, ex ->
                        assertThat(ex.failureView().diagnoses().get(0).code())
                                .isEqualTo(ItemDiagnosisCode.CHAIN_GAP));

        // 已闭环交接链中第二条发起人与重放保管人不一致：alice->bob 接受后，下一条应由 bob 发起，
        // 实际由 alice 发起 -> 保管链缺口。
        insertTransfer(d, "alice", "bob", TransferStatus.ACCEPTED, T05_UTC, T05_UTC.plusMinutes(3));
        insertTransfer(d, "alice", "carol", TransferStatus.ACCEPTED, T06_UTC, T06_UTC.plusMinutes(3));
        assertThatThrownBy(() -> create("officer", caseKey, List.of(d), T07_UTC))
                .isInstanceOfSatisfying(PackageReplayException.class, ex ->
                        assertThat(ex.failureView().diagnoses().get(0).code())
                                .isEqualTo(ItemDiagnosisCode.CHAIN_GAP));
    }

    @Test
    void notInCaseAndMissingEvidenceAreDistinctFailuresAndRollBackTogether() {
        String caseKey = uniqueKey("CASE");
        String mine = uniqueKey("EV-MINE");
        String otherCase = uniqueKey("CASE-OTHER");
        String theirs = uniqueKey("EV-OTHER");
        insertEvidence(mine, caseKey, "alice", EvidenceStatus.SEALED, T00_UTC);
        insertEvidence(theirs, otherCase, "bob", EvidenceStatus.SEALED, T00_UTC);
        String missing = uniqueKey("EV-MISSING");

        // 混合：一件在案通过、一件跨案件、一件不存在；一次性返回多个可区分诊断。
        assertThatThrownBy(() ->
                        create("officer", caseKey, List.of(mine, theirs, missing), T12_UTC))
                .isInstanceOfSatisfying(PackageReplayException.class, ex -> {
                    assertThat(ex.failureView().diagnoses()).hasSize(2);
                    assertThat(ex.failureView().diagnoses())
                            .extracting(d -> d.evidenceKey() + "=" + d.code())
                            .containsExactlyInAnyOrder(
                                    theirs + "=" + ItemDiagnosisCode.NOT_IN_CASE,
                                    missing + "=" + ItemDiagnosisCode.EVIDENCE_NOT_FOUND);
                });
        assertThat(packageService.listPackages(caseKey)).isEmpty();
    }

    @Test
    void normalizedSetIsSortedAndFingerprintChangesWithVersion() {
        String caseKey = uniqueKey("CASE");
        String x = uniqueKey("EV-X");
        String y = uniqueKey("EV-Y");
        insertEvidence(x, caseKey, "alice", EvidenceStatus.SEALED, T00_UTC);
        insertEvidence(y, caseKey, "alice", EvidenceStatus.SEALED, T00_UTC);

        // 不同请求顺序、含重复键：规范化后同一集合应生成相同指纹的版本。
        create("officer", caseKey, List.of(y, x), T12_UTC);
        PackageView first = packageService.getLatestPackage(caseKey);
        String firstKey = first.packageKey();

        // 同规范化集合/同截至时刻/同操作者，仅版本号不同：指纹必须不同；新版本逐件快照仍按件次排序。
        StoredResponse v2 = packageService.updateItems("officer", caseKey,
                new com.example.starter.evidence.dto.PackageItemsUpdateRequest(
                        uniqueKey("CMD"), List.of(x, x, y), T12_UTC),
                "hash-" + UUID.randomUUID());
        assertThat(v2.status()).isEqualTo(200);
        PackageView second = packageService.getLatestPackage(caseKey);
        assertThat(second.packageVersion()).isEqualTo(2);
        // 同案件/同规范化集合/同截至时刻/同操作者，仅版本号不同：指纹必须不同，但逐件快照一致。
        assertThat(second.packageKey()).isNotEqualTo(firstKey);
        assertThat(second.items()).hasSize(2);
        assertThat(second.items().get(0).evidenceKey()).isEqualTo(x);
        assertThat(second.frozenSummary().items()).hasSize(2);
    }
}
