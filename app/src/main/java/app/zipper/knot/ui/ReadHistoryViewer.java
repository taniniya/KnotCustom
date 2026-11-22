package app.zipper.knot.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import app.zipper.knot.Knot;
import app.zipper.knot.R;
import app.zipper.knot.SettingsStore;
import app.zipper.knot.utils.ChatJumpUtil;
import app.zipper.knot.utils.LineDBUtils;
import app.zipper.knot.utils.LineTheme;
import app.zipper.knot.utils.ModuleResources;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.json.JSONObject;

public class ReadHistoryViewer {

  private static final int READER_COLLAPSE_THRESHOLD = 5;

  private static final class ReaderEntry {
    final String mid;
    final String name;
    final String time;

    ReaderEntry(String mid, String name, String time) {
      this.mid = mid;
      this.name = name;
      this.time = time;
    }
  }

  private static final class MessageEntry {
    final String chatId;
    final String msgId;
    final long sortKey;
    final boolean numericId;
    final String text;
    final List<ReaderEntry> readers;
    boolean expanded;

    MessageEntry(String chatId, String msgId, String text, List<ReaderEntry> readers) {
      this.chatId = chatId;
      this.msgId = msgId;
      this.text = text;
      this.readers = readers;
      long parsed = 0L;
      boolean numeric = false;
      try {
        parsed = Long.parseLong(msgId);
        numeric = true;
      } catch (NumberFormatException ignored) {
      }
      this.sortKey = parsed;
      this.numericId = numeric;
    }
  }

  public static void show(Activity activity, String targetChatId) {
    try {
      LineTheme.invalidate();
      List<MessageEntry> entries = loadEntries(targetChatId);

      String chatName = LineDBUtils.resolveChatName(targetChatId);

      ListView listView = new ListView(activity);
      listView.setPadding(40, 20, 40, 40);
      listView.setClipToPadding(false);
      listView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
      listView.setDivider(null);
      listView.setDividerHeight(0);
      listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
      listView.setCacheColorHint(Color.TRANSPARENT);

      TextView header = new TextView(activity);
      header.setText(
          chatName != null ? chatName : ModuleResources.get(R.string.read_history_title));
      header.setTextSize(18);
      header.setTextColor(LineTheme.primaryTextColor(activity));
      header.setPadding(0, 20, 0, 30);
      View headerRow = buildHeaderRow(activity, header, targetChatId);
      listView.addHeaderView(headerRow, null, false);

      final AlertDialog[] dialogRef = new AlertDialog[1];

      boolean found = !entries.isEmpty();
      if (!found) {
        TextView empty = new TextView(activity);
        empty.setText(ModuleResources.get(R.string.read_history_empty));
        empty.setPadding(0, 100, 0, 100);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(LineTheme.secondaryTextColor(activity));
        listView.addHeaderView(empty, null, false);
      }

      listView.setAdapter(
          new BaseAdapter() {
            @Override
            public int getCount() {
              return entries.size();
            }

            @Override
            public MessageEntry getItem(int position) {
              return entries.get(position);
            }

            @Override
            public long getItemId(int position) {
              return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
              return bindMessageCard(activity, convertView, entries.get(position), dialogRef);
            }
          });

      int themeId = LineTheme.dialogTheme(activity);
      AlertDialog.Builder builder = new AlertDialog.Builder(activity, themeId);
      builder.setView(listView);
      builder.setPositiveButton(ModuleResources.get(R.string.common_close), null);
      if (targetChatId != null && found) {
        builder.setNeutralButton(
            ModuleResources.get(R.string.read_history_delete),
            (dialog, which) ->
                LineTheme.applyDialogColors(
                    new AlertDialog.Builder(activity, themeId)
                        .setTitle(ModuleResources.get(R.string.read_history_delete_confirm_title))
                        .setMessage(ModuleResources.get(R.string.read_history_delete_confirm_msg))
                        .setPositiveButton(
                            ModuleResources.get(R.string.settings_yes),
                            (d, w) -> clearChatHistory(targetChatId))
                        .setNegativeButton(ModuleResources.get(R.string.settings_cancel), null)
                        .show(),
                    activity));
      }
      AlertDialog dialog = builder.create();
      dialogRef[0] = dialog;
      dialog.show();
      LineTheme.applyDialogColors(dialog, activity);

    } catch (Throwable t) {
      Knot.log("Knot: error: " + t.getMessage());
    }
  }

  // One message on its own: the long-press "Read by" entry of the message menu opens this.
  public static void showForMessage(Context context, String chatId, String msgId) {
    try {
      Activity activity = resolveActivity(context);
      if (activity == null || msgId == null) return;
      LineTheme.invalidate();

      MessageDetail detail = loadMessageDetail(chatId, msgId);

      ScrollView scrollView = new ScrollView(activity);
      LinearLayout container = new LinearLayout(activity);
      container.setOrientation(LinearLayout.VERTICAL);
      container.setPadding(48, 24, 48, 40);
      scrollView.addView(container);

      TextView header = new TextView(activity);
      header.setText(ModuleResources.get(R.string.read_users_title));
      header.setTextSize(18);
      header.setTextColor(LineTheme.primaryTextColor(activity));
      header.setPadding(0, 12, 0, 16);
      container.addView(header);

      if (detail != null) container.addView(buildMessagePreview(activity, detail));

      final String scopeChatId = detail != null ? detail.chatId : chatId;

      if (detail == null || detail.readers.isEmpty()) {
        TextView empty = new TextView(activity);
        empty.setText(ModuleResources.get(R.string.read_users_empty));
        empty.setPadding(0, 40, 0, 40);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(LineTheme.secondaryTextColor(activity));
        container.addView(empty);
      } else {
        for (ReaderEntry reader : detail.readers) {
          container.addView(buildReaderRow(activity, reader, scopeChatId));
        }
        container.addView(buildTrendRow(activity, scopeChatId));
      }

      if (!SettingsStore.get("record_read_history", false)) {
        TextView hint = new TextView(activity);
        hint.setText(ModuleResources.get(R.string.read_users_setting_hint));
        hint.setTextSize(12);
        hint.setPadding(0, 24, 0, 0);
        hint.setTextColor(LineTheme.secondaryTextColor(activity));
        container.addView(hint);
      }

      int themeId = LineTheme.dialogTheme(activity);
      AlertDialog.Builder builder =
          new AlertDialog.Builder(activity, themeId)
              .setView(scrollView)
              .setPositiveButton(ModuleResources.get(R.string.common_close), null);
      String resolvedChatId = detail != null ? detail.chatId : chatId;
      if (resolvedChatId != null) {
        final String openChatId = resolvedChatId;
        builder.setNeutralButton(
            ModuleResources.get(R.string.read_history_title),
            (d, which) -> show(activity, openChatId));
      }
      AlertDialog dialog = builder.create();
      dialog.show();
      LineTheme.applyDialogColors(dialog, activity);
    } catch (Throwable t) {
      Knot.log("Knot: read users viewer error: " + t);
    }
  }

  private static final class MessageDetail {
    final String chatId;
    final String text;
    final String senderName;
    final String time;
    final List<ReaderEntry> readers = new ArrayList<>();

    MessageDetail(String chatId, String text, String senderName, String time) {
      this.chatId = chatId;
      this.text = text;
      this.senderName = senderName;
      this.time = time;
    }
  }

  private static MessageDetail loadMessageDetail(String chatId, String msgId) {
    synchronized (SettingsStore.class) {
      JSONObject historyJson = SettingsStore.loadReadHistory();
      JSONObject chats = historyJson.optJSONObject("c");
      if (chats == null) return null;

      JSONObject chat = chatId != null ? chats.optJSONObject(chatId) : null;
      String resolvedChatId = chatId;
      if (chat == null) {
        Iterator<String> chatKeys = chats.keys();
        while (chatKeys.hasNext()) {
          String chatKey = chatKeys.next();
          JSONObject candidate = chats.optJSONObject(chatKey);
          JSONObject messages = candidate == null ? null : candidate.optJSONObject("m");
          if (messages != null && messages.has(msgId)) {
            chat = candidate;
            resolvedChatId = chatKey;
            break;
          }
        }
      }
      if (chat == null) return null;

      JSONObject messages = chat.optJSONObject("m");
      JSONObject msg = messages == null ? null : messages.optJSONObject(msgId);
      if (msg == null) return null;

      MessageDetail detail =
          new MessageDetail(
              resolvedChatId,
              msg.optString("c", ""),
              msg.optString("sn", ""),
              msg.optString("ct", ""));

      JSONObject readersJson = msg.optJSONObject("r");
      if (readersJson != null) {
        Iterator<String> rKeys = readersJson.keys();
        while (rKeys.hasNext()) {
          String readerMid = rKeys.next();
          JSONObject reader = readersJson.optJSONObject(readerMid);
          if (reader != null) {
            detail.readers.add(
                new ReaderEntry(
                    readerMid, reader.optString("n", "Unknown"), reader.optString("t", "")));
          }
        }
      }
      Collections.sort(detail.readers, (a, b) -> a.time.compareTo(b.time));
      return detail;
    }
  }

  private static View buildMessagePreview(Activity activity, MessageDetail detail) {
    LinearLayout box = new LinearLayout(activity);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(24, 16, 24, 16);

    GradientDrawable gd = new GradientDrawable();
    gd.setColor(LineTheme.cardColor(activity));
    gd.setCornerRadius(12f);
    box.setBackground(gd);

    TextView text = new TextView(activity);
    text.setText(
        detail.text != null && !detail.text.isEmpty()
            ? detail.text
            : ModuleResources.get(R.string.read_history_unknown_msg));
    text.setTextSize(15);
    text.setTextColor(LineTheme.primaryTextColor(activity));
    text.setMaxLines(3);
    box.addView(text);

    String meta =
        (detail.senderName == null || detail.senderName.isEmpty() ? "" : detail.senderName)
            + (detail.time == null || detail.time.isEmpty() ? "" : "  " + detail.time);
    if (!meta.trim().isEmpty()) {
      TextView metaText = new TextView(activity);
      metaText.setText(meta.trim());
      metaText.setTextSize(12);
      metaText.setTextColor(LineTheme.secondaryTextColor(activity));
      metaText.setPadding(0, 6, 0, 0);
      box.addView(metaText);
    }

    TextView count = new TextView(activity);
    if (detail.readers.isEmpty()) return box;
    count.setText(ModuleResources.get(R.string.read_users_count, detail.readers.size()));
    count.setTextSize(13);
    count.setTypeface(null, Typeface.BOLD);
    count.setTextColor(LineTheme.secondaryTextColor(activity));
    count.setPadding(0, 16, 0, 4);
    box.addView(count);

    return box;
  }

  private static Activity resolveActivity(Context context) {
    while (context instanceof ContextWrapper) {
      if (context instanceof Activity) return (Activity) context;
      context = ((ContextWrapper) context).getBaseContext();
    }
    return null;
  }

  private static List<MessageEntry> loadEntries(String targetChatId) {
    List<MessageEntry> entries = new ArrayList<>();
    synchronized (SettingsStore.class) {
      JSONObject historyJson = SettingsStore.loadReadHistory();
      JSONObject chats = historyJson.optJSONObject("c");
      if (chats == null) return entries;

      if (targetChatId != null) {
        collectChatEntries(chats.optJSONObject(targetChatId), targetChatId, entries);
      } else {
        Iterator<String> chatKeys = chats.keys();
        while (chatKeys.hasNext()) {
          String chatKey = chatKeys.next();
          collectChatEntries(chats.optJSONObject(chatKey), chatKey, entries);
        }
      }
    }
    return entries;
  }

  private static void collectChatEntries(
      JSONObject chat, String chatId, List<MessageEntry> outEntries) {
    if (chat == null) return;
    JSONObject messages = chat.optJSONObject("m");
    if (messages == null) return;

    List<MessageEntry> chatEntries = new ArrayList<>();
    Iterator<String> keys = messages.keys();
    while (keys.hasNext()) {
      String msgId = keys.next();
      JSONObject msg = messages.optJSONObject(msgId);
      if (msg == null) continue;

      List<ReaderEntry> readers = new ArrayList<>();
      JSONObject readersJson = msg.optJSONObject("r");
      if (readersJson != null) {
        Iterator<String> rKeys = readersJson.keys();
        while (rKeys.hasNext()) {
          String readerMid = rKeys.next();
          JSONObject reader = readersJson.optJSONObject(readerMid);
          if (reader != null) {
            readers.add(
                new ReaderEntry(
                    readerMid, reader.optString("n", "Unknown"), reader.optString("t", "")));
          }
        }
      }
      chatEntries.add(new MessageEntry(chatId, msgId, msg.optString("c", ""), readers));
    }

    Collections.sort(
        chatEntries,
        (a, b) -> {
          if (a.numericId && b.numericId) {
            return Long.compare(b.sortKey, a.sortKey);
          }
          return b.msgId.compareTo(a.msgId);
        });
    outEntries.addAll(chatEntries);
  }

  private static View bindMessageCard(
      Activity activity, View convertView, MessageEntry entry, AlertDialog[] dialogRef) {
    FrameLayout wrapper;
    LinearLayout card;
    TextView contentText;

    if (convertView instanceof FrameLayout) {
      wrapper = (FrameLayout) convertView;
      card = (LinearLayout) wrapper.getChildAt(0);
      contentText = (TextView) card.getChildAt(0);
      if (card.getChildCount() > 1) {
        card.removeViews(1, card.getChildCount() - 1);
      }
    } else {
      wrapper = new FrameLayout(activity);
      wrapper.setPadding(0, 5, 0, 20);

      card = new LinearLayout(activity);
      card.setOrientation(LinearLayout.VERTICAL);
      card.setPadding(25, 20, 25, 20);

      GradientDrawable gd = new GradientDrawable();
      gd.setColor(LineTheme.cardColor(activity));
      gd.setCornerRadius(12f);
      card.setBackground(gd);

      contentText = new TextView(activity);
      contentText.setTextColor(LineTheme.primaryTextColor(activity));
      contentText.setTextSize(17);
      contentText.setPadding(0, 0, 0, 15);
      card.addView(contentText);

      wrapper.addView(
          card,
          new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    String messageText = entry.text;
    contentText.setText(
        messageText != null && !messageText.isEmpty()
            ? messageText
            : ModuleResources.get(R.string.read_history_unknown_msg));

    if (!entry.readers.isEmpty()) {
      addReaderList(activity, card, entry);
    }

    card.setOnClickListener(
        v -> {
          boolean ok = ChatJumpUtil.jumpToMessage(activity, entry.chatId, entry.msgId);
          if (ok && dialogRef[0] != null) {
            dialogRef[0].dismiss();
          }
        });

    // Long-press a card to see just that message's readers
    card.setOnLongClickListener(
        v -> {
          showForMessage(activity, entry.chatId, entry.msgId);
          return true;
        });

    return wrapper;
  }

  private static void addReaderList(Activity activity, LinearLayout card, MessageEntry entry) {
    List<ReaderEntry> readers = entry.readers;
    int initialCount = Math.min(readers.size(), READER_COLLAPSE_THRESHOLD);
    for (int i = 0; i < initialCount; i++) {
      card.addView(buildReaderRow(activity, readers.get(i), entry.chatId));
    }

    if (readers.size() > READER_COLLAPSE_THRESHOLD) {
      addReaderToggle(
          activity,
          card,
          entry,
          entry.chatId,
          readers.subList(READER_COLLAPSE_THRESHOLD, readers.size()));
    }
  }

  private static void addReaderToggle(
      Activity activity,
      LinearLayout card,
      MessageEntry entry,
      String chatId,
      List<ReaderEntry> extraReaders) {
    final String showAll = ModuleResources.get(R.string.read_history_show_all, extraReaders.size());
    final List<View> overflowViews = new ArrayList<>();
    if (entry.expanded) {
      for (ReaderEntry reader : extraReaders) {
        View row = buildReaderRow(activity, reader, chatId);
        overflowViews.add(row);
        card.addView(row);
      }
    }

    TextView toggle = new TextView(activity);
    toggle.setText(entry.expanded ? ModuleResources.get(R.string.read_history_collapse) : showAll);
    toggle.setTextSize(13);
    toggle.setTypeface(null, Typeface.BOLD);
    toggle.setTextColor(LineTheme.secondaryTextColor(activity));
    toggle.setPadding(0, 8, 0, 4);

    toggle.setOnClickListener(
        v -> {
          entry.expanded = !entry.expanded;
          if (overflowViews.isEmpty()) {
            int insertIndex = card.indexOfChild(toggle);
            for (ReaderEntry reader : extraReaders) {
              View row = buildReaderRow(activity, reader, chatId);
              overflowViews.add(row);
              card.addView(row, insertIndex++);
            }
          } else {
            for (View row : overflowViews) {
              row.setVisibility(entry.expanded ? View.VISIBLE : View.GONE);
            }
          }
          toggle.setText(
              entry.expanded ? ModuleResources.get(R.string.read_history_collapse) : showAll);
        });
    card.addView(toggle);
  }

  private static View buildReaderRow(Activity activity, ReaderEntry reader, String chatId) {
    LinearLayout detailRow = new LinearLayout(activity);
    detailRow.setOrientation(LinearLayout.HORIZONTAL);
    detailRow.setGravity(Gravity.CENTER_VERTICAL);
    detailRow.setPadding(0, 5, 0, 5);

    TextView nameText = new TextView(activity);
    nameText.setText(reader.name);
    nameText.setTextColor(LineTheme.secondaryTextColor(activity));
    nameText.setTextSize(15);
    detailRow.addView(nameText);

    TextView timeText = new TextView(activity);
    timeText.setText(reader.time);
    timeText.setTextSize(12);
    timeText.setTextColor(LineTheme.secondaryTextColor(activity));
    timeText.setPadding(20, 0, 0, 0);
    detailRow.addView(timeText);

    // Tapping a reader shows the hours that person tends to read in
    if (reader.mid != null && !reader.mid.isEmpty()) {
      detailRow.setOnClickListener(
          v -> ReadTrendDialog.showForMember(activity, chatId, reader.mid, reader.name));
    }
    return detailRow;
  }

  private static View buildTrendRow(Activity activity, String chatId) {
    TextView trend = new TextView(activity);
    trend.setText(ModuleResources.get(R.string.read_trends_button));
    trend.setTextSize(13);
    trend.setTypeface(null, Typeface.BOLD);
    trend.setTextColor(LineTheme.accentGreen(activity));
    trend.setPadding(0, 14, 0, 4);
    trend.setOnClickListener(v -> ReadTrendDialog.show(activity, chatId));
    return trend;
  }

  private static View buildHeaderRow(Activity activity, TextView title, String chatId) {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.addView(
        title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

    TextView trends = new TextView(activity);
    trends.setText(ModuleResources.get(R.string.read_trends_button));
    trends.setTextSize(12);
    trends.setTextColor(LineTheme.accentGreen(activity));
    trends.setPadding(20, 20, 0, 26);
    trends.setOnClickListener(v -> ReadTrendDialog.show(activity, chatId));
    row.addView(trends);
    return row;
  }

  private static void clearChatHistory(String chatId) {
    synchronized (SettingsStore.class) {
      try {
        JSONObject historyJson = SettingsStore.loadReadHistory();
        JSONObject chats = historyJson.optJSONObject("c");
        if (chats != null && chats.has(chatId)) {
          chats.remove(chatId);
          SettingsStore.saveReadHistory(historyJson);
        }
      } catch (Exception e) {
      }
    }
  }
}
