package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TranslationService 法律效力分支的纯单元测试：Mock 仓储，固定时钟验证时间固化与拒绝分支。
 */
class TranslationServiceUnitTest {

    private static final Instant FIXED = Instant.parse("2026-09-30T04:00:00Z");

    private TranslationRepository repository;
    private TranslationService service;

    @BeforeEach
    void setUp() {
        repository = mock(TranslationRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new TranslationService(repository, objectMapper,
                Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private void mockDocument(int draftVersion, int publishedVersion) {
        when(repository.findDocumentForUpdate(1L)).thenReturn(Optional.of(
                new Rows.DocumentRow(1L, List.of("en"), draftVersion, publishedVersion, 0)));
    }

    private void mockCurrentTranslation(int translationVersion, int sourceVersion) {
        when(repository.findSegment(1L, "s1"))
                .thenReturn(Optional.of(new Rows.SegmentRow("s1", "原文", sourceVersion)));
        when(repository.findTranslation(1L, "s1", "en"))
                .thenReturn(Optional.of(new Rows.TranslationRow("s1", "en", "v3", "alice",
                        sourceVersion, translationVersion, 0)));
    }

    @Test
    @DisplayName("签署成功：固化语种、译文版本、源文版本与固定时钟的签署时间")
    void signSuccessFreezesVersionAndTime() {
        mockDocument(2, 0);
        mockCurrentTranslation(3, 1);
        ApiDtos.SignTranslationRequest request = new ApiDtos.SignTranslationRequest("req-1", 3);

        ApiDtos.SignatureResponse response = service.signTranslation(1L, "s1", "EN", "jia", request);

        assertThat(response.signer()).isEqualTo("jia");
        assertThat(response.language()).isEqualTo("en");
        assertThat(response.translationVersion()).isEqualTo(3);
        assertThat(response.sourceVersion()).isEqualTo(1);
        assertThat(response.signedAt()).isEqualTo(FIXED);
        ArgumentCaptor<Rows.BindingSignatureRow> captor = ArgumentCaptor.forClass(Rows.BindingSignatureRow.class);
        verify(repository).insertSignature(eq(1L), captor.capture());
        assertThat(captor.getValue().language()).isEqualTo("en");
        assertThat(captor.getValue().signedAt()).isEqualTo(FIXED);
    }

    @Test
    @DisplayName("签署版本已被修订（与当前译文版本不符）：422 且不落签署记录")
    void signRevisedVersionRejected() {
        mockDocument(2, 0);
        mockCurrentTranslation(4, 1);
        ApiDtos.SignTranslationRequest request = new ApiDtos.SignTranslationRequest("req-1", 3);

        assertThatThrownBy(() -> service.signTranslation(1L, "s1", "en", "jia", request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status().value()).isEqualTo(422))
                .hasMessageContaining("已被修订");
        verify(repository, never()).insertSignature(anyLong(), any());
    }

    @Test
    @DisplayName("译文基于旧源文版本：签署 422")
    void signStaleSourceRejected() {
        mockDocument(5, 0);
        mockCurrentTranslation(3, 1);
        // 当前段落源文版本已到 2
        when(repository.findSegment(1L, "s1"))
                .thenReturn(Optional.of(new Rows.SegmentRow("s1", "新原文", 2)));
        ApiDtos.SignTranslationRequest request = new ApiDtos.SignTranslationRequest("req-1", 3);

        assertThatThrownBy(() -> service.signTranslation(1L, "s1", "en", "jia", request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status().value()).isEqualTo(422))
                .hasMessageContaining("译文待更新");
    }

    @Test
    @DisplayName("同人重复签：唯一约束冲突转 422，不吞掉为成功")
    void duplicateSignerConvertedTo422() {
        mockDocument(2, 0);
        mockCurrentTranslation(3, 1);
        when(repository.insertSignature(anyLong(), any())).thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> service.signTranslation(1L, "s1", "en", "jia",
                new ApiDtos.SignTranslationRequest("req-1", 3)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status().value()).isEqualTo(422))
                .hasMessageContaining("重复签署");
    }

    private ApiDtos.PublishRequest bindingPublish(int draftVersion, int publishedVersion) {
        return new ApiDtos.PublishRequest("pub-1", draftVersion, publishedVersion, "BINDING", "en");
    }

    private void mockPublishData(int translationVersion, int sourceVersion,
                                 List<Rows.BindingSignatureRow> signatures) {
        when(repository.listSegments(1L)).thenReturn(List.of(new Rows.SegmentRow("s1", "原文", sourceVersion)));
        when(repository.listTranslations(1L)).thenReturn(List.of(new Rows.TranslationRow(
                "s1", "en", "v3", "alice", sourceVersion, translationVersion, 0)));
        when(repository.listSignatures(1L)).thenReturn(signatures);
    }

    @Test
    @DisplayName("BINDING 发布主流程：两名不同签署人确认同一版本，写入 BINDING 快照并递增发布版本")
    void bindingPublishSuccess() {
        mockDocument(2, 0);
        mockPublishData(3, 1, List.of(
                new Rows.BindingSignatureRow("s1", "en", "jia", 3, 1, FIXED.minusSeconds(10)),
                new Rows.BindingSignatureRow("s1", "en", "yi", 3, 1, FIXED)));

        ApiDtos.PublishResponse response = service.publish(1L, bindingPublish(2, 0));

        assertThat(response.publishedVersion()).isEqualTo(1);
        assertThat(response.legalStatus()).isEqualTo("BINDING");
        assertThat(response.language()).isEqualTo("en");
        verify(repository).insertSnapshot(eq(1L), eq(1), eq("BINDING"), eq("en"), anyString());
        verify(repository).updatePublishedVersion(1L, 1);
    }

    @Test
    @DisplayName("BINDING 缺签：无任何签署 422，不写快照、不递增发布版本")
    void bindingPublishMissingSignatures() {
        mockDocument(2, 0);
        mockPublishData(3, 1, List.of());

        assertThatThrownBy(() -> service.publish(1L, bindingPublish(2, 0)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status().value()).isEqualTo(422));
        verify(repository, never()).insertSnapshot(anyLong(), anyInt(), anyString(), anyString(), anyString());
        verify(repository, never()).updatePublishedVersion(anyLong(), anyInt());
    }

    @Test
    @DisplayName("BINDING 同人重复签：只有一名签署人时 422，不能充当第二名签署人")
    void bindingPublishSameSignerTwiceRejected() {
        mockDocument(2, 0);
        mockPublishData(3, 1, List.of(
                new Rows.BindingSignatureRow("s1", "en", "jia", 3, 1, FIXED),
                new Rows.BindingSignatureRow("s1", "en", "jia", 3, 1, FIXED.plusSeconds(1))));

        assertThatThrownBy(() -> service.publish(1L, bindingPublish(2, 0)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status().value()).isEqualTo(422))
                .hasMessageContaining("两名不同签署人");
        verify(repository, never()).insertSnapshot(anyLong(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("BINDING 签署版本已被译文修订：旧版本签署不满足发布，422 且已发布版本不变")
    void bindingPublishSignaturesOnOldTranslationVersion() {
        mockDocument(3, 1);
        // 当前译文 V4；两份签署仍指向 V3
        mockPublishData(4, 1, List.of(
                new Rows.BindingSignatureRow("s1", "en", "jia", 3, 1, FIXED),
                new Rows.BindingSignatureRow("s1", "en", "yi", 3, 1, FIXED)));

        assertThatThrownBy(() -> service.publish(1L, bindingPublish(3, 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status().value()).isEqualTo(422));
        verify(repository, never()).updatePublishedVersion(anyLong(), anyInt());
    }

    @Test
    @DisplayName("BINDING 签署基于旧源文版本：源文修订使草稿签署失效，422")
    void bindingPublishSignaturesOnOldSourceVersion() {
        mockDocument(6, 0);
        // 当前源文 V2、译文 V4；签署指向 V4 但记录的源文版本为 1
        mockPublishData(4, 2, List.of(
                new Rows.BindingSignatureRow("s1", "en", "jia", 4, 1, FIXED),
                new Rows.BindingSignatureRow("s1", "en", "yi", 4, 1, FIXED)));

        assertThatThrownBy(() -> service.publish(1L, bindingPublish(6, 0)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status().value()).isEqualTo(422));
    }

    @Test
    @DisplayName("BINDING 发布缺 language 422；语种不在文档目标语种 422")
    void bindingPublishLanguageValidation() {
        mockDocument(2, 0);
        ApiDtos.PublishRequest noLanguage =
                new ApiDtos.PublishRequest("pub-1", 2, 0, "BINDING", null);
        assertThatThrownBy(() -> service.publish(1L, noLanguage))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status().value()).isEqualTo(422));

        mockPublishData(3, 1, List.of());
        ApiDtos.PublishRequest wrongLanguage =
                new ApiDtos.PublishRequest("pub-2", 2, 0, "BINDING", "fr");
        assertThatThrownBy(() -> service.publish(1L, wrongLanguage))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status().value()).isEqualTo(422));
    }

    @Test
    @DisplayName("期望版本不符：无论法律效力均 409 且不读取签署数据")
    void versionConflictBeforeLegalChecks() {
        mockDocument(4, 0);
        assertThatThrownBy(() -> service.publish(1L, bindingPublish(3, 0)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status().value()).isEqualTo(409));
        verify(repository, never()).listSignatures(anyLong());
    }
}
