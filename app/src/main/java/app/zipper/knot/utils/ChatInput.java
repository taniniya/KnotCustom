package app.zipper.knot.utils;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the message field of the open chat room and puts text in it.
 *
 * <p>LINE's composer is not addressed by a stable id here, so the field is picked out of the
 * activity's own view tree: the biggest visible, editable text field wins, and a focused one wins
 * over that. Only the field's text is set — nothing is sent, so the user can check the result and
 * complete an {@code @} mention before sending.
 */
public final class ChatInput {

  private static final int MAX_DEPTH = 60;
  private static final int MAX_VIEWS = 6000;

  private ChatInput() {}

  public static EditText find(Activity activity) {
    if (activity == null || activity.isFinishing()) return null;
    View decor = activity.getWindow() == null ? null : activity.getWindow().peekDecorView();
    if (decor == null) return null;

    List<EditText> fields = new ArrayList<>();
    collect(decor, fields, 0, new int[] {0});

    EditText best = null;
    long bestScore = -1;
    for (EditText field : fields) {
      if (field == null || !field.isShown() || !field.isEnabled()) continue;
      if (field.getWidth() <= 0 || field.getHeight() <= 0) continue;
      if (isSecret(field)) continue;
      long score = (long) field.getWidth() * field.getHeight();
      if (field.isFocused()) score += 1_000_000_000L;
      if (score > bestScore) {
        bestScore = score;
        best = field;
      }
    }
    return best;
  }

  /**
   * Puts the text in the message field. An empty field is filled; with something already typed the
   * text goes in at the cursor, so nothing the user wrote is lost.
   *
   * <p>Only a chat room is written into: on any other screen the search box or a settings field
   * would be the biggest text field around.
   */
  public static boolean insert(Activity activity, String text) {
    EditText field = isChatRoom(activity) ? find(activity) : null;
    if (field == null || text == null) return false;
    try {
      field.requestFocus();
      CharSequence current = field.getText();
      if (current == null || current.toString().trim().isEmpty()) {
        field.setText(text);
        field.setSelection(field.getText().length());
      } else {
        int start = Math.max(field.getSelectionStart(), 0);
        int end = Math.max(field.getSelectionEnd(), start);
        field.getText().replace(start, end, text);
        field.setSelection(Math.min(start + text.length(), field.getText().length()));
      }
      showKeyboard(field);
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  /** True when the activity is LINE's chat room. */
  public static boolean isChatRoom(Activity activity) {
    if (activity == null) return false;
    try {
      String name = activity.getClass().getName();
      String expected = null;
      app.zipper.knot.LineVersion.Config config = app.zipper.knot.LineVersion.get();
      if (config != null) expected = config.chatHeader.chatHistoryActivity;
      if (expected == null || expected.isEmpty()) {
        expected = "jp.naver.line.android.activity.chathistory.ChatHistoryActivity";
      }
      return name.equals(expected) || name.endsWith(".ChatHistoryActivity");
    } catch (Throwable t) {
      return false;
    }
  }

  private static void showKeyboard(EditText field) {
    try {
      InputMethodManager imm =
          (InputMethodManager) field.getContext().getSystemService(Activity.INPUT_METHOD_SERVICE);
      if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
    } catch (Throwable ignored) {
    }
  }

  private static boolean isSecret(EditText field) {
    try {
      return field.getInputType() != 0
          && (field.getInputType() & android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
              == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
    } catch (Throwable t) {
      return false;
    }
  }

  private static void collect(View view, List<EditText> out, int depth, int[] seen) {
    if (view == null || depth > MAX_DEPTH || seen[0] >= MAX_VIEWS) return;
    seen[0]++;
    if (view instanceof EditText) out.add((EditText) view);
    if (!(view instanceof ViewGroup)) return;
    ViewGroup group = (ViewGroup) view;
    int count = group.getChildCount();
    for (int i = 0; i < count; i++) collect(group.getChildAt(i), out, depth + 1, seen);
  }

  /** Chat id of an open chat room, from the intent or from the activity's own request object. */
  public static String chatId(Activity activity) {
    if (activity == null) return null;
    try {
      Intent intent = activity.getIntent();
      if (intent != null) {
        String id = intent.getStringExtra("chatId");
        if (id == null || id.isEmpty()) id = intent.getStringExtra("chat_id");
        if (id != null && !id.isEmpty()) return id;
      }
    } catch (Throwable ignored) {
    }
    app.zipper.knot.LineVersion.Config config = app.zipper.knot.LineVersion.get();
    if (config == null || config.chatActivity.chatIdField.isEmpty()) return null;
    try {
      Object request =
          app.zipper.knot.Reflect.getObjectField(activity, config.chatActivity.chatIdField);
      if (request == null) return null;
      Object id = app.zipper.knot.Reflect.callMethod(request, config.chatActivity.methodGetChatId);
      return id instanceof String ? (String) id : null;
    } catch (Throwable t) {
      return null;
    }
  }
}
