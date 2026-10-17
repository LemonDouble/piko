/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.aiFilter;

import static app.morphe.extension.shared.StringRef.str;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Utils;

@SuppressWarnings("deprecation")
public class AiFilterVaultFragment extends Fragment {
    // Fixed rate for a rough KRW estimate of the daily cost.
    private static final double KRW_PER_USD = 1400;
    private static final int DAYS_PER_MONTH = 30;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Context context = getActivity();
        String accountId = CurrentAccount.id();
        List<JSONObject> items = accountId == null ? new ArrayList<>() : FilterVault.getItems(accountId);

        ListView listView = new ListView(context);
        listView.setDivider(new ColorDrawable(borderColor(context)));
        listView.setDividerHeight(1);
        listView.addHeaderView(usageBox(context), null, false);

        if (items.isEmpty()) {
            String message = str(accountId == null ? "piko_ai_filter_account_unknown" : "piko_ai_filter_vault_empty");
            TextView emptyMessage = textView(context, message, 15, secondaryTextColor(context));
            emptyMessage.setGravity(Gravity.CENTER);
            emptyMessage.setPadding(dp(context, 16), dp(context, 48), dp(context, 16), dp(context, 48));
            listView.addHeaderView(emptyMessage, null, false);
        }

        listView.setAdapter(new VaultAdapter(context, items));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Object item = parent.getItemAtPosition(position);
            if (item instanceof JSONObject) {
                PikoUtils.openUrl("https://x.com/i/status/" + ((JSONObject) item).optString("id"), true);
            }
        });
        return listView;
    }

    private static View usageBox(Context context) {
        String[] lines = usageSummary().split("\n", 2);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        box.setBackground(roundedOutline(context, 12));
        box.addView(textView(context, lines[0], 13, primaryTextColor(context)));
        if (lines.length > 1) {
            TextView details = textView(context, lines[1], 12, secondaryTextColor(context));
            details.setPadding(0, dp(context, 4), 0, 0);
            box.addView(details);
        }

        // ListView headers ignore margins, so the box sits inside a padded frame.
        FrameLayout frame = new FrameLayout(context);
        frame.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        frame.addView(box);
        return frame;
    }

    public static void showClearDialog(Context context) {
        String accountId = CurrentAccount.id();
        if (accountId == null) {
            PikoUtils.toast(str("piko_ai_filter_account_unknown"));
            return;
        }
        int itemCount = FilterVault.getItems(accountId).size();
        new AlertDialog.Builder(context)
                .setTitle(str("piko_pref_ai_filter_clear_vault"))
                .setMessage(String.format(str("piko_ai_filter_clear_vault_confirm"), itemCount))
                .setPositiveButton(str("ok"), (dialog, which) -> {
                    FilterVault.clearItems(accountId);
                    PikoUtils.toast(str("piko_ai_filter_clear_vault_done"));
                })
                .setNegativeButton(str("cancel"), null)
                .show();
    }

    public static void showResetUsageDialog(Context context) {
        new AlertDialog.Builder(context)
                .setTitle(str("piko_pref_ai_filter_reset_usage"))
                .setMessage(str("piko_ai_filter_reset_usage_confirm"))
                .setPositiveButton(str("ok"), (dialog, which) -> {
                    FilterVault.resetUsage();
                    PikoUtils.toast(str("piko_ai_filter_reset_usage_done"));
                })
                .setNegativeButton(str("cancel"), null)
                .show();
    }

    private static String usageSummary() {
        UsageStats usage = FilterVault.getTodayUsage();
        StringBuilder summary = new StringBuilder()
                .append(String.format(Locale.US, str("piko_ai_filter_vault_usage"),
                        usage.judged, usage.cacheHits, usage.inputTokens, usage.cost, usage.cost * KRW_PER_USD))
                .append('\n')
                .append(String.format(Locale.US, str("piko_ai_filter_vault_monthly_estimate"),
                        usage.cost * DAYS_PER_MONTH, usage.cost * DAYS_PER_MONTH * KRW_PER_USD))
                .append('\n')
                .append(String.format(Locale.US, str("piko_ai_filter_vault_failures"),
                        usage.failed(), usage.timeouts, usage.httpErrors, usage.otherErrors))
                .append('\n')
                .append(String.format(Locale.US, str("piko_ai_filter_vault_latency"),
                        usage.averageJudgeMillis(), usage.averageWaitMillis()));
        if (!usage.lastError.isEmpty()) {
            summary.append('\n').append(String.format(str("piko_ai_filter_vault_last_error"), usage.lastError));
        }
        return summary.toString();
    }

    private static class VaultAdapter extends BaseAdapter {
        private final Context context;
        private final List<JSONObject> items;

        VaultAdapter(Context context, List<JSONObject> items) {
            this.context = context;
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            JSONObject item = items.get(position);

            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));

            card.addView(filterRow(item));

            String retweetedBy = item.optString("retweeted_by");
            if (!retweetedBy.isEmpty()) {
                TextView retweet = textView(context, String.format(str("piko_ai_filter_vault_retweeted_by"), retweetedBy), 12, secondaryTextColor(context));
                retweet.setPadding(0, dp(context, 10), 0, 0);
                card.addView(retweet);
            }

            TextView author = textView(context, "", 14, primaryTextColor(context));
            author.setText(authorText(item));
            author.setPadding(0, dp(context, retweetedBy.isEmpty() ? 10 : 2), 0, 0);
            card.addView(author);

            String translatedText = item.optString("translated_text");
            TextView body = textView(context, translatedText.isEmpty() ? item.optString("text") : translatedText, 15, primaryTextColor(context));
            body.setMaxLines(6);
            body.setEllipsize(TextUtils.TruncateAt.END);
            body.setPadding(0, dp(context, 4), 0, 0);
            card.addView(body);

            if (!translatedText.isEmpty()) {
                TextView original = textView(context, str("piko_ai_filter_vault_original") + " " + item.optString("text"), 12, secondaryTextColor(context));
                original.setMaxLines(3);
                original.setEllipsize(TextUtils.TruncateAt.END);
                original.setPadding(0, dp(context, 4), 0, 0);
                card.addView(original);
            }

            String quotedText = item.optString("quoted_translated_text", item.optString("quoted_text"));
            if (!quotedText.isEmpty()) {
                card.addView(quoteBox(str("piko_ai_filter_vault_quote") + " " + quotedText));
            }

            int mediaCount = item.optInt("media_count");
            if (mediaCount > 0) {
                TextView media = textView(context, String.format(str("piko_ai_filter_vault_media"), mediaCount), 12, secondaryTextColor(context));
                media.setPadding(0, dp(context, 8), 0, 0);
                card.addView(media);
            }
            return card;
        }

        // The matched filter comes first because checking why a post was hidden is the point of this screen.
        private View filterRow(JSONObject item) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView chip = textView(context, String.format(Locale.US, str("piko_ai_filter_vault_filter_chip"),
                    item.optString("filter"), item.optDouble("probability")), 12, primaryTextColor(context));
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4));
            GradientDrawable chipBackground = new GradientDrawable();
            chipBackground.setCornerRadius(dp(context, 12));
            chipBackground.setColor(Utils.resolveColor(context, "abstractColorHighlightBackground"));
            chip.setBackground(chipBackground);

            // The chip shrinks before the time does when the filter text is long.
            FrameLayout chipFrame = new FrameLayout(context);
            chipFrame.addView(chip, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(chipFrame, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            CharSequence filteredAgo = DateUtils.getRelativeTimeSpanString(
                    item.optLong("filtered_at"), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            TextView time = textView(context, filteredAgo.toString(), 12, secondaryTextColor(context));
            time.setPadding(dp(context, 8), 0, 0, 0);
            row.addView(time);
            return row;
        }

        private CharSequence authorText(JSONObject item) {
            SpannableStringBuilder text = new SpannableStringBuilder(item.optString("author_name"));
            text.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int handleStart = text.length();
            text.append("  @").append(item.optString("author_handle"));
            text.setSpan(new ForegroundColorSpan(secondaryTextColor(context)), handleStart, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return text;
        }

        private View quoteBox(String quotedText) {
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setPadding(0, dp(context, 8), 0, 0);

            View bar = new View(context);
            bar.setBackgroundColor(borderColor(context));
            box.addView(bar, new LinearLayout.LayoutParams(dp(context, 3), ViewGroup.LayoutParams.MATCH_PARENT));

            TextView quote = textView(context, quotedText, 13, secondaryTextColor(context));
            quote.setMaxLines(3);
            quote.setEllipsize(TextUtils.TruncateAt.END);
            quote.setPadding(dp(context, 10), 0, 0, 0);
            box.addView(quote, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            return box;
        }
    }

    private static GradientDrawable roundedOutline(Context context, int radiusDp) {
        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(dp(context, radiusDp));
        outline.setStroke(dp(context, 1), borderColor(context));
        return outline;
    }

    private static TextView textView(Context context, String text, int sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        return view;
    }

    private static int primaryTextColor(Context context) {
        return Utils.resolveColor(context, "coreColorPrimaryText");
    }

    private static int secondaryTextColor(Context context) {
        return Utils.resolveColor(context, "coreColorSecondaryText");
    }

    private static int borderColor(Context context) {
        return Utils.resolveColor(context, "coreColorBorder");
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }
}
