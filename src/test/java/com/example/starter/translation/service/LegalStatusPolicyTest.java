package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.SignatureRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 法律效力发布条件策略与签署固化时间的纯单元测试，不启动 Spring、不依赖数据库。
 */
class LegalStatusPolicyTest {

    private static final SegmentRow SEGMENT = new SegmentRow("s1", "原文", 2);
    private static final TranslationRow TRANSLATION =
            new TranslationRow("s1", "en", "译文", "alice", 2, 4, 0);

    private static SignatureRow signature(String signer, int version) {
        return new SignatureRow(1, "s1", "en", signer, version,
                OffsetDateTime.parse("2026-09-30T12:00:00+08:00"));
    }

    @Test
    @DisplayName("INFORMATIONAL：无批准、批准版本不匹配均 422；当前版本有效批准通过")
    void informationalApprovalRules() {
        assertThatThrownBy(() -> LegalStatusPolicy.verifyInformationalApproval(
                SEGMENT, "en", TRANSLATION, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(422))
                .hasMessageContaining("缺少批准");

        ApprovalRow stale = new ApprovalRow("s1", "en", "bob", 1, 3);
        assertThatThrownBy(() -> LegalStatusPolicy.verifyInformationalApproval(
                SEGMENT, "en", TRANSLATION, stale))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("批准已失效");

        ApprovalRow valid = new ApprovalRow("s1", "en", "bob", 2, 4);
        LegalStatusPolicy.verifyInformationalApproval(SEGMENT, "en", TRANSLATION, valid);
    }

    @Test
    @DisplayName("BINDING：无签署、仅一名签署人、同人重复（去重后一人）均 422；两名不同签署人通过")
    void bindingSignatureRules() {
        assertThatThrownBy(() -> LegalStatusPolicy.verifyBindingSignatures(
                SEGMENT, "en", TRANSLATION, List.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("缺少签署");

        assertThatThrownBy(() -> LegalStatusPolicy.verifyBindingSignatures(
                SEGMENT, "en", TRANSLATION, List.of(signature("jia", 4))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("仅有 1 名不同签署人");

        // 同一签署人两条当前版本记录（正常由唯一约束拦截，这里验证策略按不同签署人计数）
        List<SignatureRow> sameSignerTwice = List.of(signature("jia", 4), signature("jia", 4));
        assertThatThrownBy(() -> LegalStatusPolicy.verifyBindingSignatures(
                SEGMENT, "en", TRANSLATION, sameSignerTwice))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("仅有 1 名不同签署人");

        LegalStatusPolicy.verifyBindingSignatures(SEGMENT, "en", TRANSLATION,
                List.of(signature("jia", 4), signature("yi", 4)));
    }

    @Test
    @DisplayName("BINDING：V3 的两份签署在译文修订为 V4 后不计入；一名 V4 加两份 V3 仍 422")
    void bindingSignaturesMustMatchCurrentVersion() {
        List<SignatureRow> v3Signatures = List.of(signature("jia", 3), signature("yi", 3));
        assertThatThrownBy(() -> LegalStatusPolicy.verifyBindingSignatures(
                SEGMENT, "en", TRANSLATION, v3Signatures))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("缺少签署");

        List<SignatureRow> mixed = List.of(signature("jia", 3), signature("yi", 3), signature("bing", 4));
        assertThatThrownBy(() -> LegalStatusPolicy.verifyBindingSignatures(
                SEGMENT, "en", TRANSLATION, mixed))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("仅有 1 名不同签署人");
    }

    @Test
    @DisplayName("签署时间使用可注入时钟：固定时钟下固化 Asia/Shanghai 偏移时间；重复签署由唯一约束转 422")
    void signFreezesTimeFromClock() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-30T04:00:00Z"),
                ClockConfiguration.BUSINESS_ZONE);
        com.example.starter.translation.repo.TranslationRepository repository =
                mock(com.example.starter.translation.repo.TranslationRepository.class);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        TranslationService service = new TranslationService(repository, objectMapper, fixedClock);

        when(repository.findDocumentForUpdate(1L)).thenReturn(java.util.Optional.of(
                new com.example.starter.translation.domain.Rows.DocumentRow(
                        1, List.of("en"), 3, 0, 0)));
        when(repository.findSegment(1L, "s1")).thenReturn(java.util.Optional.of(SEGMENT));
        when(repository.findTranslation(1L, "s1", "en")).thenReturn(java.util.Optional.of(TRANSLATION));
        ArgumentCaptor<SignatureRow> captor = ArgumentCaptor.forClass(SignatureRow.class);

        ApiDtos.SignResponse response = service.signTranslation(1L, "s1", "en", "jia",
                new ApiDtos.SignTranslationRequest("req-1", 4));

        assertThat(response.signedAt()).isEqualTo("2026-09-30T12:00:00+08:00");
        verify(repository).insertSignature(captor.capture());
        SignatureRow saved = captor.getValue();
        assertThat(saved.signedAt()).isEqualTo(OffsetDateTime.parse("2026-09-30T12:00+08:00"));
        assertThat(saved.signer()).isEqualTo("jia");
        assertThat(saved.translationVersion()).isEqualTo(4);
        assertThat(saved.language()).isEqualTo("en");
        verify(repository).insertSignature(any(SignatureRow.class));

        // 唯一约束冲突 → 422 业务异常
        doThrow(new DuplicateKeyException("duplicate")).when(repository)
                .insertSignature(any(SignatureRow.class));
        assertThatThrownBy(() -> service.signTranslation(1L, "s1", "en", "jia",
                new ApiDtos.SignTranslationRequest("req-2", 4)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不能重复签署");
    }
}
