/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.aiFilter;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

// Clef is served through OpenRouter's Decisions API, not the chat completions API.
// Reference: https://openrouter.ai/docs/api/api-reference/alphadecisions/submit-a-decisions-questions-and-answers-request
public class ClefClient {
    private static final String ENDPOINT = "https://openrouter.ai/api/alpha/decisions";
    private static final String MODEL = "cloudflare/clef-flash";

    private static final String TOPIC_QUESTION = "topic";
    private static final String NONE_OPTION = "none";

    // Probabilities of one choice question; they sum to about 1 together with noneProbability.
    public static class TopicProbabilities {
        // Index matches the rule list passed to judge().
        public final double[] ruleProbabilities;
        public final double noneProbability;

        TopicProbabilities(double[] ruleProbabilities, double noneProbability) {
            this.ruleProbabilities = ruleProbabilities;
            this.noneProbability = noneProbability;
        }
    }

    public static class Judgement {
        public final TopicProbabilities topics;
        public final double cost;
        public final long inputTokens;
        public final long elapsedMillis;

        Judgement(TopicProbabilities topics, double cost, long inputTokens, long elapsedMillis) {
            this.topics = topics;
            this.cost = cost;
            this.inputTokens = inputTokens;
            this.elapsedMillis = elapsedMillis;
        }
    }

    public static class HttpException extends IOException {
        public final int statusCode;

        HttpException(int statusCode, String body) {
            super("HTTP " + statusCode + " " + body);
            this.statusCode = statusCode;
        }
    }

    // All rules go into one choice question: measured on clef-flash, 5 rules cost 267 input tokens
    // this way versus 553 as separate yes/no questions, with near-identical judgements.
    public static Judgement judge(TimelineTweet tweet, List<FilterRule> rules, String apiKey, int timeoutMillis) throws Exception {
        long startedAt = System.currentTimeMillis();
        JSONObject criteria = new JSONObject().put(NONE_OPTION, "None of the descriptions");
        for (int index = 0; index < rules.size(); index++) {
            criteria.put(optionId(index), rules.get(index).text);
        }

        JSONObject request = new JSONObject()
                .put("model", MODEL)
                .put("provider", new JSONObject().put("data_collection", "deny"))
                .put("state", tweet.toClefState())
                .put("questions", new JSONObject().put(TOPIC_QUESTION, new JSONObject()
                        .put("type", "choice")
                        .put("instructions", "Which description does this post match?")
                        .put("criteria", criteria)));

        JSONObject response = post(request, apiKey, timeoutMillis);

        JSONObject probabilities = response.getJSONObject("answers")
                .getJSONObject(TOPIC_QUESTION)
                .getJSONObject("probabilities");
        double[] ruleProbabilities = new double[rules.size()];
        for (int index = 0; index < rules.size(); index++) {
            ruleProbabilities[index] = probabilities.optDouble(optionId(index), 0);
        }
        TopicProbabilities topics = new TopicProbabilities(ruleProbabilities, probabilities.optDouble(NONE_OPTION, 1));

        JSONObject usage = response.optJSONObject("usage");
        double cost = usage == null ? 0 : usage.optDouble("cost", 0);
        long inputTokens = usage == null ? 0 : usage.optLong("input_tokens", 0);
        return new Judgement(topics, cost, inputTokens, System.currentTimeMillis() - startedAt);
    }

    private static String optionId(int ruleIndex) {
        return "rule_" + ruleIndex;
    }

    // disconnect() is not called: reading the body to the end and closing it lets Android reuse the TLS connection.
    private static JSONObject post(JSONObject request, String apiKey, int timeoutMillis) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json");

        try (OutputStream body = connection.getOutputStream()) {
            body.write(request.toString().getBytes(StandardCharsets.UTF_8));
        }

        int statusCode = connection.getResponseCode();
        if (statusCode != HttpURLConnection.HTTP_OK) {
            throw new HttpException(statusCode, readFully(connection.getErrorStream()));
        }
        return new JSONObject(readFully(connection.getInputStream()));
    }

    private static String readFully(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
