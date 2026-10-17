/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.aiFilter;

import android.os.Looper;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.Settings;
import app.morphe.extension.twitter.settings.SettingsStatus;

@SuppressWarnings("unused")
public class AiTimelineFilter {
    // The timeline key sits at the start of the response, so a short peek avoids reading unrelated responses.
    private static final int PEEK_BYTES = 2048;
    private static final String TIMELINE_RESPONSE_KEY = "timeline_response";
    private static final Set<String> HOME_PAGES = new HashSet<>(Arrays.asList("for_you", "following"));
    private static final int MAX_PARALLEL_REQUESTS = 16;
    private static final int MAX_CACHED_JUDGEMENTS = 2000;
    private static final int DEFAULT_TIMEOUT_MILLIS = 3000;

    private static final ExecutorService executor = Executors.newFixedThreadPool(MAX_PARALLEL_REQUESTS);

    private static final Map<String, ClefClient.TopicProbabilities> probabilityCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ClefClient.TopicProbabilities> eldest) {
            return size() > MAX_CACHED_JUDGEMENTS;
        }
    };

    private static class Match {
        final FilterRule rule;
        final double probability;

        Match(FilterRule rule, double probability) {
            this.rule = rule;
            this.probability = probability;
        }
    }

    // Called by the patch before Jackson parses any JSON response.
    public static InputStream filterResponse(InputStream inputStream) {
        String accountId = filteringAccountId();
        if (accountId == null) return inputStream;

        BufferedInputStream buffered = new BufferedInputStream(inputStream);
        try {
            if (!startsWithTimelineResponse(buffered)) return buffered;
            return new ByteArrayInputStream(filterHomeTimelineOrKeep(readAll(buffered), accountId));
        } catch (IOException ex) {
            PikoUtils.logger(ex);
            return buffered;
        }
    }

    // Returns the current account when it has the filter turned on, otherwise null.
    @Nullable
    private static String filteringAccountId() {
        if (!SettingsStatus.aiTimelineFilter) return null;
        // Network calls are not allowed on the main thread; leave such responses untouched.
        if (Looper.myLooper() == Looper.getMainLooper()) return null;
        String accountId = CurrentAccount.id();
        if (accountId == null || !Utils.getBooleanPref(Settings.aiFilterEnabled(accountId))) return null;
        return accountId;
    }

    private static boolean startsWithTimelineResponse(BufferedInputStream buffered) throws IOException {
        buffered.mark(PEEK_BYTES);
        byte[] head = new byte[PEEK_BYTES];
        int total = 0;
        int length;
        while (total < PEEK_BYTES && (length = buffered.read(head, total, PEEK_BYTES - total)) != -1) {
            total += length;
        }
        buffered.reset();
        return new String(head, 0, total, StandardCharsets.UTF_8).contains(TIMELINE_RESPONSE_KEY);
    }

    private static byte[] filterHomeTimelineOrKeep(byte[] body, String accountId) {
        try {
            byte[] filteredBody = filterHomeTimeline(body, accountId);
            return filteredBody != null ? filteredBody : body;
        } catch (Exception ex) {
            PikoUtils.logger(ex);
            return body;
        }
    }

    // Returns null when nothing was removed, so the original bytes are passed on untouched.
    @Nullable
    private static byte[] filterHomeTimeline(byte[] body, String accountId) throws Exception {
        String apiKey = Utils.getStringPref(Settings.AI_FILTER_API_KEY).trim();
        List<FilterRule> rules = FilterRule.loadFromSettings(accountId);
        if (apiKey.isEmpty() || rules.isEmpty()) return null;

        JSONObject root = new JSONObject(new String(body, StandardCharsets.UTF_8));
        JSONObject homeTimeline = homeTimelineOf(root);
        if (homeTimeline == null) return null;
        JSONArray instructions = homeTimeline.getJSONArray("instructions");
        boolean skipFollowedAuthors = Utils.getBooleanPref(Settings.aiFilterSkipFollowing(accountId));

        List<JSONArray> entryLists = new ArrayList<>();
        Map<String, TimelineTweet> tweetsById = new LinkedHashMap<>();
        for (int index = 0; index < instructions.length(); index++) {
            JSONArray entries = instructions.getJSONObject(index).optJSONArray("entries");
            if (entries == null) continue;
            entryLists.add(entries);
            for (int entryIndex = 0; entryIndex < entries.length(); entryIndex++) {
                for (TimelineTweet tweet : judgeableTweetsOf(entries.getJSONObject(entryIndex), skipFollowedAuthors)) {
                    tweetsById.put(tweet.id, tweet);
                }
            }
        }
        if (tweetsById.isEmpty()) return null;

        Map<String, Match> matches = judgeAll(new ArrayList<>(tweetsById.values()), rules, apiKey);
        if (matches.isEmpty()) return null;

        List<JSONObject> vaultItems = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (JSONArray entries : entryLists) {
            for (int entryIndex = entries.length() - 1; entryIndex >= 0; entryIndex--) {
                // A conversation module is removed as a whole if any of its posts matches.
                boolean removeEntry = false;
                for (TimelineTweet tweet : judgeableTweetsOf(entries.getJSONObject(entryIndex), skipFollowedAuthors)) {
                    Match match = matches.get(tweet.id);
                    if (match == null) continue;
                    removeEntry = true;
                    vaultItems.add(tweet.toVaultItem(accountId, match.rule.text, match.probability, now));
                }
                if (removeEntry) entries.remove(entryIndex);
            }
        }
        FilterVault.addItems(vaultItems);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    // Other screens also return data.timeline_response; only the For you and Following tabs are filtered.
    @Nullable
    private static JSONObject homeTimelineOf(JSONObject root) {
        JSONObject data = root.optJSONObject("data");
        JSONObject timelineResponse = data == null ? null : data.optJSONObject(TIMELINE_RESPONSE_KEY);
        JSONObject timeline = timelineResponse == null ? null : timelineResponse.optJSONObject("timeline");
        JSONObject metadata = timeline == null ? null : timeline.optJSONObject("metadata");
        JSONObject scribeConfig = metadata == null ? null : metadata.optJSONObject("scribeConfig");
        if (scribeConfig == null || !HOME_PAGES.contains(scribeConfig.optString("page"))) return null;
        return timeline;
    }

    // Ads, and optionally posts by followed accounts, are skipped so that they do not cost judgement requests.
    private static List<TimelineTweet> judgeableTweetsOf(JSONObject entry, boolean skipFollowedAuthors) {
        List<TimelineTweet> tweets = new ArrayList<>();
        if (entry.optString("entryId").startsWith("promoted-")) return tweets;
        for (TimelineTweet tweet : TimelineTweet.fromEntry(entry)) {
            if (skipFollowedAuthors && tweet.authorFollowedByMe) continue;
            tweets.add(tweet);
        }
        return tweets;
    }

    // Requests that fail or exceed the timeout leave the post visible.
    private static Map<String, Match> judgeAll(List<TimelineTweet> tweets, List<FilterRule> rules, String apiKey) throws InterruptedException {
        long startedAt = System.currentTimeMillis();
        int timeoutMillis = timeoutMillis();
        String signature = FilterRule.cacheSignature(rules);

        Map<String, ClefClient.TopicProbabilities> probabilitiesById = new HashMap<>();
        List<TimelineTweet> uncachedTweets = new ArrayList<>();
        for (TimelineTweet tweet : tweets) {
            ClefClient.TopicProbabilities cached;
            synchronized (probabilityCache) {
                cached = probabilityCache.get(tweet.id + signature);
            }
            if (cached != null) {
                probabilitiesById.put(tweet.id, cached);
            } else {
                uncachedTweets.add(tweet);
            }
        }

        List<Callable<ClefClient.Judgement>> requests = new ArrayList<>();
        for (TimelineTweet tweet : uncachedTweets) {
            requests.add(() -> ClefClient.judge(tweet, rules, apiKey, timeoutMillis));
        }
        List<Future<ClefClient.Judgement>> results = requests.isEmpty()
                ? new ArrayList<>()
                : executor.invokeAll(requests, timeoutMillis, TimeUnit.MILLISECONDS);

        UsageStats usage = new UsageStats();
        usage.judged = uncachedTweets.size();
        for (int index = 0; index < results.size(); index++) {
            TimelineTweet tweet = uncachedTweets.get(index);
            try {
                ClefClient.Judgement judgement = results.get(index).get();
                probabilitiesById.put(tweet.id, judgement.topics);
                synchronized (probabilityCache) {
                    probabilityCache.put(tweet.id + signature, judgement.topics);
                }
                usage.cost += judgement.cost;
                usage.inputTokens += judgement.inputTokens;
                usage.judgeMillisTotal += judgement.elapsedMillis;
            } catch (CancellationException ex) {
                usage.timeouts++;
                usage.lastError = "timeout";
            } catch (ExecutionException ex) {
                recordFailure(usage, ex.getCause());
            }
        }
        usage.timelineResponses = 1;
        usage.waitMillisTotal = System.currentTimeMillis() - startedAt;
        FilterVault.recordUsage(usage);

        Map<String, Match> matches = new HashMap<>();
        for (Map.Entry<String, ClefClient.TopicProbabilities> entry : probabilitiesById.entrySet()) {
            Match match = strongestMatch(rules, entry.getValue());
            if (match != null) matches.put(entry.getKey(), match);
        }
        return matches;
    }

    private static void recordFailure(UsageStats usage, Throwable cause) {
        PikoUtils.logger(cause);
        if (cause instanceof SocketTimeoutException) {
            usage.timeouts++;
            usage.lastError = "timeout";
        } else if (cause instanceof ClefClient.HttpException) {
            usage.httpErrors++;
            usage.lastError = "HTTP " + ((ClefClient.HttpException) cause).statusCode;
        } else {
            usage.otherErrors++;
            usage.lastError = cause == null ? "unknown" : cause.getClass().getSimpleName();
        }
    }

    // A choice question splits probability across rules when a post fits several of them,
    // so the chance of matching any rule (1 - none) is compared with the most likely rule's threshold.
    @Nullable
    private static Match strongestMatch(List<FilterRule> rules, ClefClient.TopicProbabilities topics) {
        int mostLikelyIndex = -1;
        for (int index = 0; index < rules.size(); index++) {
            if (mostLikelyIndex < 0 || topics.ruleProbabilities[index] > topics.ruleProbabilities[mostLikelyIndex]) {
                mostLikelyIndex = index;
            }
        }
        if (mostLikelyIndex < 0) return null;

        FilterRule mostLikelyRule = rules.get(mostLikelyIndex);
        double anyRuleProbability = 1 - topics.noneProbability;
        if (anyRuleProbability < mostLikelyRule.threshold) return null;
        return new Match(mostLikelyRule, anyRuleProbability);
    }

    private static int timeoutMillis() {
        try {
            int timeout = Integer.parseInt(Utils.getStringPref(Settings.AI_FILTER_TIMEOUT_MILLIS).trim());
            if (timeout > 0) return timeout;
        } catch (NumberFormatException ignored) {
        }
        return DEFAULT_TIMEOUT_MILLIS;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int length;
        while ((length = input.read(buffer)) != -1) {
            output.write(buffer, 0, length);
        }
        input.close();
        return output.toByteArray();
    }
}
