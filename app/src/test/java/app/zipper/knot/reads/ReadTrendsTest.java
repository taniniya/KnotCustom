package app.zipper.knot.reads;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;

public class ReadTrendsTest {

  private static final TimeZone TOKYO = TimeZone.getTimeZone("Asia/Tokyo");

  // 2026-11-21 20:30 JST
  private static final long BASE = 1795260600000L;
  private static final long HOUR = 60L * 60L * 1000L;

  @Test
  public void hoursAreCountedPerMember() {
    List<ReadTrends.Event> events = new ArrayList<>();
    events.add(new ReadTrends.Event("m1", "A", BASE));
    events.add(new ReadTrends.Event("m1", "A", BASE + HOUR));
    events.add(new ReadTrends.Event("m2", "B", BASE));

    List<ReadTrends.MemberTrend> trends = ReadTrends.compute(events, TOKYO);
    assertEquals(2, trends.size());
    assertEquals("m1", trends.get(0).mid);
    assertEquals(2, trends.get(0).total);
    assertEquals(1, trends.get(1).total);
  }

  @Test
  public void busiestWindowIsFourHours() {
    List<ReadTrends.Event> events = new ArrayList<>();
    for (int i = 0; i < 3; i++) events.add(new ReadTrends.Event("m1", "A", BASE + i * HOUR));
    ReadTrends.MemberTrend trend = ReadTrends.compute(events, TOKYO).get(0);

    assertEquals(20, ReadTrends.busiestWindowStart(trend));
    assertEquals(3, ReadTrends.busiestWindowCount(trend));
    assertEquals("20〜23時台", ReadTrends.windowLabel(20));
  }

  @Test
  public void windowCrossingMidnightIsLabelled() {
    assertEquals("22〜1時台", ReadTrends.windowLabel(22));
    assertEquals("0〜3時台", ReadTrends.windowLabel(0));
  }

  @Test
  public void windowCountWrapsAroundMidnight() {
    List<ReadTrends.Event> events = new ArrayList<>();
    events.add(new ReadTrends.Event("m1", "A", BASE + 3 * HOUR)); // 23:30
    events.add(new ReadTrends.Event("m1", "A", BASE + 4 * HOUR)); // 00:30 next day
    ReadTrends.MemberTrend trend = ReadTrends.compute(events, TOKYO).get(0);
    assertEquals(2, ReadTrends.windowCount(trend, 22));
  }

  @Test
  public void distributionCountsFourHourBlocks() {
    List<ReadTrends.Event> events = new ArrayList<>();
    events.add(new ReadTrends.Event("m1", "A", BASE));
    events.add(new ReadTrends.Event("m1", "A", BASE + HOUR));
    ReadTrends.MemberTrend trend = ReadTrends.compute(events, TOKYO).get(0);
    int[] blocks = ReadTrends.blockCounts(trend);
    assertEquals(0, blocks[0]);
    assertEquals(2, blocks[5]);
  }

  @Test
  public void lastReadIsTheLatestEvent() {
    List<ReadTrends.Event> events = new ArrayList<>();
    events.add(new ReadTrends.Event("m1", "A", BASE + 2 * HOUR));
    events.add(new ReadTrends.Event("m1", "A", BASE));
    ReadTrends.MemberTrend trend = ReadTrends.compute(events, TOKYO).get(0);
    assertEquals(BASE + 2 * HOUR, trend.lastMs);
  }

  @Test
  public void mostRecentNameWins() {
    List<ReadTrends.Event> events =
        Arrays.asList(
            new ReadTrends.Event("m1", "Old name", BASE), new ReadTrends.Event("m1", "New", BASE));
    assertEquals("New", ReadTrends.compute(events, TOKYO).get(0).displayName());
  }

  @Test
  public void eventsWithoutAReaderAreIgnored() {
    List<ReadTrends.Event> events = new ArrayList<>();
    events.add(new ReadTrends.Event(null, "A", BASE));
    events.add(new ReadTrends.Event("m1", "A", 0));
    assertEquals(0, ReadTrends.compute(events, TOKYO).size());
  }
}
