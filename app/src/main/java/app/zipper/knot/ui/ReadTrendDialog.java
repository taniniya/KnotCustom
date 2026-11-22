package app.zipper.knot.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.zipper.knot.Knot;
import app.zipper.knot.R;
import app.zipper.knot.SettingsStore;
import app.zipper.knot.reads.ReadTrendSource;
import app.zipper.knot.reads.ReadTrends;
import app.zipper.knot.utils.LineTheme;
import app.zipper.knot.utils.ModuleResources;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "When do they read?": the hours each member tends to read in, built from the read history Knot
 * already records.
 */
public final class ReadTrendDialog {

  private static final String LAST_FMT = "M/d HH:mm";

  private ReadTrendDialog() {}

  /** Everyone who read in this chat, or in every chat when {@code chatId} is null. */
  public static void show(Activity activity, String chatId) {
    if (activity == null || activity.isFinishing()) return;
    try {
      List<ReadTrends.MemberTrend> trends = ReadTrends.compute(ReadTrendSource.events(chatId));

      LinearLayout box = container(activity);
      TextView scope = new TextView(activity);
      scope.setText(
          ModuleResources.get(
              chatId != null ? R.string.read_trends_scope_chat : R.string.read_trends_scope_all));
      scope.setTextSize(12);
      scope.setTextColor(LineTheme.secondaryTextColor(activity));
      scope.setPadding(0, 0, 0, 8);
      box.addView(scope);

      if (trends.isEmpty()) {
        box.addView(emptyText(activity));
      } else {
        for (ReadTrends.MemberTrend trend : trends) box.addView(memberView(activity, trend));
      }
      box.addView(hintText(activity));
      present(activity, box);
    } catch (Throwable t) {
      Knot.log("Knot: read trends failed: " + t);
    }
  }

  /** One member's times, opened by tapping their row in the "Read by" dialog. */
  public static void showForMember(Activity activity, String chatId, String mid, String name) {
    if (activity == null || activity.isFinishing()) return;
    try {
      List<ReadTrends.MemberTrend> all = ReadTrends.compute(ReadTrendSource.events(chatId));
      ReadTrends.MemberTrend trend = null;
      for (ReadTrends.MemberTrend candidate : all) {
        if (candidate.mid != null && candidate.mid.equals(mid)) {
          trend = candidate;
          break;
        }
      }

      LinearLayout box = container(activity);
      TextView title = new TextView(activity);
      String display = trend != null ? trend.displayName() : (name == null ? "" : name);
      title.setText(ModuleResources.get(R.string.read_trends_member_title, display));
      title.setTextSize(15);
      title.setTypeface(null, Typeface.BOLD);
      title.setTextColor(LineTheme.primaryTextColor(activity));
      title.setPadding(0, 0, 0, 8);
      box.addView(title);

      if (trend == null) box.addView(emptyText(activity));
      else box.addView(memberView(activity, trend));
      box.addView(hintText(activity));
      present(activity, box);
    } catch (Throwable t) {
      Knot.log("Knot: read trend member failed: " + t);
    }
  }

  private static void present(Activity activity, LinearLayout box) {
    ScrollView scroller = new ScrollView(activity);
    scroller.addView(box);
    AlertDialog dialog =
        new AlertDialog.Builder(activity, LineTheme.dialogTheme(activity))
            .setTitle(ModuleResources.get(R.string.read_trends_title))
            .setView(scroller)
            .setPositiveButton(ModuleResources.get(R.string.common_close), null)
            .show();
    LineTheme.applyDialogColors(dialog, activity);
  }

  private static LinearLayout container(Activity activity) {
    LinearLayout box = new LinearLayout(activity);
    box.setOrientation(LinearLayout.VERTICAL);
    int side = Math.round(20 * activity.getResources().getDisplayMetrics().density);
    box.setPadding(side, side / 2, side, side);
    return box;
  }

  private static TextView emptyText(Activity activity) {
    TextView empty = new TextView(activity);
    empty.setText(ModuleResources.get(R.string.read_trends_empty));
    empty.setTextSize(13);
    empty.setTextColor(LineTheme.secondaryTextColor(activity));
    empty.setPadding(0, 16, 0, 16);
    return empty;
  }

  private static TextView hintText(Activity activity) {
    TextView hint = new TextView(activity);
    boolean recording = SettingsStore.get("record_read_history", false);
    hint.setText(
        recording
            ? ModuleResources.get(R.string.read_trends_hint_on)
            : ModuleResources.get(R.string.read_trends_hint_off));
    hint.setTextSize(11);
    hint.setTextColor(LineTheme.secondaryTextColor(activity));
    hint.setPadding(0, 14, 0, 0);
    return hint;
  }

  private static LinearLayout memberView(Activity activity, ReadTrends.MemberTrend trend) {
    LinearLayout box = new LinearLayout(activity);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(0, 8, 0, 10);

    LinearLayout nameRow = new LinearLayout(activity);
    nameRow.setOrientation(LinearLayout.HORIZONTAL);

    TextView name = new TextView(activity);
    name.setText(trend.displayName());
    name.setTextSize(14);
    name.setTypeface(null, Typeface.BOLD);
    name.setTextColor(LineTheme.primaryTextColor(activity));
    nameRow.addView(
        name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

    TextView count = new TextView(activity);
    count.setText(ModuleResources.get(R.string.read_trends_count, trend.total));
    count.setTextSize(12);
    count.setTextColor(LineTheme.secondaryTextColor(activity));
    nameRow.addView(count);
    box.addView(nameRow);

    int start = ReadTrends.busiestWindowStart(trend);
    int windowCount = ReadTrends.busiestWindowCount(trend);
    box.addView(
        detail(
            activity,
            ModuleResources.get(
                R.string.read_trends_window, ReadTrends.windowLabel(start), windowCount),
            true));
    box.addView(detail(activity, distribution(trend), false));
    if (trend.lastMs > 0) {
      box.addView(
          detail(
              activity,
              ModuleResources.get(R.string.read_trends_last, formatLast(trend.lastMs)),
              false));
    }
    return box;
  }

  private static TextView detail(Activity activity, String text, boolean accent) {
    TextView view = new TextView(activity);
    view.setText(text);
    view.setTextSize(12);
    view.setTextColor(
        accent ? LineTheme.accentGreen(activity) : LineTheme.secondaryTextColor(activity));
    view.setPadding(0, 2, 0, 0);
    return view;
  }

  // "0-3:1 4-7:3 …" so the whole day fits on one line
  private static String distribution(ReadTrends.MemberTrend trend) {
    int[] counts = ReadTrends.blockCounts(trend);
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < counts.length; i++) {
      int from = i * ReadTrends.WINDOW_HOURS;
      int to = from + ReadTrends.WINDOW_HOURS - 1;
      parts.add(from + "-" + to + ":" + counts[i]);
    }
    return ModuleResources.get(R.string.read_trends_distribution) + " " + String.join("  ", parts);
  }

  private static String formatLast(long millis) {
    return new SimpleDateFormat(LAST_FMT, Locale.getDefault()).format(new Date(millis));
  }
}
