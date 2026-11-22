package app.zipper.knot.commands;

import app.zipper.knot.SettingsStore;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Keeps the template list and the launcher switches in Knot's settings file.
 *
 * <p>There is one list. Up to v3.1.13 a second list of "commands" sat beside it; v3.1.14 folds
 * everything into the templates, and the old keys are left in the file untouched and never read, so
 * a settings file written by an older version neither crashes nor brings the old list back.
 *
 * <p>Templates have a switch that puts a leading dot on whatever they insert.
 */
public final class QuickStore {

  public static final String KEY_DOT_PREFIX = "template_dot_prefix";
  public static final String KEY_PLUS_MENU = "plus_menu_commands";
  public static final String KEY_CHIP = "command_chip_in_chat";
  public static final String KEY_PANEL = "plus_panel_icons";

  private static final String KEY_TEMPLATES = "template_entries";
  private static final String KEY_CHIP_OFF_SEEN = "command_chip_off_seen";

  /** Left behind by v3.1.13 and earlier. Read by nothing here; see the note on this class. */
  public static final String LEGACY_COMMANDS = "command_entries";

  /** Told when the dot switch is flipped anywhere, so every copy of it can follow. */
  public interface DotListener {
    void onDotPrefixChanged(boolean on);
  }

  private static final List<DotListener> dotListeners = new ArrayList<>();

  private QuickStore() {}

  /**
   * v3.1.12: the bolt button over the message field is too big for the chat, so it goes off and the
   * buttons inside the {@code +} panel take its place. Runs once, so a later choice by the user
   * stands.
   */
  public static void applyPanelDefaults() {
    try {
      if (SettingsStore.get(KEY_CHIP_OFF_SEEN, false)) return;
      SettingsStore.save(KEY_CHIP, false);
      SettingsStore.save(KEY_PANEL, true);
      SettingsStore.save(KEY_CHIP_OFF_SEEN, true);
    } catch (Throwable ignored) {
    }
  }

  /** The one list: what the launcher shows and the management screen edits. */
  public static List<QuickEntry> entries() {
    return load(KEY_TEMPLATES);
  }

  public static int count() {
    return entries().size();
  }

  public static void save(List<QuickEntry> entries) {
    SettingsStore.save(KEY_TEMPLATES, encode(entries));
  }

  /**
   * Whether a settings file from an older version still holds a command list. Only used to explain
   * the situation in the debug screen; nothing is read from it or written back.
   */
  public static boolean legacyCommandListPresent() {
    try {
      String raw = SettingsStore.getString(LEGACY_COMMANDS, "");
      return raw != null && !raw.isEmpty();
    } catch (Throwable t) {
      return false;
    }
  }

  public static boolean dotPrefix() {
    return SettingsStore.get(KEY_DOT_PREFIX, false);
  }

  public static void setDotPrefix(boolean on) {
    SettingsStore.save(KEY_DOT_PREFIX, on);
    notifyDot(on);
  }

  public static void addDotListener(DotListener listener) {
    if (listener == null) return;
    synchronized (dotListeners) {
      if (!dotListeners.contains(listener)) dotListeners.add(listener);
    }
  }

  public static void removeDotListener(DotListener listener) {
    synchronized (dotListeners) {
      dotListeners.remove(listener);
    }
  }

  private static void notifyDot(boolean on) {
    List<DotListener> copy;
    synchronized (dotListeners) {
      copy = new ArrayList<>(dotListeners);
    }
    for (DotListener listener : copy) {
      try {
        listener.onDotPrefixChanged(on);
      } catch (Throwable ignored) {
      }
    }
  }

  public static boolean plusMenuEnabled() {
    return SettingsStore.get(KEY_PLUS_MENU, true);
  }

  public static boolean chipEnabled() {
    return SettingsStore.get(KEY_CHIP, false);
  }

  public static void setChipEnabled(boolean on) {
    SettingsStore.save(KEY_CHIP, on);
  }

  /** The button inside LINE's {@code +} panel. On by default: it is the main way in. */
  public static boolean panelIconsEnabled() {
    return SettingsStore.get(KEY_PANEL, true);
  }

  public static void setPanelIconsEnabled(boolean on) {
    SettingsStore.save(KEY_PANEL, on);
  }

  private static List<QuickEntry> load(String key) {
    List<QuickEntry> entries = new ArrayList<>();
    String raw = SettingsStore.getString(key, "");
    if (raw == null || raw.isEmpty()) return entries;
    try {
      JSONArray array = new JSONArray(raw);
      for (int i = 0; i < array.length(); i++) {
        JSONObject item = array.optJSONObject(i);
        if (item == null) continue;
        String body = item.optString("b", "");
        if (body.isEmpty()) continue;
        entries.add(new QuickEntry(item.optString("t", ""), body));
      }
    } catch (Throwable ignored) {
    }
    return entries;
  }

  private static String encode(List<QuickEntry> entries) {
    JSONArray array = new JSONArray();
    if (entries != null) {
      for (QuickEntry entry : entries) {
        if (entry == null || entry.body == null || entry.body.trim().isEmpty()) continue;
        JSONObject item = new JSONObject();
        try {
          item.put("t", entry.title != null ? entry.title : "");
          item.put("b", entry.body);
        } catch (Throwable ignored) {
        }
        array.put(item);
      }
    }
    return array.toString();
  }
}
