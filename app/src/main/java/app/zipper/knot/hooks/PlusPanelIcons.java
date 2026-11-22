package app.zipper.knot.hooks;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.zipper.knot.Knot;
import app.zipper.knot.R;
import app.zipper.knot.commands.QuickStore;
import app.zipper.knot.ui.PanelPlacement;
import app.zipper.knot.ui.QuickLauncher;
import app.zipper.knot.utils.ChatInput;
import app.zipper.knot.utils.LineTheme;
import app.zipper.knot.utils.ModuleResources;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The "templates" button that sits inside LINE's {@code +} panel in a chat room.
 *
 * <p>That panel is LINE's own server-driven grid, so nothing of LINE's is rewritten: the chat
 * screen's view tree is watched, the panel is recognised by the captions of the cells already in
 * it, and a small strip of one button (plus the dot switch) is put in the free space at the bottom
 * of the panel. Nothing covers the message field, nothing darkens the screen, and the strip goes
 * away as soon as the panel does.
 */
public final class PlusPanelIcons {

  private static final String TAG = "knot_plus_panel_icons";
  private static final long TICK_MS = 700;
  private static final int MAX_DEPTH = 60;

  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  private static volatile WeakReference<Activity> hostRef;
  private static volatile WeakReference<ViewGroup> decorRef;
  private static volatile LinearLayout strip;
  private static volatile TextView dotChip;
  private static volatile boolean ticking;

  // The LIST line's rows live in while we need room below them, and what it looked like before us
  private static volatile ViewGroup paddedList;
  private static volatile int paddedListBottom;
  private static volatile boolean paddedListClipToPadding;

  private static volatile long lastLayoutPass;
  private static volatile boolean loggedPanel;

  private static volatile String state = "idle";

  private PlusPanelIcons() {}

  public static String diagnostics() {
    return "state: " + state + "\n+ panel buttons: " + QuickStore.panelIconsEnabled();
  }

  // ------------------------------------------------------------------ lifecycle

  public static void onActivityResumed(Activity activity) {
    if (activity == null) return;
    if (!ChatInput.isChatRoom(activity) || !QuickStore.panelIconsEnabled()) {
      detach();
      return;
    }
    attach(activity);
  }

  public static void onActivityDestroyed(Activity activity) {
    WeakReference<Activity> ref = hostRef;
    if (ref != null && ref.get() == activity) detach();
  }

  private static void attach(final Activity activity) {
    activity.runOnUiThread(
        () -> {
          try {
            WeakReference<Activity> ref = hostRef;
            if (ref != null && ref.get() == activity) return;
            View decor = activity.getWindow() == null ? null : activity.getWindow().peekDecorView();
            if (!(decor instanceof ViewGroup)) return;
            detach();
            hostRef = new WeakReference<>(activity);
            decorRef = new WeakReference<>((ViewGroup) decor);
            try {
              ((ViewGroup) decor).getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
            } catch (Throwable ignored) {
            }
            if (!ticking) {
              ticking = true;
              MAIN.removeCallbacks(tick);
              MAIN.postDelayed(tick, TICK_MS);
            }
            pass(activity);
          } catch (Throwable t) {
            Knot.log("Knot: + panel buttons failed to attach: " + t);
          }
        });
  }

  private static void detach() {
    ticking = false;
    WeakReference<ViewGroup> decor = decorRef;
    ViewGroup view = decor == null ? null : decor.get();
    if (view != null) {
      try {
        view.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
      } catch (Throwable ignored) {
      }
    }
    hostRef = null;
    decorRef = null;
    releasePadding();
    removeStrip();
    state = "idle";
  }

  private static Activity host() {
    WeakReference<Activity> ref = hostRef;
    Activity activity = ref == null ? null : ref.get();
    if (activity == null || activity.isFinishing()) return null;
    return activity;
  }

  // The panel closing is a layout change, so the strip can go at once rather than on the next tick
  private static final ViewTreeObserver.OnGlobalLayoutListener layoutListener =
      new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout() {
          Activity activity = host();
          if (activity == null) return;
          long now = SystemClock.uptimeMillis();
          if (now - lastLayoutPass < 100) return;
          lastLayoutPass = now;
          try {
            pass(activity);
          } catch (Throwable t) {
            Knot.log("Knot: + panel buttons layout pass failed: " + t);
          }
        }
      };

  private static final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          try {
            Activity activity = host();
            if (activity == null) {
              ticking = false;
              return;
            }
            pass(activity);
          } catch (Throwable t) {
            Knot.log("Knot: + panel buttons tick failed: " + t);
          }
          if (ticking) MAIN.postDelayed(this, TICK_MS);
        }
      };

  // ------------------------------------------------------------------ one look at the screen

  private static void pass(Activity activity) {
    if (!QuickStore.panelIconsEnabled()) {
      hide();
      return;
    }
    EditText input = ChatInput.find(activity);
    ViewGroup decor = decorRef == null ? null : decorRef.get();
    if (input == null || decor == null || !input.isShown()) {
      state = "no message field";
      hide();
      return;
    }
    if (decor.getWindowVisibility() != View.VISIBLE) {
      state = "window not visible";
      hide();
      return;
    }

    int[] inputAt = new int[2];
    input.getLocationInWindow(inputAt);
    int inputBottom = inputAt[1] + input.getHeight();

    // Nothing tall enough can be under a field that sits at the bottom of the screen
    if (inputBottom > decor.getHeight() - dp(activity, 70)) {
      state = "no room below the message field";
      hide();
      return;
    }

    ViewGroup panel = findPanel(decor, inputBottom);
    if (panel == null) {
      state = "no + panel";
      hide();
      return;
    }

    LinearLayout row = ensureStrip(activity);
    if (row == null) {
      hide();
      return;
    }

    List<int[]> cells = readCells(panel);
    int panelAt[] = new int[2];
    panel.getLocationInWindow(panelAt);
    int panelLeft = panelAt[0];
    int panelBottom = panelAt[1] + panel.getHeight();

    int stripHeight = row.getHeight();
    if (stripHeight <= 0) {
      row.measure(
          View.MeasureSpec.makeMeasureSpec(
              activity.getResources().getDisplayMetrics().widthPixels, View.MeasureSpec.AT_MOST),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
      stripHeight = row.getMeasuredHeight();
    }
    if (stripHeight <= 0) stripHeight = dp(activity, 48);

    int gap = dp(activity, 6);
    PanelPlacement.Spot spot =
        PanelPlacement.place(panelLeft, panelBottom, cells, stripHeight, gap, dp(activity, 10));

    applyPadding(panel, spot.bottomPadding);

    if (row.getVisibility() != View.VISIBLE) row.setVisibility(View.VISIBLE);
    if (row.getX() != spot.x) row.setX(spot.x);
    if (row.getY() != spot.y) row.setY(spot.y);
    state =
        "+ panel: "
            + panel.getClass().getSimpleName()
            + ", cells "
            + cells.size()
            + ", free "
            + (panelBottom - PanelPlacement.lastRowBottom(cells, dp(activity, 10)))
            + "px, padding "
            + spot.bottomPadding
            + "px, strip "
            + stripHeight
            + "px";
    if (!loggedPanel) {
      loggedPanel = true;
      Knot.log("Knot: + panel detected: " + state);
    }
  }

  // ------------------------------------------------------------------ finding LINE's + panel

  /**
   * The panel is the outermost visible container that starts below the message field and carries at
   * least three short captions — the tiles' names. Nothing of the composer or the message list can
   * match, because both start above the field.
   */
  private static ViewGroup findPanel(ViewGroup decor, int inputBottom) {
    try {
      return scanPanel(decor, inputBottom, 0, decor.getWidth());
    } catch (Throwable t) {
      return null;
    }
  }

  private static ViewGroup scanPanel(ViewGroup group, int inputBottom, int depth, int decorWidth) {
    if (group == null || depth > MAX_DEPTH) return null;
    if (TAG.equals(group.getTag())) return null;
    if (!group.isShown() || group.getWidth() <= 0 || group.getHeight() <= 0) return null;

    int[] at = new int[2];
    group.getLocationInWindow(at);
    int top = at[1];
    boolean below = top >= inputBottom - dp(group, 8);
    boolean wide = group.getWidth() >= decorWidth * 0.6f;
    boolean tall = group.getHeight() >= dp(group, 100);

    if (below && wide && tall && looksLikeTiles(group)) return group;

    int count = group.getChildCount();
    for (int i = 0; i < count; i++) {
      View child = group.getChildAt(i);
      if (!(child instanceof ViewGroup)) continue;
      ViewGroup found = scanPanel((ViewGroup) child, inputBottom, depth + 1, decorWidth);
      if (found != null) return found;
    }
    return null;
  }

  /**
   * The {@code +} panel's tiles are an icon over a caption. Counting those tells the panel apart
   * from the sticker sheet, which also opens under the message field but has no captions of its
   * own.
   */
  private static boolean looksLikeTiles(ViewGroup group) {
    try {
      ViewGroup grid = findGrid(group);
      if (grid == null) return countCaptions(group) >= 3;
      int labelled = 0;
      int count = Math.min(grid.getChildCount(), 40);
      for (int i = 0; i < count && labelled < 3; i++) {
        View child = grid.getChildAt(i);
        if (child == null || !child.isShown() || child.getHeight() <= 0) continue;
        int[] flags = new int[1];
        markTile(child, 0, flags);
        if (flags[0] == 3) labelled++;
      }
      return labelled >= 3;
    } catch (Throwable t) {
      return false;
    }
  }

  private static void markTile(View view, int depth, int[] flags) {
    if (view == null || depth > 12 || flags[0] == 3) return;
    if (view instanceof ImageView) flags[0] |= 1;
    if (view instanceof TextView) {
      CharSequence text = ((TextView) view).getText();
      if (text != null) {
        String value = text.toString().trim();
        if (!value.isEmpty() && value.length() <= 16) flags[0] |= 2;
      }
    }
    if (!(view instanceof ViewGroup)) return;
    ViewGroup group = (ViewGroup) view;
    for (int i = 0; i < group.getChildCount() && flags[0] != 3; i++) {
      markTile(group.getChildAt(i), depth + 1, flags);
    }
  }

  private static int countCaptions(View view) {
    int[] seen = new int[] {0};
    return countCaptions(view, 0, seen);
  }

  private static int countCaptions(View view, int depth, int[] seen) {
    if (view == null || depth > MAX_DEPTH || seen[0] > 400) return 0;
    seen[0]++;
    int found = 0;
    if (view instanceof TextView && !(view instanceof EditText) && view.isShown()) {
      CharSequence text = ((TextView) view).getText();
      if (text != null) {
        String value = text.toString().trim();
        if (value.length() >= 1 && value.length() <= 16 && !value.isEmpty()) found++;
      }
    }
    if (!(view instanceof ViewGroup)) return found;
    ViewGroup group = (ViewGroup) view;
    int count = group.getChildCount();
    for (int i = 0; i < count; i++) found += countCaptions(group.getChildAt(i), depth + 1, seen);
    return found;
  }

  /** The cells of the panel's grid, as {left, top, right, bottom} in window coordinates. */
  private static List<int[]> readCells(ViewGroup panel) {
    List<int[]> cells = new ArrayList<>();
    try {
      ViewGroup grid = findGrid(panel);
      ViewGroup source = grid != null ? grid : panel;
      int count = source.getChildCount();
      for (int i = 0; i < count; i++) {
        View child = source.getChildAt(i);
        if (child == null || !child.isShown()) continue;
        if (child.getHeight() <= 0 || child.getWidth() <= 0) continue;
        int[] at = new int[2];
        child.getLocationInWindow(at);
        cells.add(new int[] {at[0], at[1], at[0] + child.getWidth(), at[1] + child.getHeight()});
      }
    } catch (Throwable ignored) {
    }
    return cells;
  }

  private static ViewGroup findGrid(ViewGroup panel) {
    try {
      ViewGroup grid = scanGrid(panel, 0);
      return grid;
    } catch (Throwable t) {
      return null;
    }
  }

  private static ViewGroup scanGrid(View view, int depth) {
    if (!(view instanceof ViewGroup) || depth > MAX_DEPTH) return null;
    ViewGroup group = (ViewGroup) view;
    if (isRecycler(group) && group.getChildCount() >= 2) return group;
    int count = group.getChildCount();
    for (int i = 0; i < count; i++) {
      ViewGroup found = scanGrid(group.getChildAt(i), depth + 1);
      if (found != null) return found;
    }
    return null;
  }

  private static boolean isRecycler(View view) {
    Class<?> type = view.getClass();
    while (type != null) {
      if ("androidx.recyclerview.widget.RecyclerView".equals(type.getName())) return true;
      type = type.getSuperclass();
    }
    return false;
  }

  // ------------------------------------------------------------------ the strip itself

  private static LinearLayout ensureStrip(final Activity activity) {
    LinearLayout current = strip;
    if (current != null) {
      if (current.getParent() instanceof ViewGroup) return current;
      strip = null;
    }
    ViewGroup decor = decorRef == null ? null : decorRef.get();
    if (decor == null) return null;

    LinearLayout row = new LinearLayout(activity);
    row.setTag(TAG);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 8), dp(activity, 4));
    row.setVisibility(View.GONE);

    row.addView(
        cell(
            activity,
            "ic_knot_templates",
            R.string.quick_launcher_templates,
            () -> QuickLauncher.show(activity)));

    dotChip = dotChip(activity);
    LinearLayout.LayoutParams dotParams =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    dotParams.leftMargin = dp(activity, 6);
    row.addView(dotChip, dotParams);
    refreshDot(activity);

    strip = row;
    try {
      decor.addView(
          row,
          new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    } catch (Throwable t) {
      strip = null;
      Knot.log("Knot: + panel strip could not be added: " + t);
      return null;
    }
    return row;
  }

  /** One tile of the strip: the icon over its caption, the same way LINE's own tiles read. */
  private static View cell(
      Activity activity, String drawableName, int labelRes, final Runnable action) {
    LinearLayout cell = new LinearLayout(activity);
    cell.setOrientation(LinearLayout.VERTICAL);
    cell.setGravity(Gravity.CENTER_HORIZONTAL);
    cell.setPadding(dp(activity, 10), dp(activity, 2), dp(activity, 10), dp(activity, 2));

    ImageView icon = new ImageView(activity);
    Drawable drawable = ModuleResources.drawable(drawableName);
    if (drawable != null) {
      icon.setImageDrawable(drawable);
      icon.setColorFilter(LineTheme.primaryTextColor(activity));
    }
    cell.addView(icon, new LinearLayout.LayoutParams(dp(activity, 22), dp(activity, 22)));

    TextView label = new TextView(activity);
    label.setText(ModuleResources.get(labelRes));
    label.setTextSize(10);
    label.setGravity(Gravity.CENTER);
    label.setTextColor(LineTheme.primaryTextColor(activity));
    label.setPadding(0, dp(activity, 2), 0, 0);
    cell.addView(label);

    cell.setOnClickListener(
        v -> {
          try {
            action.run();
          } catch (Throwable t) {
            Knot.log("Knot: + panel button failed: " + t);
          }
        });
    return cell;
  }

  private static TextView dotChip(Activity activity) {
    TextView chip = new TextView(activity);
    chip.setTextSize(11);
    chip.setTypeface(null, Typeface.BOLD);
    chip.setGravity(Gravity.CENTER);
    chip.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
    chip.setOnClickListener(
        v -> {
          QuickStore.setDotPrefix(!QuickStore.dotPrefix());
          Activity hostActivity = host();
          if (hostActivity != null) refreshDot(hostActivity);
        });
    return chip;
  }

  private static void refreshDot(Activity activity) {
    TextView chip = dotChip;
    if (chip == null) return;
    boolean on = QuickStore.dotPrefix();
    chip.setText(
        ModuleResources.get(on ? R.string.quick_panel_dot_on : R.string.quick_panel_dot_off));
    GradientDrawable background = new GradientDrawable();
    background.setCornerRadius(dp(activity, 12));
    if (on) {
      background.setColor(LineTheme.linkColor(activity));
      chip.setTextColor(0xFFFFFFFF);
    } else {
      background.setColor(0x00000000);
      background.setStroke(dp(activity, 1), LineTheme.secondaryTextColor(activity));
      chip.setTextColor(LineTheme.secondaryTextColor(activity));
    }
    chip.setBackground(background);
  }

  // ------------------------------------------------------------------ room for the strip

  /**
   * Gives the panel's list room at the bottom when the strip would otherwise sit on its last row.
   * The original padding goes back the moment the panel closes.
   */
  private static void applyPadding(ViewGroup panel, int padding) {
    ViewGroup grid = findGrid(panel);
    ViewGroup target = grid;
    if (padding <= 0) {
      releasePadding();
      return;
    }
    if (target == null) return;
    if (paddedList == target) {
      int wanted = paddedListBottom + padding;
      if (target.getPaddingBottom() == wanted) return;
      target.setPadding(
          target.getPaddingLeft(), target.getPaddingTop(), target.getPaddingRight(), wanted);
      return;
    }
    releasePadding();
    paddedList = target;
    paddedListBottom = target.getPaddingBottom();
    paddedListClipToPadding = target.getClipToPadding();
    try {
      target.setClipToPadding(true);
    } catch (Throwable ignored) {
    }
    target.setPadding(
        target.getPaddingLeft(),
        target.getPaddingTop(),
        target.getPaddingRight(),
        paddedListBottom + padding);
  }

  private static void releasePadding() {
    ViewGroup target = paddedList;
    paddedList = null;
    if (target == null) return;
    try {
      target.setPadding(
          target.getPaddingLeft(),
          target.getPaddingTop(),
          target.getPaddingRight(),
          paddedListBottom);
      target.setClipToPadding(paddedListClipToPadding);
    } catch (Throwable ignored) {
    }
  }

  // ------------------------------------------------------------------ tidy up

  private static void hide() {
    releasePadding();
    loggedPanel = false;
    LinearLayout row = strip;
    if (row != null && row.getVisibility() != View.GONE) row.setVisibility(View.GONE);
  }

  private static void removeStrip() {
    LinearLayout row = strip;
    strip = null;
    dotChip = null;
    if (row != null && row.getParent() instanceof ViewGroup) {
      ((ViewGroup) row.getParent()).removeView(row);
    }
  }

  private static int dp(View view, float value) {
    return Math.round(
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, view.getResources().getDisplayMetrics()));
  }

  private static int dp(Activity activity, float value) {
    return dp((View) activity.getWindow().getDecorView(), value);
  }
}
