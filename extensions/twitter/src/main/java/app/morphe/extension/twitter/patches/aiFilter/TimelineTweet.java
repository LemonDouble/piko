/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.aiFilter;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class TimelineTweet {
    private static final int MAX_CLEF_TEXT_CODE_POINTS = 2000;

    public final String id;
    public final String authorName;
    public final String authorHandle;
    public final String text;
    @Nullable
    public final String quotedText;
    public final int mediaCount;
    @Nullable
    public final String retweetedBy;
    public final boolean authorFollowedByMe;

    private TimelineTweet(String id, String authorName, String authorHandle, String text,
                          @Nullable String quotedText, int mediaCount, @Nullable String retweetedBy,
                          boolean authorFollowedByMe) {
        this.id = id;
        this.authorName = authorName;
        this.authorHandle = authorHandle;
        this.text = text;
        this.quotedText = quotedText;
        this.mediaCount = mediaCount;
        this.retweetedBy = retweetedBy;
        this.authorFollowedByMe = authorFollowedByMe;
    }

    // A single post entry has "content.content"; a conversation module has "content.items".
    public static List<TimelineTweet> fromEntry(JSONObject entry) {
        List<TimelineTweet> tweets = new ArrayList<>();
        JSONObject content = entry.optJSONObject("content");
        if (content == null) return tweets;

        addIfTweet(tweets, content.optJSONObject("content"));

        JSONArray moduleItems = content.optJSONArray("items");
        if (moduleItems != null) {
            for (int index = 0; index < moduleItems.length(); index++) {
                JSONObject moduleItem = moduleItems.optJSONObject(index);
                JSONObject item = moduleItem == null ? null : moduleItem.optJSONObject("item");
                addIfTweet(tweets, item == null ? null : item.optJSONObject("content"));
            }
        }
        return tweets;
    }

    private static void addIfTweet(List<TimelineTweet> tweets, @Nullable JSONObject itemContent) {
        if (itemContent == null) return;
        TimelineTweet tweet = fromResult(resultOf(itemContent.optJSONObject("tweetResult")), null);
        if (tweet != null) tweets.add(tweet);
    }

    @Nullable
    private static TimelineTweet fromResult(@Nullable JSONObject result, @Nullable String retweetedBy) {
        result = unwrapVisibilityResult(result);
        if (result == null) return null;
        JSONObject legacy = result.optJSONObject("legacy");
        String id = result.optString("rest_id");
        if (legacy == null || id.isEmpty()) return null;

        JSONObject author = authorOf(result);

        // A retweet is judged as the original post, including whether its original author is followed.
        JSONObject retweetedResult = resultOf(legacy.optJSONObject("retweeted_status_result"));
        if (retweetedResult != null) {
            TimelineTweet original = fromResult(retweetedResult, author.optString("screen_name"));
            if (original != null) return original;
        }

        TimelineTweet quoted = fromResult(resultOf(result.optJSONObject("quoted_status_result")), null);
        String quotedText = quoted == null ? null : quoted.text;

        JSONObject extendedEntities = legacy.optJSONObject("extended_entities");
        JSONArray media = extendedEntities == null ? null : extendedEntities.optJSONArray("media");

        return new TimelineTweet(
                id,
                author.optString("name"),
                author.optString("screen_name"),
                fullTextOf(result, legacy),
                quotedText,
                media == null ? 0 : media.length(),
                retweetedBy,
                author.optBoolean("following")
        );
    }

    @Nullable
    private static JSONObject unwrapVisibilityResult(@Nullable JSONObject result) {
        if (result != null && "TweetWithVisibilityResults".equals(result.optString("__typename"))) {
            return result.optJSONObject("tweet");
        }
        return result;
    }

    @Nullable
    private static JSONObject resultOf(@Nullable JSONObject resultHolder) {
        return resultHolder == null ? null : resultHolder.optJSONObject("result");
    }

    // Long posts keep only the first 280 characters in legacy.full_text.
    private static String fullTextOf(JSONObject result, JSONObject legacy) {
        JSONObject noteTweet = result.optJSONObject("note_tweet");
        JSONObject noteResult = noteTweet == null ? null : resultOf(noteTweet.optJSONObject("note_tweet_results"));
        if (noteResult != null && !noteResult.optString("text").isEmpty()) {
            return noteResult.optString("text");
        }
        return legacy.optString("full_text");
    }

    // Returns user.legacy, which holds name, screen_name and following; empty when missing.
    private static JSONObject authorOf(JSONObject result) {
        JSONObject core = result.optJSONObject("core");
        JSONObject user = core == null ? null : resultOf(core.optJSONObject("user_result"));
        JSONObject legacy = user == null ? null : user.optJSONObject("legacy");
        return legacy == null ? new JSONObject() : legacy;
    }

    public JSONObject toClefState() throws JSONException {
        JSONObject state = new JSONObject();
        state.put("author", authorName + " (@" + authorHandle + ")");
        state.put("text", truncatedForClef(text));
        if (quotedText != null) state.put("quoted_post", truncatedForClef(quotedText));
        state.put("media_count", mediaCount);
        return state;
    }

    // Caps the cost of very long posts (up to 25,000 characters) at about 1,000 tokens each;
    // the opening of a post is enough to judge its topic.
    private static String truncatedForClef(String value) {
        if (value.codePointCount(0, value.length()) <= MAX_CLEF_TEXT_CODE_POINTS) return value;
        return value.substring(0, value.offsetByCodePoints(0, MAX_CLEF_TEXT_CODE_POINTS));
    }

    public JSONObject toVaultItem(String accountId, String filterText, double probability, long filteredAtMillis) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("account_id", accountId);
        item.put("id", id);
        item.put("author_name", authorName);
        item.put("author_handle", authorHandle);
        item.put("text", text);
        if (quotedText != null) item.put("quoted_text", quotedText);
        item.put("media_count", mediaCount);
        if (retweetedBy != null) item.put("retweeted_by", retweetedBy);
        item.put("filter", filterText);
        item.put("probability", probability);
        item.put("filtered_at", filteredAtMillis);
        return item;
    }
}
