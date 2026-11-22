package app.zipper.knot.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import app.zipper.knot.Knot;
import app.zipper.knot.R;
import app.zipper.knot.commands.QuickEntry;
import app.zipper.knot.commands.QuickStore;
import app.zipper.knot.commands.TemplateParser;
import app.zipper.knot.utils.ChatInput;
import app.zipper.knot.utils.LineTheme;
import app.zipper.knot.utils.ModuleResources;
import java.util.ArrayList;
import java.util.List;

/**
 * The quick launcher: the template list, opened from the chat room button, LINE's {@code +} panel
 * or the {@code +} menu.
 *
 * <p>One screen, one list. An entry without blocks goes straight into the message field. An entry
 * with blocks opens a screen where each choice block is a row of chips and each free-input block a
 * text field, so the finished line can be put in the message field — the user still presses send,
 * so a mention can be completed first.
 */
public final class QuickLauncher {

  private static final float MAX_LIST_FRACTION = 0.60f;

  private final Activity activity;
  private final AlertDialog dialog;
  private final LinearLayout root;
  private Switch dotToggle;
  private boolean syncingDot;

  // The dot switch can be flipped from the + panel as well, so every copy follows the store
  private final QuickStore.DotListener dotListener = this::onDotChanged;

  private void onDotChanged(boolean on) {
    Switch toggle = dotToggle;
    if (toggle == null || toggle.isChecked() == on) return;
    syncingDot = true;
    toggle.setChecked(on);
    syncingDot = false;
    if (fillPreview != null) updatePreview();
  }

  private QuickLauncher(Activity activity) {
    this.activity = activity;
    this.root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(20), dp(14), dp(20), dp(12));

    AlertDialog.Builder builder =
        new AlertDialog.Builder(activity, LineTheme.dialogTheme(activity)).setView(root);
    dialog = builder.create();
  }

  public static void show(Activity activity) {
    if (activity == null || activity.isFinishing()) return;
    try {
      new QuickLauncher(activity).open();
    } catch (Throwable t) {
      Knot.log("Knot: quick launcher failed: " + t);
    }
  }

  private void open() {
    renderList();
    QuickStore.addDotListener(dotListener);
    dialog.setOnDismissListener(d -> QuickStore.removeDotListener(dotListener));
    dialog.show();
    LineTheme.applyDialogColors(dialog, activity);
    allowSoftInput();
  }

  /**
   * Lets the keyboard open over this dialog.
   *
   * <p>The dialog is shown while its content has no text field at all (the list). Android's {@code
   * AlertBuilder} marks such a window {@code FLAG_ALT_FOCUSABLE_IM} then, and that flag makes every
   * field added afterwards ignore the IME — tapping it does nothing. Clearing the flag, and doing
   * so again whenever the content changes, is what brings the keyboard back.
   */
  private void allowSoftInput() {
    Window window = dialog.getWindow();
    if (window == null) return;
    window.clearFlags(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  }

  private void showKeyboard(View view) {
    if (view == null) return;
    view.requestFocus();
    InputMethodManager imm =
        (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
    if (imm == null) return;
    // After the window flag change settles, otherwise the first request is dropped
    view.postDelayed(() -> imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT), 120);
  }

  // ------------------------------------------------------------------ list

  private void renderList() {
    root.removeAllViews();
    root.setTag(null);
    root.removeOnLayoutChangeListener(fitFillBody);
    fillScroller = null;
    fillPreview = null;
    dotToggle = null;
    allowSoftInput();
    root.addView(buildDotSwitch());

    List<QuickEntry> entries = QuickStore.entries();
    if (entries.isEmpty()) {
      TextView empty = new TextView(activity);
      empty.setText(ModuleResources.get(R.string.quick_launcher_empty_templates));
      empty.setTextSize(13);
      empty.setTextColor(LineTheme.secondaryTextColor(activity));
      empty.setPadding(0, dp(18), 0, dp(18));
      root.addView(empty);
    } else {
      root.addView(buildEntryList(entries));
    }

    root.addView(buildCloseRow());
  }

  private View buildDotSwitch() {
    Switch toggle = new Switch(activity);
    toggle.setText(ModuleResources.get(R.string.quick_launcher_dot_switch));
    toggle.setTextSize(13);
    toggle.setTextColor(LineTheme.primaryTextColor(activity));
    toggle.setChecked(QuickStore.dotPrefix());
    toggle.setOnCheckedChangeListener(
        (button, checked) -> {
          if (syncingDot) return;
          QuickStore.setDotPrefix(checked);
          if (fillPreview != null) updatePreview();
        });
    toggle.setPadding(0, 0, 0, dp(6));
    dotToggle = toggle;
    return toggle;
  }

  private View buildEntryList(List<QuickEntry> entries) {
    LinearLayout list = new LinearLayout(activity);
    list.setOrientation(LinearLayout.VERTICAL);

    for (QuickEntry entry : entries) list.addView(buildEntryRow(entry));

    ScrollView scroller = new ScrollView(activity);
    scroller.setVerticalScrollBarEnabled(true);
    scroller.addView(list);
    scroller.setLayoutParams(
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cappedHeight(list)));
    return scroller;
  }

  // As tall as the rows need, up to most of the screen, so a short list is not a big empty box
  private int cappedHeight(View content) {
    int max =
        Math.round(activity.getResources().getDisplayMetrics().heightPixels * MAX_LIST_FRACTION);
    int width = activity.getResources().getDisplayMetrics().widthPixels - dp(24) - dp(20) - dp(20);
    content.measure(
        View.MeasureSpec.makeMeasureSpec(Math.max(width, 1), View.MeasureSpec.AT_MOST),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    return Math.max(Math.min(content.getMeasuredHeight(), max), dp(48));
  }

  private View buildEntryRow(QuickEntry entry) {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setPadding(dp(12), dp(10), dp(12), dp(10));

    GradientDrawable background = new GradientDrawable();
    background.setColor(LineTheme.cardColor(activity));
    background.setCornerRadius(dp(12));
    row.setBackground(background);

    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.bottomMargin = dp(6);
    row.setLayoutParams(params);

    TextView title = new TextView(activity);
    title.setText(entry.displayTitle());
    title.setTextSize(15);
    title.setTypeface(null, Typeface.BOLD);
    title.setTextColor(LineTheme.primaryTextColor(activity));
    row.addView(title);

    if (entry.hasBlocks()) {
      TextView summary = new TextView(activity);
      summary.setText(entry.describe());
      summary.setTextSize(12);
      summary.setTextColor(LineTheme.secondaryTextColor(activity));
      summary.setPadding(0, dp(2), 0, 0);
      row.addView(summary);
    }

    row.setOnClickListener(v -> open(entry));
    return row;
  }

  private View buildCloseRow() {
    LinearLayout footer = new LinearLayout(activity);
    footer.setOrientation(LinearLayout.HORIZONTAL);
    footer.setGravity(Gravity.END);
    footer.setPadding(0, dp(10), 0, 0);

    TextView close = footerButton(R.string.common_close, false);
    close.setOnClickListener(v -> dialog.dismiss());
    footer.addView(close);
    return footer;
  }

  // ------------------------------------------------------------------ fill

  private List<String> fillValues = new ArrayList<>();
  private TextView fillPreview;
  private ScrollView fillScroller;
  private int fillScrollerCap;

  /**
   * Keeps the preview and the buttons on screen while the keyboard is up: with the IME shown the
   * dialog window is shorter, so the body gives up the height it cannot have instead of pushing the
   * rows below it out of view.
   */
  private final View.OnLayoutChangeListener fitFillBody = this::fitFillBody;

  private void fitFillBody(
      View view,
      int left,
      int top,
      int right,
      int bottom,
      int oldLeft,
      int oldTop,
      int oldRight,
      int oldBottom) {
    ScrollView scroller = fillScroller;
    if (scroller == null || scroller.getParent() != root) return;
    int available = bottom - top - root.getPaddingTop() - root.getPaddingBottom();
    if (available <= 0) return;
    int used = 0;
    for (int i = 0; i < root.getChildCount(); i++) {
      View child = root.getChildAt(i);
      if (child == scroller) continue;
      used += child.getMeasuredHeight();
      ViewGroup.LayoutParams params = child.getLayoutParams();
      if (params instanceof LinearLayout.LayoutParams) {
        used += ((LinearLayout.LayoutParams) params).topMargin;
        used += ((LinearLayout.LayoutParams) params).bottomMargin;
      }
    }
    int target = Math.max(dp(48), Math.min(fillScrollerCap, available - used));
    ViewGroup.LayoutParams params = scroller.getLayoutParams();
    if (params.height == target) return;
    params.height = target;
    scroller.setLayoutParams(params);
  }

  private void open(QuickEntry entry) {
    if (!entry.hasBlocks()) {
      insert(entryText(entry.body));
      return;
    }
    renderFill(entry);
  }

  private void renderFill(QuickEntry entry) {
    root.removeAllViews();
    root.removeOnLayoutChangeListener(fitFillBody);
    fillScroller = null;
    root.setTag(entry);
    dotToggle = null;
    List<TemplateParser.Block> blocks = entry.blocks();
    fillValues = new ArrayList<>();
    for (int i = 0; i < blocks.size(); i++) fillValues.add("");

    TextView title = new TextView(activity);
    title.setText(
        entry.displayTitle().isEmpty()
            ? ModuleResources.get(R.string.quick_launcher_fill_title)
            : entry.displayTitle());
    title.setTextSize(16);
    title.setTypeface(null, Typeface.BOLD);
    title.setTextColor(LineTheme.primaryTextColor(activity));
    root.addView(title);

    LinearLayout body = new LinearLayout(activity);
    body.setOrientation(LinearLayout.VERTICAL);
    for (int i = 0; i < blocks.size(); i++) body.addView(buildBlockEditor(blocks.get(i), i));

    // The window was created for the list, so it carries the no-IME flag until this clears it
    allowSoftInput();

    ScrollView scroller = new ScrollView(activity);
    scroller.addView(body);
    fillScroller = scroller;
    fillScrollerCap = cappedHeight(body);
    scroller.setLayoutParams(
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, fillScrollerCap));
    root.addView(scroller);

    root.addView(buildDotSwitch());

    fillPreview = new TextView(activity);
    fillPreview.setTextSize(12);
    fillPreview.setTextColor(LineTheme.secondaryTextColor(activity));
    fillPreview.setPadding(dp(4), dp(8), dp(4), dp(4));
    root.addView(fillPreview);
    updatePreview();

    LinearLayout footer = new LinearLayout(activity);
    footer.setOrientation(LinearLayout.HORIZONTAL);
    footer.setGravity(Gravity.END);
    footer.setPadding(0, dp(10), 0, 0);

    TextView back = footerButton(R.string.quick_launcher_back, false);
    back.setOnClickListener(v -> renderList());
    TextView insert = footerButton(R.string.quick_launcher_insert, true);
    insert.setOnClickListener(
        v -> insert(entryText(TemplateParser.render(entry.body, fillValues))));
    footer.addView(back);
    footer.addView(insert, leftMargin(dp(8)));
    root.addView(footer);

    root.addOnLayoutChangeListener(fitFillBody);
    // Put the cursor in the first field so the keyboard is there to type on
    showKeyboard(firstFreeInput(body));
  }

  private EditText firstFreeInput(View view) {
    if (view instanceof EditText) return (EditText) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        EditText found = firstFreeInput(group.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
  }

  private View buildBlockEditor(TemplateParser.Block block, int index) {
    LinearLayout box = new LinearLayout(activity);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(0, dp(8), 0, dp(4));

    TextView label = new TextView(activity);
    label.setText(
        block.kind == TemplateParser.Kind.FREE_INPUT
            ? ModuleResources.get(R.string.quick_launcher_free_hint, block.label)
            : ModuleResources.get(R.string.quick_launcher_choice_pick));
    label.setTextSize(12);
    label.setTextColor(LineTheme.secondaryTextColor(activity));
    box.addView(label);

    if (block.kind == TemplateParser.Kind.FREE_INPUT) {
      box.addView(freeInput(block, index));
      return box;
    }

    box.addView(choiceRow(block, index));
    if (block.allowsFreeText()) {
      EditText own = freeInput(block, index);
      own.setHint(ModuleResources.get(R.string.quick_launcher_choice_free));
      box.addView(own);
    }
    return box;
  }

  private EditText freeInput(TemplateParser.Block block, int index) {
    EditText input = new EditText(activity);
    input.setSingleLine(true);
    input.setTextSize(14);
    // A field added after the dialog was shown needs this to take focus and the IME at all
    input.setFocusable(true);
    input.setFocusableInTouchMode(true);
    input.setOnClickListener(v -> showKeyboard(input));
    input.setHint(
        block.kind == TemplateParser.Kind.FREE_INPUT
            ? block.label
            : ModuleResources.get(R.string.quick_launcher_choice_free));
    input.setInputType(
        InputType.TYPE_CLASS_TEXT
            | (block.label != null && block.label.startsWith("@")
                ? InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                : 0));
    input.addTextChangedListener(
        new android.text.TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {
            setValue(index, s.toString());
          }

          @Override
          public void afterTextChanged(android.text.Editable s) {}
        });
    return input;
  }

  private View choiceRow(TemplateParser.Block block, int index) {
    HorizontalScrollView scroller = new HorizontalScrollView(activity);
    scroller.setHorizontalScrollBarEnabled(false);
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setPadding(0, dp(4), 0, 0);

    for (String option : block.options) {
      TextView chip = new TextView(activity);
      chip.setText(option);
      chip.setTextSize(13);
      chip.setPadding(dp(12), dp(6), dp(12), dp(6));
      chip.setTextColor(LineTheme.primaryTextColor(activity));
      chip.setBackground(chipBackground(false));
      chip.setOnClickListener(
          v -> {
            setValue(index, option);
            for (int i = 0; i < row.getChildCount(); i++) {
              View child = row.getChildAt(i);
              if (!(child instanceof TextView)) continue;
              boolean on = ((TextView) child).getText().toString().equals(option);
              child.setBackground(chipBackground(on));
              ((TextView) child)
                  .setTextColor(
                      on ? LineTheme.linkColor(activity) : LineTheme.primaryTextColor(activity));
            }
          });
      LinearLayout.LayoutParams params =
          new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      params.rightMargin = dp(6);
      row.addView(chip, params);
    }

    scroller.addView(row);
    return scroller;
  }

  private GradientDrawable chipBackground(boolean selected) {
    GradientDrawable background = new GradientDrawable();
    background.setColor(LineTheme.fieldColor(activity));
    background.setCornerRadius(dp(14));
    background.setStroke(dp(1), selected ? LineTheme.linkColor(activity) : 0x00000000);
    return background;
  }

  private void setValue(int index, String value) {
    while (fillValues.size() <= index) fillValues.add("");
    fillValues.set(index, value);
    updatePreview();
  }

  private void updatePreview() {
    TextView preview = fillPreview;
    if (preview == null) return;
    QuickEntry entry = null;
    // The preview only needs the body that was opened; it is kept in the tag of the root
    Object tag = root.getTag();
    if (tag instanceof QuickEntry) entry = (QuickEntry) tag;
    if (entry == null) return;
    preview.setText(entryText(TemplateParser.render(entry.body, fillValues)));
  }

  // ------------------------------------------------------------------ shared

  private String entryText(String body) {
    return TemplateParser.applyDotPrefix(body == null ? "" : body, QuickStore.dotPrefix());
  }

  private void insert(String text) {
    if (text == null || text.trim().isEmpty()) return;
    if (ChatInput.insert(activity, text)) {
      dialog.dismiss();
      return;
    }
    copyToClipboard(text);
    Toast.makeText(activity, ModuleResources.get(R.string.quick_launcher_copied), Toast.LENGTH_LONG)
        .show();
    dialog.dismiss();
  }

  private void copyToClipboard(String text) {
    try {
      ClipboardManager clipboard =
          (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
      if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Knot", text));
    } catch (Throwable ignored) {
    }
  }

  private TextView footerButton(int textRes, boolean primary) {
    TextView view = new TextView(activity);
    view.setText(ModuleResources.get(textRes));
    view.setTextSize(14);
    view.setTypeface(null, primary ? Typeface.BOLD : Typeface.NORMAL);
    view.setTextColor(
        primary ? LineTheme.linkColor(activity) : LineTheme.secondaryTextColor(activity));
    view.setPadding(dp(14), dp(8), dp(14), dp(8));
    return view;
  }

  private LinearLayout.LayoutParams leftMargin(int px) {
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.leftMargin = px;
    return params;
  }

  private int dp(float value) {
    return Math.round(
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics()));
  }
}
