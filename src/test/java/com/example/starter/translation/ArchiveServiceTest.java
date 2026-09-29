package com.example.starter.translation;

import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.archive.ArchiveDtos;
import com.example.starter.translation.archive.ArchiveRepository;
import com.example.starter.translation.archive.ArchiveRows.ArchivePackageRow;
import com.example.starter.translation.archive.ArchiveService;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArchiveService 的纯 Mockito 单元测试：固定 UTC 时钟，
 * 覆盖主流程、失败分支、批量原子性与时间区间边界，不启动 Spring/数据库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchiveServiceTest {

    private static final long DOC_ID = 10L;
    private static final Instant FIXED = Instant.parse("2026-09-29T08:30:00.123Z");

    @Mock
    private TranslationRepository repository;
    @Mock
    private ArchiveRepository archiveRepository;

    private ArchiveService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
        service = new ArchiveService(repository, archiveRepository, objectMapper, clock);
        DocumentRow document = new DocumentRow(DOC_ID, List.of("en", "ja"), 5, 1, 0);
        when(repository.findDocumentForUpdate(DOC_ID)).thenReturn(Optional.of(document));
        when(repository.findDocument(DOC_ID)).thenReturn(Optional.of(document));
    }

    private String completeSnapshot(int publishedVersion) {
        return "{\"documentId\":" + DOC_ID + ",\"publishedVersion\":" + publishedVersion
                + ",\"termVersion\":0,\"targetLanguages\":[\"en\",\"ja\"],\"terms\":[],"
                + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"原文\",\"sourceVersion\":1,"
                + "\"translations\":["
                + "{\"language\":\"ja\",\"content\":\"こんにちは\",\"author\":\"carol\","
                + "\"translationVersion\":1,\"sourceVersion\":1,\"termVersion\":0,\"reviewer\":\"dave\"},"
                + "{\"language\":\"en\",\"content\":\"hello\",\"author\":\"alice\","
                + "\"translationVersion\":1,\"sourceVersion\":1,\"termVersion\":0,\"reviewer\":\"bob\"}]}]}";
    }

    private String incompleteSnapshotJaMissing(int publishedVersion) {
        return "{\"documentId\":" + DOC_ID + ",\"publishedVersion\":" + publishedVersion
                + ",\"termVersion\":0,\"targetLanguages\":[\"en\",\"ja\"],\"terms\":[],"
                + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"原文\",\"sourceVersion\":1,"
                + "\"translations\":["
                + "{\"language\":\"en\",\"content\":\"hello\",\"author\":\"alice\","
                + "\"translationVersion\":1,\"sourceVersion\":1,\"termVersion\":0,\"reviewer\":\"bob\"}]}]}";
    }

    private ArchivePackageRow activeRow(long archiveId, int version, String packageJson) {
        return new ArchivePackageRow(archiveId, DOC_ID, version, "ACTIVE", "archiver", FIXED,
                null, null, null, packageJson);
    }

    @Test
    @DisplayName("创建归档成功：固定 UTC 时刻写入，证据为规范化排序与实际数量")
    void createArchiveSuccess() {
        when(repository.findSnapshot(DOC_ID, 1)).thenReturn(Optional.of(completeSnapshot(1)));
        when(repository.findSnapshotCreatedAt(DOC_ID, 1))
                .thenReturn(Optional.of(Instant.parse("2026-09-29T08:00:00.000Z")));
        when(archiveRepository.findWithdrawal(DOC_ID, 1)).thenReturn(Optional.empty());
        when(archiveRepository.findArchiveByVersion(DOC_ID, 1)).thenReturn(Optional.empty());
        when(archiveRepository.insertArchivePackage(eq(DOC_ID), eq(1), eq("archiver"), eq(FIXED), any()))
                .thenReturn(77L);

        ArchiveDtos.ArchiveCreatedResponse response = service.createArchive(DOC_ID, "archiver",
                new ArchiveDtos.CreateArchiveRequest("req-1", 1));

        assertThat(response.archiveId()).isEqualTo(77L);
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.createdAt()).isEqualTo("2026-09-29T08:30:00.123Z");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(archiveRepository).insertArchivePackage(eq(DOC_ID), eq(1), eq("archiver"),
                eq(FIXED), jsonCaptor.capture());
        ArchiveDtos.ArchiveEvidence evidence = readEvidence(jsonCaptor.getValue());
        assertThat(evidence.targetLanguages()).containsExactly("en", "ja");
        assertThat(evidence.segments()).hasSize(1);
        assertThat(evidence.segments().get(0).translations()).extracting(ArchiveDtos.ArchivedTranslation::language)
                .containsExactly("en", "ja");
        assertThat(evidence.approvals()).hasSize(2);
        assertThat(evidence.releaseSummary().translationCount()).isEqualTo(2);
        assertThat(evidence.releaseSummary().requiredLanguageCount()).isEqualTo(2);
        assertThat(evidence.releaseSummary().releasedAt()).isEqualTo("2026-09-29T08:00:00Z");
    }

    @Test
    @DisplayName("创建归档失败：发布不存在 404、已撤回 422、已归档 409、缺段 422，均不落包")
    void createArchiveFailures() {
        // 发布不存在
        when(repository.findSnapshot(DOC_ID, 9)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createArchive(DOC_ID, "a",
                new ArchiveDtos.CreateArchiveRequest("r", 9)))
                .isInstanceOf(ApiException.class).satisfies(ex ->
                        assertThat(((ApiException) ex).status().value()).isEqualTo(404));
        verify(archiveRepository, never()).insertArchivePackage(anyLong(), anyInt(), any(), any(), any());

        // 已撤回
        when(repository.findSnapshot(DOC_ID, 1)).thenReturn(Optional.of(completeSnapshot(1)));
        when(archiveRepository.findWithdrawal(DOC_ID, 1)).thenReturn(Optional.of(
                new com.example.starter.translation.archive.ArchiveRows.WithdrawalRow(
                        DOC_ID, 1, "boss", "原因", FIXED)));
        assertThatThrownBy(() -> service.createArchive(DOC_ID, "a",
                new ArchiveDtos.CreateArchiveRequest("r", 1)))
                .isInstanceOf(ApiException.class).satisfies(ex ->
                        assertThat(((ApiException) ex).status().value()).isEqualTo(422));
        verify(archiveRepository, never()).insertArchivePackage(anyLong(), anyInt(), any(), any(), any());

        // 已存在归档包
        when(archiveRepository.findWithdrawal(DOC_ID, 1)).thenReturn(Optional.empty());
        when(archiveRepository.findArchiveByVersion(DOC_ID, 1))
                .thenReturn(Optional.of(activeRow(5L, 1, "{}")));
        assertThatThrownBy(() -> service.createArchive(DOC_ID, "a",
                new ArchiveDtos.CreateArchiveRequest("r", 1)))
                .isInstanceOf(ApiException.class).satisfies(ex ->
                        assertThat(((ApiException) ex).status().value()).isEqualTo(409));
        verify(archiveRepository, never()).insertArchivePackage(anyLong(), anyInt(), any(), any(), any());

        // 缺 ja 段译文
        when(repository.findSnapshot(DOC_ID, 1)).thenReturn(Optional.of(incompleteSnapshotJaMissing(1)));
        when(archiveRepository.findArchiveByVersion(DOC_ID, 1)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createArchive(DOC_ID, "a",
                new ArchiveDtos.CreateArchiveRequest("r", 1)))
                .isInstanceOf(ApiException.class).satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.status().value()).isEqualTo(422);
                    assertThat(apiException.getMessage()).contains("ja");
                });
        verify(archiveRepository, never()).insertArchivePackage(anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("批量归档原子性：任一版本不完整则全部不插入；既有包复用并计入 existingCount")
    void batchArchiveAtomicity() throws Exception {
        // 版本 1 完整、版本 2 缺段 → 整批失败，无任何插入
        when(repository.findSnapshot(DOC_ID, 1)).thenReturn(Optional.of(completeSnapshot(1)));
        when(repository.findSnapshot(DOC_ID, 2)).thenReturn(Optional.of(incompleteSnapshotJaMissing(2)));
        when(archiveRepository.findWithdrawal(anyLong(), anyInt())).thenReturn(Optional.empty());
        when(archiveRepository.findArchiveByVersion(DOC_ID, 1)).thenReturn(Optional.empty());
        when(archiveRepository.findArchiveByVersion(DOC_ID, 2)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.batchArchive(DOC_ID, "archiver",
                new ArchiveDtos.BatchArchiveRequest("r", List.of(1, 2))))
                .isInstanceOf(ApiException.class).satisfies(ex ->
                        assertThat(((ApiException) ex).status().value()).isEqualTo(422));
        verify(archiveRepository, never()).insertArchivePackage(anyLong(), anyInt(), any(), any(), any());

        // 版本 1 已有包、版本 2 完整：复用 1、插入 2
        String packageV1 = objectMapper.writeValueAsString(new ArchiveDtos.ArchiveEvidence(
                1, 0, List.of("en", "ja"), List.of(), List.of(), List.of(), null));
        when(repository.findSnapshot(DOC_ID, 2)).thenReturn(Optional.of(completeSnapshot(2)));
        when(archiveRepository.findArchiveByVersion(DOC_ID, 1))
                .thenReturn(Optional.of(activeRow(50L, 1, packageV1)));
        when(archiveRepository.findArchiveByVersion(DOC_ID, 2)).thenReturn(Optional.empty());
        when(repository.findSnapshotCreatedAt(DOC_ID, 2)).thenReturn(Optional.of(FIXED));
        when(archiveRepository.insertArchivePackage(eq(DOC_ID), eq(2), eq("archiver"), eq(FIXED), any()))
                .thenReturn(51L);

        ArchiveDtos.BatchArchiveResponse response = service.batchArchive(DOC_ID, "archiver",
                new ArchiveDtos.BatchArchiveRequest("r2", List.of(2, 1)));
        assertThat(response.requestedCount()).isEqualTo(2);
        assertThat(response.createdCount()).isEqualTo(1);
        assertThat(response.existingCount()).isEqualTo(1);
        assertThat(response.packages()).extracting(ArchiveDtos.ArchiveSummary::publishedVersion)
                .containsExactly(1, 2);
        verify(archiveRepository).insertArchivePackage(eq(DOC_ID), eq(2), eq("archiver"),
                eq(FIXED), any());
    }

    @Test
    @DisplayName("撤销归档：ACTIVE 返回 200；REVOKED 与缺失分别 409/404，且不重复更新")
    void revokeArchive() {
        when(archiveRepository.findArchiveById(DOC_ID, 77L))
                .thenReturn(Optional.of(activeRow(77L, 1, "{}")));
        when(archiveRepository.markArchiveRevoked(eq(77L), eq("auditor"), eq(FIXED), eq("原因")))
                .thenReturn(1);

        ArchiveDtos.RevokeArchiveResponse response = service.revokeArchive(DOC_ID, 77L, "auditor",
                new ArchiveDtos.RevokeArchiveRequest("r", "原因"));
        assertThat(response.status()).isEqualTo("REVOKED");
        assertThat(response.revokedAt()).isEqualTo("2026-09-29T08:30:00.123Z");

        ArchivePackageRow revoked = new ArchivePackageRow(77L, DOC_ID, 1, "REVOKED", "archiver", FIXED,
                "auditor", FIXED, "原因", "{}");
        when(archiveRepository.findArchiveById(DOC_ID, 77L)).thenReturn(Optional.of(revoked));
        assertThatThrownBy(() -> service.revokeArchive(DOC_ID, 77L, "auditor",
                new ArchiveDtos.RevokeArchiveRequest("r2", "再次")))
                .isInstanceOf(ApiException.class).satisfies(ex ->
                        assertThat(((ApiException) ex).status().value()).isEqualTo(409));
        verify(archiveRepository, never()).markArchiveRevoked(eq(78L), any(), any(), any());

        when(archiveRepository.findArchiveById(DOC_ID, 78L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.revokeArchive(DOC_ID, 78L, "auditor",
                new ArchiveDtos.RevokeArchiveRequest("r3", "x")))
                .isInstanceOf(ApiException.class).satisfies(ex ->
                        assertThat(((ApiException) ex).status().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("明细查询：REVOKED 抛 410 且消息带原因；ACTIVE 解析证据")
    void getArchive() {
        ArchivePackageRow revoked = new ArchivePackageRow(77L, DOC_ID, 1, "REVOKED", "archiver", FIXED,
                "auditor", FIXED, "合规原因", "{}");
        when(archiveRepository.findArchiveById(DOC_ID, 77L)).thenReturn(Optional.of(revoked));
        assertThatThrownBy(() -> service.getArchive(DOC_ID, 77L))
                .isInstanceOf(ApiException.class).satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.status().value()).isEqualTo(410);
                    assertThat(apiException.getMessage()).contains("合规原因");
                });

        String evidenceJson;
        try {
            evidenceJson = objectMapper.writeValueAsString(new ArchiveDtos.ArchiveEvidence(
                    1, 0, List.of("en"), List.of(), List.of(), List.of(), null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(archiveRepository.findArchiveById(DOC_ID, 78L))
                .thenReturn(Optional.of(activeRow(78L, 1, evidenceJson)));
        ArchiveDtos.ArchiveDetailResponse detail = service.getArchive(DOC_ID, 78L);
        assertThat(detail.status()).isEqualTo("ACTIVE");
        assertThat(detail.evidence().publishedVersion()).isEqualTo(1);
        assertThat(detail.revokedBy()).isNull();
        assertThat(detail.revokedReason()).isNull();
    }

    @Test
    @DisplayName("历史区间：from>=to 与非法时间 400；合法区间按毫秒截断传入仓储并回显")
    void historyWindowValidation() {
        assertThatThrownBy(() -> service.listArchives(DOC_ID, "2026-09-29T10:00:00Z", "2026-09-29T09:00:00Z"))
                .isInstanceOf(ApiException.class).satisfies(ex ->
                        assertThat(((ApiException) ex).status().value()).isEqualTo(400));
        assertThatThrownBy(() -> service.listArchives(DOC_ID, "nonsense", null))
                .isInstanceOf(ApiException.class).satisfies(ex ->
                        assertThat(((ApiException) ex).status().value()).isEqualTo(400));

        when(archiveRepository.listArchivesBetween(eq(DOC_ID),
                eq(Instant.parse("2026-09-29T08:00:00Z")), eq(Instant.parse("2026-09-29T09:00:00.123Z"))))
                .thenReturn(List.of(activeRow(77L, 1, "{}")));
        ArchiveDtos.ArchiveHistoryResponse response = service.listArchives(DOC_ID,
                "2026-09-29T08:00:00.000Z", "2026-09-29T09:00:00.123999Z");
        assertThat(response.from()).isEqualTo("2026-09-29T08:00:00Z");
        assertThat(response.to()).isEqualTo("2026-09-29T09:00:00.123Z");
        assertThat(response.totalCount()).isEqualTo(1);
        assertThat(response.activeCount()).isEqualTo(1);
        assertThat(response.revokedCount()).isZero();
        assertThat(response.items().get(0).revokedBy()).isNull();
    }

    @Test
    @DisplayName("术语退役：版本不存在 404、重复退役 409、成功写入固定时刻记录")
    void retireTerms() {
        when(repository.termVersionExists(DOC_ID, 9)).thenReturn(false);
        assertThatThrownBy(() -> service.retireTerms(DOC_ID, "boss",
                new ArchiveDtos.RetireTermsRequest("r", 9, "x")))
                .isInstanceOf(ApiException.class).satisfies(ex ->
                        assertThat(((ApiException) ex).status().value()).isEqualTo(404));

        when(repository.termVersionExists(DOC_ID, 1)).thenReturn(true);
        when(archiveRepository.findTermRetirement(DOC_ID, 1)).thenReturn(Optional.of(
                new com.example.starter.translation.archive.ArchiveRows.TermRetirementRow(
                        DOC_ID, 1, "boss", "已退役", FIXED)));
        assertThatThrownBy(() -> service.retireTerms(DOC_ID, "boss",
                new ArchiveDtos.RetireTermsRequest("r", 1, "x")))
                .isInstanceOf(ApiException.class).satisfies(ex ->
                        assertThat(((ApiException) ex).status().value()).isEqualTo(409));

        when(archiveRepository.findTermRetirement(DOC_ID, 1)).thenReturn(Optional.empty());
        ArchiveDtos.TermRetirementResponse response = service.retireTerms(DOC_ID, "boss",
                new ArchiveDtos.RetireTermsRequest("r2", 1, "术语废弃"));
        assertThat(response.termVersion()).isEqualTo(1);
        assertThat(response.retiredAt()).isEqualTo("2026-09-29T08:30:00.123Z");
        verify(archiveRepository).insertTermRetirement(any());
    }

    @Test
    @DisplayName("发布撤回：重复撤回 409；成功写入操作者、原因与 UTC 时刻")
    void withdrawRelease() {
        when(repository.findSnapshot(DOC_ID, 1)).thenReturn(Optional.of("{}"));
        when(archiveRepository.findWithdrawal(DOC_ID, 1)).thenReturn(Optional.empty());

        ArchiveDtos.WithdrawalResponse response = service.withdrawRelease(DOC_ID, 1, "boss",
                new ArchiveDtos.WithdrawReleaseRequest("r", "发布有误"));
        assertThat(response.withdrawnBy()).isEqualTo("boss");
        assertThat(response.reason()).isEqualTo("发布有误");
        assertThat(response.withdrawnAt()).isEqualTo("2026-09-29T08:30:00.123Z");

        when(archiveRepository.findWithdrawal(DOC_ID, 1)).thenReturn(Optional.of(
                new com.example.starter.translation.archive.ArchiveRows.WithdrawalRow(
                        DOC_ID, 1, "boss", "发布有误", FIXED)));
        assertThatThrownBy(() -> service.withdrawRelease(DOC_ID, 1, "boss",
                new ArchiveDtos.WithdrawReleaseRequest("r2", "再次")))
                .isInstanceOf(ApiException.class).satisfies(ex ->
                        assertThat(((ApiException) ex).status().value()).isEqualTo(409));
    }

    private ArchiveDtos.ArchiveEvidence readEvidence(String json) {
        try {
            return objectMapper.readValue(json, ArchiveDtos.ArchiveEvidence.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
