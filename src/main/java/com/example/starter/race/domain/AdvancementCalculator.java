package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分组晋级选人的纯逻辑，不涉及数据库与时间。
 *
 * <p>输入为各组中具备有效成绩的选手（完赛计时、覆盖全部检查点且未取消资格；
 * 总耗时已含未撤销加时），排名沿用现有规则：总耗时升序、并列同名次并跳号（1、1、3）。
 * <ul>
 *   <li>DIRECT：每组内取名次不超过 Q 的选手；并列跨过第 Q 名边界时同名次全部纳入；</li>
 *   <li>WILDCARD：从所有未 DIRECT 的选手中按总耗时全局排名取前 W 名，并列同理全部纳入；</li>
 *   <li>超额原因记录每个被并列跨越的边界及该同名次组的耗时与参赛号。</li>
 * </ul>
 */
public final class AdvancementCalculator {

    private AdvancementCalculator() {
    }

    /** 具备有效成绩的分组候选人；时间均为毫秒。 */
    public record Candidate(
            String bib,
            String groupCode,
            long finishTimeMs,
            long penaltyMs,
            long totalTimeMs
    ) {
    }

    /** 带名次的候选人。 */
    public record Ranked(Candidate candidate, int rank) {
    }

    /** 并列跨边界的超额说明（纯逻辑层，不含配额字段以外的响应信息）。 */
    public record OverQuota(
            AdvancementEntryType boundary,
            String groupCode,
            int quota,
            long tiedTimeMs,
            List<String> bibs
    ) {
    }

    /** 单个分组的计算结果。 */
    public record GroupResult(String groupCode, List<Ranked> ranked) {
    }

    /** 完整选人结果。 */
    public record Selection(
            List<GroupResult> groups,
            List<Ranked> direct,
            List<Ranked> wildcard,
            List<Ranked> nonAdvanced,
            List<OverQuota> overQuotaReasons
    ) {
    }

    /**
     * 计算晋级名单。
     *
     * @param groupCodes    分组代码，按分组顺序排列
     * @param candidates    各组有效选手候选人
     * @param directQuota   每组直接晋级名额 Q（1~8）
     * @param wildcardQuota 跨组补位名额 W（0~8）
     */
    public static Selection select(
            List<String> groupCodes,
            List<Candidate> candidates,
            int directQuota,
            int wildcardQuota) {
        Map<String, List<Candidate>> byGroup = new LinkedHashMap<>();
        for (String groupCode : groupCodes) {
            byGroup.put(groupCode, new ArrayList<>());
        }
        for (Candidate candidate : candidates) {
            byGroup.computeIfAbsent(candidate.groupCode(), key -> new ArrayList<>())
                    .add(candidate);
        }

        List<GroupResult> groupResults = new ArrayList<>();
        List<Ranked> direct = new ArrayList<>();
        List<OverQuota> overQuota = new ArrayList<>();
        Map<String, Ranked> directByBib = new LinkedHashMap<>();
        for (String groupCode : groupCodes) {
            List<Ranked> ranked = rank(byGroup.getOrDefault(groupCode, List.of()));
            groupResults.add(new GroupResult(groupCode, ranked));
            List<Ranked> selected = selectWithinRank(ranked, directQuota);
            if (selected.size() > directQuota) {
                long tiedTimeMs = selected.getLast().candidate().totalTimeMs();
                overQuota.add(new OverQuota(
                        AdvancementEntryType.DIRECT, groupCode, directQuota, tiedTimeMs,
                        selected.stream()
                                .filter(r -> r.candidate().totalTimeMs() == tiedTimeMs)
                                .map(r -> r.candidate().bib())
                                .sorted()
                                .toList()));
            }
            for (Ranked rankedCandidate : selected) {
                direct.add(rankedCandidate);
                directByBib.put(rankedCandidate.candidate().bib(), rankedCandidate);
            }
        }

        List<Candidate> wildcardPool = candidates.stream()
                .filter(candidate -> !directByBib.containsKey(candidate.bib()))
                .toList();
        List<Ranked> wildcardRanked = rank(wildcardPool);
        List<Ranked> wildcard = selectWithinRank(wildcardRanked, wildcardQuota);
        if (wildcard.size() > wildcardQuota) {
            long tiedTimeMs = wildcard.getLast().candidate().totalTimeMs();
            overQuota.add(new OverQuota(
                    AdvancementEntryType.WILDCARD, null, wildcardQuota, tiedTimeMs,
                    wildcard.stream()
                            .filter(r -> r.candidate().totalTimeMs() == tiedTimeMs)
                            .map(r -> r.candidate().bib())
                            .sorted()
                            .toList()));
        }

        java.util.Set<String> advancedBibs = new java.util.HashSet<>(directByBib.keySet());
        for (Ranked rankedCandidate : wildcard) {
            advancedBibs.add(rankedCandidate.candidate().bib());
        }

        List<Ranked> nonAdvanced = new ArrayList<>();
        for (GroupResult groupResult : groupResults) {
            for (Ranked rankedCandidate : groupResult.ranked()) {
                if (!advancedBibs.contains(rankedCandidate.candidate().bib())) {
                    nonAdvanced.add(rankedCandidate);
                }
            }
        }

        return new Selection(
                List.copyOf(groupResults),
                List.copyOf(direct),
                List.copyOf(wildcard),
                List.copyOf(nonAdvanced),
                List.copyOf(overQuota));
    }

    /** 总耗时升序、并列按参赛号字典序排列，并赋予并列同名次（1、1、3）。 */
    public static List<Ranked> rank(List<Candidate> candidates) {
        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator
                .comparingLong(Candidate::totalTimeMs)
                .thenComparing(Candidate::bib));
        List<Ranked> ranked = new ArrayList<>(ordered.size());
        int index = 0;
        while (index < ordered.size()) {
            int groupEnd = index + 1;
            while (groupEnd < ordered.size()
                    && ordered.get(groupEnd).totalTimeMs() == ordered.get(index).totalTimeMs()) {
                groupEnd++;
            }
            int rank = index + 1;
            for (int groupIndex = index; groupIndex < groupEnd; groupIndex++) {
                ranked.add(new Ranked(ordered.get(groupIndex), rank));
            }
            index = groupEnd;
        }
        return ranked;
    }

    /** 取名次不超过 quota 的全部选手：并列同名次组跨过边界时整组纳入。 */
    private static List<Ranked> selectWithinRank(List<Ranked> ranked, int quota) {
        if (quota <= 0) {
            return List.of();
        }
        List<Ranked> selected = new ArrayList<>();
        for (Ranked rankedCandidate : ranked) {
            if (rankedCandidate.rank() <= quota) {
                selected.add(rankedCandidate);
            }
        }
        return selected;
    }
}
