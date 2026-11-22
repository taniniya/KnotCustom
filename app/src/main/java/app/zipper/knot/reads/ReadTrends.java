package app.zipper.knot.reads;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Works out, from the read events Knot has recorded, which hours of the day each member tends to
 * read in.
 *
 * <p>Holds no Android dependency so the counting can be tested on its own; the caller passes
 * timestamps and the time zone to read the hours in.
 */
public final class ReadTrends {

  /** Width of the "reads most" window, in hours. */
  public static final int WINDOW_HOURS = 4;

  /** Number of blocks the day is split into for the short distribution line. */
  public static final int BLOCKS = 24 / WINDOW_HOURS;

  private ReadTrends() {}

  public static final class Event {
    public final String mid;
    public final String name;
    public final long timeMs;

    public Event(String mid, String name, long timeMs) {
      this.mid = mid;
      this.name = name;
      this.timeMs = timeMs;
    }
  }

  public static final class MemberTrend {
    public final String mid;
    public String name = "";
    public int total;
    public final int[] hours = new int[24];
    public long lastMs;

    MemberTrend(String mid) {
      this.mid = mid;
    }

    public String displayName() {
      return name == null || name.trim().isEmpty() ? mid : name;
    }
  }

  public static List<MemberTrend> compute(List<Event> events) {
    return compute(events, TimeZone.getDefault());
  }

  public static List<MemberTrend> compute(List<Event> events, TimeZone zone) {
    Map<String, MemberTrend> byMid = new LinkedHashMap<>();
    if (events != null) {
      Calendar calendar = Calendar.getInstance(zone != null ? zone : TimeZone.getDefault());
      for (Event event : events) {
        if (event == null || event.mid == null || event.timeMs <= 0) continue;
        MemberTrend trend = byMid.get(event.mid);
        if (trend == null) {
          trend = new MemberTrend(event.mid);
          byMid.put(event.mid, trend);
        }
        if (event.name != null && !event.name.trim().isEmpty()) trend.name = event.name.trim();
        calendar.setTimeInMillis(event.timeMs);
        trend.hours[calendar.get(Calendar.HOUR_OF_DAY)]++;
        trend.total++;
        if (event.timeMs > trend.lastMs) trend.lastMs = event.timeMs;
      }
    }

    List<MemberTrend> trends = new ArrayList<>(byMid.values());
    Collections.sort(
        trends,
        (a, b) -> {
          if (a.total != b.total) return Integer.compare(b.total, a.total);
          if (a.lastMs != b.lastMs) return Long.compare(b.lastMs, a.lastMs);
          return a.displayName().compareTo(b.displayName());
        });
    return trends;
  }

  /** Start hour of the four-hour window with the most reads. */
  public static int busiestWindowStart(MemberTrend trend) {
    int bestStart = 0;
    int bestCount = -1;
    for (int start = 0; start < 24; start++) {
      int count = windowCount(trend, start);
      if (count > bestCount) {
        bestCount = count;
        bestStart = start;
        continue;
      }
      // A tie goes to the earliest window that starts on an hour they actually read, so the
      // label points at the reading itself rather than the quiet hours just before it
      if (count == bestCount && readsAt(trend, start) && !readsAt(trend, bestStart)) {
        bestStart = start;
      }
    }
    return bestStart;
  }

  private static boolean readsAt(MemberTrend trend, int hour) {
    return trend != null && trend.hours[hour] > 0;
  }

  public static int busiestWindowCount(MemberTrend trend) {
    return trend == null ? 0 : windowCount(trend, busiestWindowStart(trend));
  }

  public static int windowCount(MemberTrend trend, int startHour) {
    if (trend == null) return 0;
    int count = 0;
    for (int offset = 0; offset < WINDOW_HOURS; offset++) {
      count += trend.hours[((startHour + offset) % 24 + 24) % 24];
    }
    return count;
  }

  /** "20〜23時台" for the window beginning at 20, "22〜1時台" for the one crossing midnight. */
  public static String windowLabel(int startHour) {
    int start = ((startHour % 24) + 24) % 24;
    int end = (start + WINDOW_HOURS - 1) % 24;
    return start + "〜" + end + "時台";
  }

  /** Reads per four-hour block, from 0-3 through 20-23. */
  public static int[] blockCounts(MemberTrend trend) {
    int[] counts = new int[BLOCKS];
    if (trend == null) return counts;
    for (int hour = 0; hour < 24; hour++) {
      counts[hour / WINDOW_HOURS] += trend.hours[hour];
    }
    return counts;
  }
}
