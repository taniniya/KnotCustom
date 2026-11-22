package app.zipper.knot.ui.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import app.zipper.knot.R;
import app.zipper.knot.commands.QuickEntry;
import app.zipper.knot.commands.QuickStore;
import app.zipper.knot.commands.TemplateParser;
import app.zipper.knot.utils.LineTheme;
import app.zipper.knot.utils.ModuleResources;
import java.util.ArrayList;
import java.util.List;

/**
 * The management screen behind "Template list" in Knot's settings: add, edit, move and delete
 * entries, and drop blocks into the body with one tap.
 *
 * <p>One screen for the one list. What earlier versions called commands are templates now, so there
 * is no second screen and no preset to restore.
 */
public final class TemplateManager {

  private final Context context;
  private final LinearLayout root;
  private AlertDialog dialog;
  private List<QuickEntry> entries = new ArrayList<>();

  private TemplateManager(Context context) {
    this.context = context;
    this.root = new LinearLayout(context);
  }

  public static void showTemplates(Context context) {
    showTemplates(context, null);
  }

  public static void showTemplates(Context context, Runnable onClosed) {
    new TemplateManager(context).open(onClosed);
  }

  private void open(Runnable onClosed) {
    root.setOrientation(LinearLayout.VERTICAL);
    int side = SettingsViews.dp(context, 20);
    root.setPadding(side, SettingsViews.dp(context, 8), side, SettingsViews.dp(context, 8));

    ScrollView scroller = new ScrollView(context);
    scroller.addView(root);

    dialog =
        new AlertDialog.Builder(context, LineTheme.dialogTheme(context))
            .setTitle(ModuleResources.get(R.string.cmd_manager_title_templates))
            .setView(scroller)
            .setPositiveButton(ModuleResources.get(R.string.common_close), null)
            .show();
    LineTheme.applyDialogColors(dialog, context);
    if (onClosed != null) dialog.setOnDismissListener(d -> onClosed.run());
    reload();
  }

  private void reload() {
    entries = new ArrayList<>(QuickStore.entries());
    root.removeAllViews();
    root.addView(buildActions());
    if (entries.isEmpty()) {
      TextView empty = new TextView(context);
      empty.setText(ModuleResources.get(R.string.cmd_manager_empty));
      empty.setTextSize(13);
      empty.setTextColor(LineTheme.secondaryTextColor(context));
      empty.setPadding(0, SettingsViews.dp(context, 16), 0, SettingsViews.dp(context, 16));
      root.addView(empty);
    } else {
      for (int i = 0; i < entries.size(); i++) root.addView(buildRow(i));
    }
  }

  private View buildActions() {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, 0, 0, SettingsViews.dp(context, 6));

    TextView add = new TextView(context);
    add.setText(ModuleResources.get(R.string.cmd_manager_add));
    add.setTextSize(14);
    add.setTypeface(null, Typeface.BOLD);
    add.setTextColor(LineTheme.linkColor(context));
    add.setPadding(
        0,
        SettingsViews.dp(context, 8),
        SettingsViews.dp(context, 12),
        SettingsViews.dp(context, 8));
    add.setOnClickListener(v -> openEditor(-1));
    row.addView(add, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    return row;
  }

  private View buildRow(final int index) {
    QuickEntry entry = entries.get(index);

    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.VERTICAL);
    int pad = SettingsViews.dp(context, 8);
    row.setPadding(pad, pad, pad, pad);

    TextView title = new TextView(context);
    title.setText(entry.displayTitle());
    title.setTextSize(14);
    title.setTypeface(null, Typeface.BOLD);
    title.setTextColor(LineTheme.primaryTextColor(context));
    row.addView(title);

    TextView body = new TextView(context);
    body.setText(entry.describe());
    body.setTextSize(12);
    body.setTextColor(LineTheme.secondaryTextColor(context));
    body.setPadding(0, SettingsViews.dp(context, 2), 0, 0);
    body.setMaxLines(3);
    row.addView(body);

    row.setOnClickListener(v -> openActions(index));
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.bottomMargin = SettingsViews.dp(context, 2);
    row.setLayoutParams(params);
    return row;
  }

  private void openActions(final int index) {
    final int count = entries.size();
    List<String> labels = new ArrayList<>();
    labels.add(ModuleResources.get(R.string.cmd_manager_edit));
    if (index > 0) labels.add(ModuleResources.get(R.string.cmd_manager_move_up));
    if (index < count - 1) labels.add(ModuleResources.get(R.string.cmd_manager_move_down));
    labels.add(ModuleResources.get(R.string.cmd_manager_delete));

    LineTheme.applyDialogColors(
        new AlertDialog.Builder(context, LineTheme.dialogTheme(context))
            .setItems(
                labels.toArray(new String[0]),
                (d, which) -> {
                  String choice = labels.get(which);
                  if (choice.equals(ModuleResources.get(R.string.cmd_manager_edit))) {
                    openEditor(index);
                  } else if (choice.equals(ModuleResources.get(R.string.cmd_manager_move_up))) {
                    move(index, index - 1);
                  } else if (choice.equals(ModuleResources.get(R.string.cmd_manager_move_down))) {
                    move(index, index + 1);
                  } else {
                    confirmDelete(index);
                  }
                })
            .setNegativeButton(ModuleResources.get(R.string.settings_cancel), null)
            .show(),
        context);
  }

  private void move(int from, int to) {
    if (to < 0 || to >= entries.size()) return;
    QuickEntry entry = entries.remove(from);
    entries.add(to, entry);
    persist();
    reload();
  }

  private void confirmDelete(final int index) {
    LineTheme.applyDialogColors(
        new AlertDialog.Builder(context, LineTheme.dialogTheme(context))
            .setTitle(ModuleResources.get(R.string.cmd_manager_delete))
            .setMessage(ModuleResources.get(R.string.cmd_manager_delete_confirm))
            .setPositiveButton(
                ModuleResources.get(R.string.settings_yes),
                (d, w) -> {
                  entries.remove(index);
                  persist();
                  reload();
                })
            .setNegativeButton(ModuleResources.get(R.string.settings_cancel), null)
            .show(),
        context);
  }

  private void persist() {
    QuickStore.save(entries);
  }

  // ---------------------------------------------------------------- editor

  private void openEditor(final int index) {
    boolean isNew = index < 0;
    final QuickEntry editing = isNew ? new QuickEntry("", "") : entries.get(index).copy();

    LinearLayout box = new LinearLayout(context);
    box.setOrientation(LinearLayout.VERTICAL);
    int side = SettingsViews.dp(context, 20);
    box.setPadding(side, SettingsViews.dp(context, 6), side, 0);

    final EditText title = new EditText(context);
    title.setHint(ModuleResources.get(R.string.cmd_editor_title_hint));
    title.setSingleLine(true);
    title.setText(editing.title);
    box.addView(title);

    final EditText body = new EditText(context);
    body.setHint(ModuleResources.get(R.string.cmd_editor_body_hint));
    body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    body.setMinLines(3);
    body.setMaxLines(6);
    body.setGravity(Gravity.TOP | Gravity.START);
    body.setText(editing.body);
    body.setSelection(body.getText().length());
    box.addView(body);

    LinearLayout buttons = new LinearLayout(context);
    buttons.setOrientation(LinearLayout.HORIZONTAL);
    buttons.setPadding(0, SettingsViews.dp(context, 4), 0, 0);

    TextView free = blockButton(R.string.cmd_editor_add_free);
    free.setOnClickListener(v -> promptFreeBlock(body));
    TextView choice = blockButton(R.string.cmd_editor_add_choice);
    choice.setOnClickListener(v -> promptChoiceBlock(body));
    buttons.addView(
        free, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    choice.setPadding(
        SettingsViews.dp(context, 8), free.getPaddingTop(), 0, free.getPaddingBottom());
    buttons.addView(
        choice, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    box.addView(buttons);

    final TextView summary = new TextView(context);
    summary.setTextSize(11);
    summary.setTextColor(LineTheme.secondaryTextColor(context));
    summary.setPadding(0, SettingsViews.dp(context, 6), 0, 0);
    box.addView(summary);

    Runnable refreshSummary = () -> summary.setText(blockSummary(body.getText().toString()));
    body.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {
            refreshSummary.run();
          }

          @Override
          public void afterTextChanged(Editable s) {}
        });
    refreshSummary.run();

    TextView hint = new TextView(context);
    hint.setText(ModuleResources.get(R.string.cmd_editor_hint));
    hint.setTextSize(11);
    hint.setTextColor(LineTheme.secondaryTextColor(context));
    hint.setPadding(0, SettingsViews.dp(context, 6), 0, 0);
    box.addView(hint);

    AlertDialog editor =
        new AlertDialog.Builder(context, LineTheme.dialogTheme(context))
            .setTitle(
                ModuleResources.get(
                    isNew ? R.string.cmd_editor_title_new : R.string.cmd_editor_title_edit))
            .setView(box)
            .setPositiveButton(
                ModuleResources.get(R.string.cmd_editor_save),
                (d, w) -> {
                  String text = body.getText().toString();
                  if (text.trim().isEmpty()) {
                    Toast.makeText(
                            context,
                            ModuleResources.get(R.string.cmd_editor_empty_body),
                            Toast.LENGTH_SHORT)
                        .show();
                    return;
                  }
                  QuickEntry entry = new QuickEntry(title.getText().toString().trim(), text.trim());
                  if (isNew) entries.add(entry);
                  else entries.set(index, entry);
                  persist();
                  reload();
                })
            .setNegativeButton(ModuleResources.get(R.string.settings_cancel), null)
            .show();
    LineTheme.applyDialogColors(editor, context);
  }

  private TextView blockButton(int textRes) {
    TextView view = new TextView(context);
    view.setText(ModuleResources.get(textRes));
    view.setTextSize(12);
    view.setTextColor(LineTheme.linkColor(context));
    view.setGravity(Gravity.CENTER);
    view.setPadding(0, SettingsViews.dp(context, 8), 0, SettingsViews.dp(context, 8));
    return view;
  }

  private String blockSummary(String body) {
    List<TemplateParser.Block> blocks = TemplateParser.blocks(body);
    if (blocks.isEmpty()) return ModuleResources.get(R.string.cmd_editor_no_blocks);
    List<String> names = new ArrayList<>();
    for (TemplateParser.Block block : blocks) names.add(block.summary());
    return ModuleResources.get(
        R.string.cmd_editor_blocks_summary, blocks.size(), String.join(", ", names));
  }

  private void promptFreeBlock(final EditText body) {
    final EditText label = new EditText(context);
    label.setHint(ModuleResources.get(R.string.cmd_editor_free_label_hint));
    label.setSingleLine(true);
    int side = SettingsViews.dp(context, 20);
    label.setPadding(side, SettingsViews.dp(context, 8), side, 0);

    LineTheme.applyDialogColors(
        new AlertDialog.Builder(context, LineTheme.dialogTheme(context))
            .setTitle(ModuleResources.get(R.string.cmd_editor_free_label_title))
            .setView(label)
            .setPositiveButton(
                ModuleResources.get(R.string.cmd_editor_save),
                (d, w) -> {
                  String name = label.getText().toString().trim();
                  if (name.isEmpty()) return;
                  insertAtCursor(body, "{入力:" + name + "}");
                })
            .setNegativeButton(ModuleResources.get(R.string.settings_cancel), null)
            .show(),
        context);
  }

  private void promptChoiceBlock(final EditText body) {
    LinearLayout box = new LinearLayout(context);
    box.setOrientation(LinearLayout.VERTICAL);
    int side = SettingsViews.dp(context, 20);
    box.setPadding(side, SettingsViews.dp(context, 6), side, 0);

    final EditText options = new EditText(context);
    options.setHint(ModuleResources.get(R.string.cmd_editor_choice_options_hint));
    options.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    options.setMinLines(2);
    box.addView(options);

    final CheckBox allowFree = new CheckBox(context);
    allowFree.setText(ModuleResources.get(R.string.cmd_editor_choice_allow_free));
    allowFree.setChecked(false);
    box.addView(allowFree);

    LineTheme.applyDialogColors(
        new AlertDialog.Builder(context, LineTheme.dialogTheme(context))
            .setTitle(ModuleResources.get(R.string.cmd_editor_choice_title))
            .setView(box)
            .setPositiveButton(
                ModuleResources.get(R.string.cmd_editor_save),
                (d, w) -> {
                  List<String> parts = splitOptions(options.getText().toString());
                  if (parts.isEmpty()) {
                    Toast.makeText(
                            context,
                            ModuleResources.get(R.string.cmd_editor_options_required),
                            Toast.LENGTH_SHORT)
                        .show();
                    return;
                  }
                  String token =
                      "{"
                          + (allowFree.isChecked() ? "選択+:" : "選択:")
                          + String.join("|", parts)
                          + "}";
                  insertAtCursor(body, token);
                })
            .setNegativeButton(ModuleResources.get(R.string.settings_cancel), null)
            .show(),
        context);
  }

  private static List<String> splitOptions(String text) {
    List<String> parts = new ArrayList<>();
    if (text == null) return parts;
    for (String line : text.split("[\n,]|、")) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty()) parts.add(trimmed);
    }
    return parts;
  }

  private static void insertAtCursor(EditText field, String token) {
    int start = Math.max(field.getSelectionStart(), 0);
    int end = Math.max(field.getSelectionEnd(), start);
    field.getText().replace(start, end, token);
    field.setSelection(Math.min(start + token.length(), field.getText().length()));
  }

  /** Used by the settings row to show how many entries the list holds. */
  public static int count() {
    return QuickStore.count();
  }
}
