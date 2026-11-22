package app.zipper.knot.commands;

import java.util.List;

/**
 * One line of the template list: a body to put in the message field, and an optional title to find
 * it by.
 *
 * <p>Since v3.1.14 there is only one list. What an earlier version kept apart as "commands" lives
 * here too, so an entry is simply a template whether or not it starts with a bot's slash.
 */
public final class QuickEntry {

  public String title = "";
  public String body = "";

  public QuickEntry() {}

  public QuickEntry(String title, String body) {
    this.title = title != null ? title : "";
    this.body = body != null ? body : "";
  }

  public static QuickEntry of(String body) {
    return new QuickEntry("", body);
  }

  public List<TemplateParser.Block> blocks() {
    return TemplateParser.blocks(body);
  }

  public boolean hasBlocks() {
    return TemplateParser.hasBlocks(body);
  }

  /** The title, or the start of the body when there is none. */
  public String displayTitle() {
    if (title != null && !title.trim().isEmpty()) return title.trim();
    String first = body == null ? "" : body.trim();
    int newline = first.indexOf('\n');
    if (newline >= 0) first = first.substring(0, newline).trim();
    if (first.length() > 42) first = first.substring(0, 42) + "…";
    return first.isEmpty() ? "" : first;
  }

  /** The body with its blocks written as labels, for the second line of a list row. */
  public String describe() {
    return TemplateParser.describe(body);
  }

  public String summary() {
    List<TemplateParser.Block> blocks = blocks();
    if (blocks.isEmpty()) return "no blocks";
    StringBuilder sb = new StringBuilder();
    for (TemplateParser.Block block : blocks) {
      if (sb.length() > 0) sb.append(", ");
      sb.append(block.summary());
    }
    return blocks.size() + " blocks: " + sb;
  }

  public QuickEntry copy() {
    return new QuickEntry(title, body);
  }
}
