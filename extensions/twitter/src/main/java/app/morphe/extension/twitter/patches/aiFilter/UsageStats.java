/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.aiFilter;

import org.json.JSONException;
import org.json.JSONObject;

public class UsageStats {
    public int judged;
    public int cacheHits;
    public int timeouts;
    public int httpErrors;
    public int otherErrors;
    public double cost;
    public long inputTokens;
    public long judgeMillisTotal;
    public int timelineResponses;
    public long waitMillisTotal;
    public String lastError = "";

    public int failed() {
        return timeouts + httpErrors + otherErrors;
    }

    public long averageJudgeMillis() {
        int succeeded = judged - failed();
        return succeeded == 0 ? 0 : judgeMillisTotal / succeeded;
    }

    public long averageWaitMillis() {
        return timelineResponses == 0 ? 0 : waitMillisTotal / timelineResponses;
    }

    void add(UsageStats other) {
        judged += other.judged;
        cacheHits += other.cacheHits;
        timeouts += other.timeouts;
        httpErrors += other.httpErrors;
        otherErrors += other.otherErrors;
        cost += other.cost;
        inputTokens += other.inputTokens;
        judgeMillisTotal += other.judgeMillisTotal;
        timelineResponses += other.timelineResponses;
        waitMillisTotal += other.waitMillisTotal;
        if (!other.lastError.isEmpty()) lastError = other.lastError;
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("judged", judged)
                .put("cache_hits", cacheHits)
                .put("timeouts", timeouts)
                .put("http_errors", httpErrors)
                .put("other_errors", otherErrors)
                .put("cost", cost)
                .put("input_tokens", inputTokens)
                .put("judge_millis_total", judgeMillisTotal)
                .put("timeline_responses", timelineResponses)
                .put("wait_millis_total", waitMillisTotal)
                .put("last_error", lastError);
    }

    static UsageStats fromJson(JSONObject json) {
        UsageStats stats = new UsageStats();
        stats.judged = json.optInt("judged");
        stats.cacheHits = json.optInt("cache_hits");
        stats.timeouts = json.optInt("timeouts");
        stats.httpErrors = json.optInt("http_errors");
        stats.otherErrors = json.optInt("other_errors");
        stats.cost = json.optDouble("cost", 0);
        stats.inputTokens = json.optLong("input_tokens");
        stats.judgeMillisTotal = json.optLong("judge_millis_total");
        stats.timelineResponses = json.optInt("timeline_responses");
        stats.waitMillisTotal = json.optLong("wait_millis_total");
        stats.lastError = json.optString("last_error");
        return stats;
    }
}
