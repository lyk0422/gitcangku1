package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队成绩汇总纯逻辑：按各队锁定名单版本内固化的个人成绩汇总团队总分，
 * 不访问数据库、不读取时钟。
 *
 * <p>规则：
 * <ul>
 *   <li>每个团队引用且仅引用一个完整名单版本（{@link TeamRosterView#rosterVersion()}）；</li>
 *   <li>个人成绩以版本固化值为准，后续计时修订、处罚变更不改变团队汇总；</li>
 *   <li>成员已登记退赛且 {@code effectiveSession <= currentSession} 时不计分
 *       （实时榜传入当前场次，封榜快照由调用方只传入封榜前已生效的退赛）；</li>
 *   <li>仅 RANKED 成员的总耗时计入团队总分，其余状态及退赛生效成员不计分但仍列在成员明细中；</li>
 *   <li>团队按总分升序排列（总耗时越少名次越前，与个人排名方向一致），同分按团队ID字典序；
 *       成员按参赛号字典序。</li>
 * </ul>
 */
public final class TeamScoreCalculator {

    private TeamScoreCalculator() {
    }

    /** 团队名单版本视图：版本号与该版本固化的成员个人成绩。 */
    public interface TeamRosterView {
        String teamId();

        int rosterVersion();

        List<? extends RosterMemberView> members();
    }

    /** 版本内成员的固化个人成绩视图。 */
    public interface RosterMemberView {
        String bib();

        EntryStatus status();

        Long finishTimeMs();

        long penaltyMs();

        Long totalTimeMs();
    }

    /** 退赛视图：生效场次与参赛号。 */
    public interface WithdrawalView {
        String bib();

        int effectiveSession();
    }

    /** 团队计分结果。 */
    public record TeamScore(
            String teamId,
            int rosterVersion,
            long totalTimeMs,
            int memberCount,
            int scoringCount,
            List<MemberScore> members
    ) {
    }

    /** 成员计分结果。 */
    public record MemberScore(
            String bib,
            EntryStatus status,
            Long finishTimeMs,
            long penaltyMs,
            Long totalTimeMs,
            boolean scoring,
            boolean withdrawn,
            Integer effectiveSession
    ) {
    }

    /**
     * 计算团队成绩榜。
     *
     * @param rosters        各队引用的完整名单版本（通常为各队最新锁定版本）
     * @param withdrawals    赛事内全部已登记退赛，按参赛号索引
     * @param currentSession 当前计分层场次；生效场次不晚于该值的退赛成员不计分
     * @return 按团队总分升序、同分按团队ID字典序排列的团队成绩
     */
    public static List<TeamScore> compute(
            List<? extends TeamRosterView> rosters,
            Map<String, ? extends WithdrawalView> withdrawals,
            int currentSession) {
        List<TeamScore> scores = new ArrayList<>(rosters.size());
        for (TeamRosterView roster : rosters) {
            List<RosterMemberView> orderedMembers = new ArrayList<>(roster.members());
            orderedMembers.sort(Comparator.comparing(RosterMemberView::bib));

            List<MemberScore> memberScores = new ArrayList<>(orderedMembers.size());
            long totalTimeMs = 0L;
            int scoringCount = 0;
            for (RosterMemberView member : orderedMembers) {
                WithdrawalView withdrawal = withdrawals.get(member.bib());
                boolean withdrawn = withdrawal != null;
                boolean effective = withdrawn && withdrawal.effectiveSession() <= currentSession;
                boolean scoring = member.status() == EntryStatus.RANKED && !effective;
                if (scoring) {
                    totalTimeMs += member.totalTimeMs();
                    scoringCount++;
                }
                memberScores.add(new MemberScore(
                        member.bib(),
                        member.status(),
                        member.finishTimeMs(),
                        member.penaltyMs(),
                        member.totalTimeMs(),
                        scoring,
                        withdrawn,
                        withdrawn ? withdrawal.effectiveSession() : null));
            }
            scores.add(new TeamScore(
                    roster.teamId(), roster.rosterVersion(), totalTimeMs,
                    orderedMembers.size(), scoringCount, memberScores));
        }
        scores.sort(Comparator.comparingLong(TeamScore::totalTimeMs)
                .thenComparing(TeamScore::teamId));
        return scores;
    }

    /** 把按参赛号索引的退赛列表转为 Map，重复键保留先到者（数据库唯一约束保证不重复）。 */
    public static Map<String, WithdrawalView> indexByBib(
            List<? extends WithdrawalView> withdrawals) {
        Map<String, WithdrawalView> indexed = new LinkedHashMap<>();
        for (WithdrawalView withdrawal : withdrawals) {
            indexed.putIfAbsent(withdrawal.bib(), withdrawal);
        }
        return indexed;
    }
}
