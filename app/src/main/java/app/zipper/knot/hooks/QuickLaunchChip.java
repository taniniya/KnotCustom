package app.zipper.knot.hooks;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import app.zipper.knot.Knot;
import app.zipper.knot.R;
import app.zipper.knot.commands.QuickStore;
import app.zipper.knot.ui.QuickLauncher;
import app.zipper.knot.utils.ChatInput;
import app.zipper.knot.utils.ModuleResources;
import java.lang.ref.WeakReference;

/**
 * The small bolt button that sits above the message field of an open chat room and opens the quick
 * launcher.
 *
 * <p>LINE's own {@code +} sheet is a server-driven grid inside the chat screen, so this button is
 * how the templates are reached from the chat room itself. It lives in LINE's window, so no overlay
 * permission is needed; a long press hides it until it is turned back on in Knot's settings.
 */
public final class QuickLaunchChip {

  private static final String OVERLAY_TAG = "knot_quick_launch_chip";
  private static final int BACKGROUND = 0xD92B2B2B;
  private static final int TEXT_COLOR = 0xFFFFFFFF;

  private static volatile WeakReference<Activity> hostRef;
  private static volatile FrameLayout overlay;
  private static volatile boolean hintShown;

  private QuickLaunchChip() {}

  public static void onActivityResumed(Activity activity) {
    if (activity == null) return;
    if (!ChatInput.isChatRoom(activity) || !QuickStore.chipEnabled()) {
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
    activity.runOnUiThread(() -> doAttach(activity));
  }

  private static void doAttach(Activity activity) {
    try {
      if (activity.isFinishing()) return;
      if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) return;
      View decor = activity.getWindow() == null ? null : activity.getWindow().peekDecorView();
      if (!(decor instanceof ViewGroup)) return;
      ViewGroup decorGroup = (ViewGroup) decor;

      FrameLayout current = overlay;
      WeakReference<Activity> ref = hostRef;
      if (current != null && ref != null && ref.get() == activity) {
        if (current.getParent() == decorGroup) {
          position(activity, current, current.getChildCount() > 0 ? current.getChildAt(0) : null);
          return;
        }
        current = null;
      }
      if (overlay != null && overlay.getParent() instanceof ViewGroup) {
        ((ViewGroup) overlay.getParent()).removeView(overlay);
      }
      overlay = null;

      FrameLayout root = new FrameLayout(activity);
      root.setTag(OVERLAY_TAG);
      // Transparent and never a touch target of its own: only the pill is
      root.setBackground(null);
      root.setClickable(false);
      root.setFocusable(false);

      TextView pill = new TextView(activity);
      pill.setText(ModuleResources.get(R.string.quick_launcher_chip_label));
      pill.setTextColor(TEXT_COLOR);
      pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
      pill.setTypeface(null, Typeface.BOLD);
      pill.setGravity(Gravity.CENTER);
      pill.setPadding(dp(activity, 10), dp(activity, 5), dp(activity, 10), dp(activity, 5));
      pill.setBackground(rounded(activity));
      pill.setElevation(dp(activity, 4));
      pill.setOnClickListener(v -> QuickLauncher.show(activity));
      pill.setOnLongClickListener(
          v -> {
            QuickStore.setChipEnabled(false);
            detach();
            Toast.makeText(
                    activity,
                    ModuleResources.get(R.string.quick_launcher_chip_hidden),
                    Toast.LENGTH_LONG)
                .show();
            return true;
          });
      root.addView(pill, pillParams(activity));
      position(activity, root, pill);

      // The composer moves when the keyboard opens or the list scrolls
      final ViewTreeObserver observer = decorGroup.getViewTreeObserver();
      observer.addOnGlobalLayoutListener(
          new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
              FrameLayout currentOverlay = overlay;
              if (currentOverlay == null) {
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                return;
              }
              position(activity, currentOverlay, currentOverlay.getChildAt(0));
            }
          });

      hostRef = new WeakReference<>(activity);
      overlay = root;
      decorGroup.addView(
          root,
          new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

      if (!hintShown) {
        hintShown = true;
        Toast.makeText(
                activity, ModuleResources.get(R.string.quick_launcher_chip_hint), Toast.LENGTH_LONG)
            .show();
      }
    } catch (Throwable t) {
      Knot.log("Knot: quick launcher chip failed: " + t);
    }
  }

  private static FrameLayout.LayoutParams pillParams(Activity activity) {
    // FrameLayout's default is MATCH_PARENT for both sides, which turned the pill itself into a
    // full-screen dark sheet. WRAP_CONTENT is the whole point of a small button.
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.gravity = Gravity.BOTTOM | Gravity.END;
    params.rightMargin = dp(activity, 10);
    params.bottomMargin = dp(activity, 84);
    return params;
  }

  private static void position(Activity activity, FrameLayout root, View pill) {
    if (root == null || pill == null) return;
    ViewGroup.LayoutParams existing = pill.getLayoutParams();
    FrameLayout.LayoutParams params =
        existing instanceof FrameLayout.LayoutParams
            ? (FrameLayout.LayoutParams) existing
            : new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.gravity = Gravity.BOTTOM | Gravity.END;
    params.rightMargin = dp(activity, 10);
    params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
    params.height = ViewGroup.LayoutParams.WRAP_CONTENT;

    int height = root.getHeight();
    int margin = dp(activity, 84);
    EditText input = ChatInput.find(activity);
    if (input != null && height > 0) {
      int[] location = new int[2];
      input.getLocationInWindow(location);
      int above = height - location[1] + dp(activity, 3);
      if (above > dp(activity, 16) && above < height - dp(activity, 48)) margin = above;
    }
    params.bottomMargin = margin;
    pill.setLayoutParams(params);
  }

  private static void detach() {
    FrameLayout current = overlay;
    overlay = null;
    hostRef = null;
    if (current != null && current.getParent() instanceof ViewGroup) {
      ((ViewGroup) current.getParent()).removeView(current);
    }
  }

  private static GradientDrawable rounded(Activity activity) {
    GradientDrawable background = new GradientDrawable();
    background.setColor(BACKGROUND);
    background.setCornerRadius(dp(activity, 14));
    return background;
  }

  private static int dp(Activity activity, float value) {
    return Math.round(
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics()));
  }
}
