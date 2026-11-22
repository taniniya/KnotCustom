package app.zipper.knot.reads;

import app.zipper.knot.SettingsStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;

/** Reads the recorded read events out of Knot's read history, for the trend screens. */
public final class ReadTrendSource {

  private static final String TIME_FMT = "yyyy-MM-dd HH:mm:ss";

  private ReadTrendSource() {}

  /**
   * Read events of one chat, or of every chat when {@code chatId} is null.
   *
   * <p>One read marks several messages with the same timestamp, so entries that share a member and
   * a time count once: the count then means "how many times they read", not "how many messages".
   */
  public static List<ReadTrends.Event> events(String chatId) {
    List<ReadTrends.Event> events = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    SimpleDateFormat format = new SimpleDateFormat(TIME_FMT, Locale.getDefault());

    synchronized (SettingsStore.class) {
      JSONObject history = SettingsStore.loadReadHistory();
      JSONObject chats = history.optJSONObject("c");
      if (chats == null) return events;

      Iterator<String> chatKeys = chats.keys();
      while (chatKeys.hasNext()) {
        String key = chatKeys.next();
        if (chatId != null && !chatId.equals(key)) continue;
        JSONObject chat = chats.optJSONObject(key);
        if (chat == null) continue;
        JSONObject messages = chat.optJSONObject("m");
        if (messages == null) continue;

        Iterator<String> messageKeys = messages.keys();
        while (messageKeys.hasNext()) {
          JSONObject message = messages.optJSONObject(messageKeys.next());
          if (message == null) continue;
          JSONObject readers = message.optJSONObject("r");
          if (readers == null) continue;

          Iterator<String> readerKeys = readers.keys();
          while (readerKeys.hasNext()) {
            String mid = readerKeys.next();
            JSONObject info = readers.optJSONObject(mid);
            if (info == null) continue;
            String time = info.optString("t", "");
            if (time.isEmpty()) continue;
            if (!seen.add(mid + "|" + time)) continue;
            long millis = parseTime(format, time);
            if (millis <= 0) continue;
            events.add(new ReadTrends.Event(mid, info.optString("n", ""), millis));
          }
        }
      }
    }
    return events;
  }

  public static boolean hasAnyHistory() {
    JSONObject history;
    synchronized (SettingsStore.class) {
      history = SettingsStore.loadReadHistory();
    }
    JSONObject chats = history.optJSONObject("c");
    return chats != null && chats.length() > 0;
  }

  private static long parseTime(SimpleDateFormat format, String text) {
    try {
      java.util.Date date = format.parse(text);
      return date != null ? date.getTime() : 0L;
    } catch (Throwable t) {
      return 0L;
    }
  }
}
