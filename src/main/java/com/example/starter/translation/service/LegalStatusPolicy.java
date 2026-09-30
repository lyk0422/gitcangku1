package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.SignatureRow;
import com.example.starter.translation.domain.Rows.TranslationRow;

import java.util.List;

/**
 * 按语种法律效力状态判定发布条件的纯逻辑策略，便于不依赖数据库做单元测试。
 */
final class LegalStatusPolicy {

    private LegalStatusPolicy() {
    }

    /** INFORMATIONAL 发布条件：当前译文版本存在且源文版本匹配的单审核人批准。 */
    static void verifyInformationalApproval(SegmentRow segment, String language,
                                            TranslationRow translation, ApprovalRow approval) {
        if (approval == null) {
            throw ApiException.unprocessable(
                    "缺少批准: " + segment.segmentId() + "/" + language);
        }
        if (approval.translationVersion() != translation.translationVersion()
                || approval.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("批准已失效: " + segment.segmentId() + "/" + language
                    + "，源文或译文版本已改变");
        }
    }

    /**
     * BINDING 发布条件：当前译文版本上有两名（含）以上互不相同的签署人。
     * 针对已修订旧版本的签署不计入；无当前版本签署为缺签，仅一名不同签署人为签署不足。
     */
    static void verifyBindingSignatures(SegmentRow segment, String language,
                                        TranslationRow translation, List<SignatureRow> all) {
        List<SignatureRow> current = all.stream()
                .filter(s -> s.translationVersion() == translation.translationVersion())
                .toList();
        long distinctSigners = current.stream().map(SignatureRow::signer).distinct().count();
        if (distinctSigners < 2) {
            String detail;
            if (current.isEmpty()) {
                detail = "缺少签署: " + segment.segmentId() + "/" + language
                        + "，BINDING 发布须两名不同签署人签署译文版本 "
                        + translation.translationVersion();
            } else {
                detail = "签署不足: " + segment.segmentId() + "/" + language
                        + "，译文版本 " + translation.translationVersion() + " 仅有 " + distinctSigners
                        + " 名不同签署人，BINDING 发布须两名不同签署人";
            }
            throw ApiException.unprocessable(detail);
        }
    }
}
