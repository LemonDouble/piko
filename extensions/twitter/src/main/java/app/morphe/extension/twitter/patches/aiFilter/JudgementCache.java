/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.aiFilter;

import androidx.annotation.Nullable;

import org.json.JSONArray;

import java.util.LinkedHashMap;
import java.util.Map;

import app.morphe.extension.crimera.PikoUtils;

// Judgements survive process restarts: Android often kills the app in the background,
// and the timeline then sends many posts that were already judged.
public class JudgementCache {
    private static final String CACHE_FILE = "ai_filter_cache.json";
    private static final int MAX_ENTRIES = 5000;

    // Access order, so the least recently used judgement is dropped first.
    private static Map<String, ClefClient.TopicProbabilities> entries;

    @Nullable
    public static synchronized ClefClient.TopicProbabilities get(String tweetId, String ruleSignature) {
        return loadedEntries().get(key(tweetId, ruleSignature));
    }

    public static synchronized void putAll(Map<String, ClefClient.TopicProbabilities> topicsByTweetId, String ruleSignature) {
        if (topicsByTweetId.isEmpty()) return;
        Map<String, ClefClient.TopicProbabilities> current = loadedEntries();
        for (Map.Entry<String, ClefClient.TopicProbabilities> judged : topicsByTweetId.entrySet()) {
            current.put(key(judged.getKey(), ruleSignature), judged.getValue());
        }
        save(current);
    }

    // Probabilities only depend on the rule texts, so thresholds can change without new requests.
    private static String key(String tweetId, String ruleSignature) {
        return tweetId + "|" + Integer.toHexString(ruleSignature.hashCode());
    }

    private static Map<String, ClefClient.TopicProbabilities> loadedEntries() {
        if (entries != null) return entries;
        entries = new LinkedHashMap<String, ClefClient.TopicProbabilities>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ClefClient.TopicProbabilities> eldest) {
                return size() > MAX_ENTRIES;
            }
        };
        try {
            // Stored oldest first as [key, [rule probabilities], none probability].
            JSONArray saved = new JSONArray(FilterVault.readFile(CACHE_FILE));
            for (int index = 0; index < saved.length(); index++) {
                JSONArray row = saved.getJSONArray(index);
                JSONArray savedRules = row.getJSONArray(1);
                double[] ruleProbabilities = new double[savedRules.length()];
                for (int rule = 0; rule < ruleProbabilities.length; rule++) {
                    ruleProbabilities[rule] = savedRules.getDouble(rule);
                }
                entries.put(row.getString(0), new ClefClient.TopicProbabilities(ruleProbabilities, row.getDouble(2)));
            }
        } catch (Exception ignored) {
        }
        return entries;
    }

    private static void save(Map<String, ClefClient.TopicProbabilities> current) {
        try {
            JSONArray rows = new JSONArray();
            for (Map.Entry<String, ClefClient.TopicProbabilities> entry : current.entrySet()) {
                JSONArray ruleProbabilities = new JSONArray();
                for (double probability : entry.getValue().ruleProbabilities) {
                    ruleProbabilities.put(probability);
                }
                rows.put(new JSONArray()
                        .put(entry.getKey())
                        .put(ruleProbabilities)
                        .put(entry.getValue().noneProbability));
            }
            FilterVault.writeFile(CACHE_FILE, rows.toString());
        } catch (Exception ex) {
            PikoUtils.logger(ex);
        }
    }
}
