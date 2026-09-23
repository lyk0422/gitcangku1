package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos.StageStatusView;
import com.example.starter.translation.api.ApiDtos.VoteHistoryEntry;
import com.example.starter.translation.domain.Rows.ReviewPolicyRow;
import com.example.starter.translation.domain.Rows.ReviewStage;
import com.example.starter.translation.domain.Rows.ReviewVoteRow;
import com.example.starter.translation.domain.Rows.VoteDecision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双阶段评审计票规则（无状态组件）。
 * 当前票必须同时满足：策略版本为激活版本、sourceVersion/translationVersion/termVersion 与当前一致、
 * 审核人仍在该阶段候选集合中；同一审核人只取其最高 voteVersion 的票计入法定人数，旧票保留审计。
 * 任一当前 REJECT 票使阶段 BLOCKED；无拒绝且 APPROVE 人数达到阈值才 PASSED，否则 PENDING。
 */
public final class ReviewGate {

    /** 阶段计票结果：对外状态视图与被计入（拟采用）的当前 APPROVE 票。 */
    public record StageState(StageStatusView view, List<ReviewVoteRow> adoptedApproves) {
    }

    private ReviewGate() {
    }

    /** 该阶段候选审核人集合。 */
    public static List<String> candidates(ReviewPolicyRow policy, ReviewStage stage) {
        return stage == ReviewStage.LANGUAGE ? policy.languageReviewers() : policy.complianceReviewers();
    }

    /** 该阶段法定人数。 */
    public static int quorum(ReviewPolicyRow policy, ReviewStage stage) {
        return stage == ReviewStage.LANGUAGE ? policy.languageQuorum() : policy.complianceQuorum();
    }

    /**
     * 计算某阶段当前状态。
     *
     * @param policy               激活的策略版本（不可变）
     * @param stage                阶段
     * @param votes                该段落-语言的全部历史票
     * @param sourceVersion        当前源文版本
     * @param translationVersion   当前译文版本
     * @param termVersion          当前术语版本
     */
    public static StageState evaluate(ReviewPolicyRow policy, ReviewStage stage, List<ReviewVoteRow> votes,
                                      int sourceVersion, int translationVersion, int termVersion) {
        List<String> candidateSet = candidates(policy, stage);
        Map<String, ReviewVoteRow> latestByReviewer = new LinkedHashMap<>();
        for (ReviewVoteRow vote : votes) {
            if (vote.stage() != stage || !isCurrent(vote, policy.policyVersion(),
                    sourceVersion, translationVersion, termVersion)) {
                continue;
            }
            if (!candidateSet.contains(vote.reviewer())) {
                continue;
            }
            ReviewVoteRow previous = latestByReviewer.get(vote.reviewer());
            if (previous == null || vote.voteVersion() > previous.voteVersion()) {
                latestByReviewer.put(vote.reviewer(), vote);
            }
        }
        int approveCount = 0;
        int rejectCount = 0;
        boolean blocked = false;
        List<ReviewVoteRow> adopted = new ArrayList<>();
        List<VoteHistoryEntry> currentEntries = new ArrayList<>();
        for (ReviewVoteRow vote : latestByReviewer.values().stream()
                .sorted(Comparator.comparing(ReviewVoteRow::reviewer)).toList()) {
            if (vote.decision() == VoteDecision.REJECT) {
                rejectCount++;
                blocked = true;
            } else {
                approveCount++;
                adopted.add(vote);
            }
            currentEntries.add(toEntry(vote, true));
        }
        int quorum = quorum(policy, stage);
        String status = blocked ? "BLOCKED" : approveCount >= quorum ? "PASSED" : "PENDING";
        return new StageState(new StageStatusView(stage.name(), quorum, approveCount, rejectCount, status,
                List.copyOf(currentEntries)), List.copyOf(adopted));
    }

    /** 判断一票是否计入当前法定人数：版本全部匹配且策略为激活版本。 */
    public static boolean isCurrent(ReviewVoteRow vote, int activePolicyVersion, int sourceVersion,
                                    int translationVersion, int termVersion) {
        return vote.policyVersion() == activePolicyVersion
                && vote.sourceVersion() == sourceVersion
                && vote.translationVersion() == translationVersion
                && vote.termVersion() == termVersion;
    }

    /** 历史票转视图，current 表示是否计入当前法定人数。 */
    public static VoteHistoryEntry toEntry(ReviewVoteRow vote, boolean current) {
        return new VoteHistoryEntry(vote.voteId(), vote.voteKey(), vote.stage().name(), vote.reviewer(),
                vote.decision().name(), vote.voteVersion(), vote.sourceVersion(), vote.translationVersion(),
                vote.termVersion(), vote.policyVersion(), current, vote.requestId());
    }
}
