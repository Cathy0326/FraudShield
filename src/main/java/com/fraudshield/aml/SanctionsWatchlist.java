package com.fraudshield.aml;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 制裁/PEP名单（演示用的模拟名单）—— 真实系统会对接OFAC SDN、欧盟、UN、以及PEP数据源。
 * Sanctions / PEP watchlist. THIS IS A MOCK for the demo — every name below is
 * fictional. A real system syncs OFAC SDN, EU/UN consolidated lists, and a PEP feed,
 * with proper entity resolution. The matching here is a deliberately simple fuzzy
 * match (normalize + token-subset + edit-distance-1) — enough to show the typology,
 * not a production screening engine.
 */
@Component
public class SanctionsWatchlist {

    public record Entry(String name, String type, String program) { }  // type: SANCTIONS | PEP

    // 纯虚构名单 / entirely fictional entries
    private static final List<Entry> ENTRIES = List.of(
            new Entry("Viktor Petrov",          "SANCTIONS", "MOCK-OFAC-SDN"),
            new Entry("Global Shell Holdings",  "SANCTIONS", "MOCK-OFAC-SDN"),
            new Entry("Ivan Sokolov",           "SANCTIONS", "MOCK-EU-CONSOLIDATED"),
            new Entry("Marlow Trading Ltd",     "SANCTIONS", "MOCK-UN"),
            new Entry("Elena Vasquez",          "PEP",       "MOCK-PEP"));

    /** 归一化：小写、去标点、压缩空白 / normalize: lowercase, strip punctuation, collapse spaces. */
    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * 对一个名字做筛查。命中条件（任一）：归一化后完全相等；名单所有词都出现在名字里；
     * 或与某条目的编辑距离≤1（抓拼写/转写差异）。
     * Screen a name. Hit when (any): normalized-equal; every watchlist token appears in
     * the name; or edit-distance ≤ 1 (catches spelling/transliteration drift).
     */
    public Optional<Entry> screen(String name) {
        String n = norm(name);
        if (n.isEmpty()) {
            return Optional.empty();
        }
        for (Entry e : ENTRIES) {
            String w = norm(e.name());
            if (n.equals(w)) {
                return Optional.of(e);
            }
            List<String> wTokens = List.of(w.split(" "));
            if (wTokens.size() > 1 && wTokens.stream().allMatch(t -> containsWord(n, t))) {
                return Optional.of(e);
            }
            if (levenshtein(n, w) <= 1) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    private static boolean containsWord(String haystack, String word) {
        return (" " + haystack + " ").contains(" " + word + " ");
    }

    // 标准DP编辑距离 / standard DP edit distance
    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }
}
