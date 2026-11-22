package app.zipper.knot.hooks;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.widget.Toast;
import app.zipper.knot.Knot;
import app.zipper.knot.KnotConfig;
import app.zipper.knot.LineVersion;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Main;
import app.zipper.knot.R;
import app.zipper.knot.Reflect;
import app.zipper.knot.SettingsStore;
import app.zipper.knot.ui.ReadHistoryViewer;
import app.zipper.knot.utils.LineDBUtils;
import app.zipper.knot.utils.ModuleResources;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adds a "Read by" entry to the message long-press menu that opens the readers of that one message.
 *
 * <p>LINE builds the menu from an item enum and renders every item through a second, presentation
 * enum of the same constant names. Knot's edit history reuses the never-shown INVALID constant of
 * both, and that is the one constant whose rendering is known to work, so while edit history is off
 * this entry takes INVALID. While edit history is on, EditHistoryHook answers for INVALID by name
 * on every menu, so this entry instead borrows a real menu item that this message's menu does not
 * use, and overrides its label, icon and action for that one menu only.
 *
 * <p>The item goes in at the front of the list: LINE's popup lays the items out in a fixed grid, so
 * an entry appended at the end can fall outside the visible rows.
 */
public class ReadUsersMenuHook implements BaseHook {

  private static final String EDIT_HISTORY_PLACEHOLDER = "INVALID";
  private static final String ICON_DRAWABLE = "ic_read_users";
  private static final int ICON_ID = 0x64000011;
  private static final String APPCOMPAT_IMAGE_VIEW = "androidx.appcompat.widget.AppCompatImageView";

  // A borrowed item answers only while the menu it was added to is being drawn
  private static final long ACTIVE_WINDOW_MS = 15000L;
  private static final Object NONE = new Object();

  // Item names LINE itself has put into a menu; never borrowed as our own
  private static final Set<String> seenRealItems = ConcurrentHashMap.newKeySet();

  private static volatile Object borrowedItem;
  private static volatile long borrowedAt;
  private static volatile String lastBorrowedName;

  // The constant this menu draws as "Read by". Matched by name: the menu list holds item-enum
  // instances while the label/icon/action hooks are handed presentation-enum instances of the
  // same constant names, so the objects themselves never compare equal.
  private static volatile String activeName;
  private static volatile Set<String> activeMenuNames = Collections.emptySet();

  private static volatile LineVersion.Config.MessageEditHistory cfg;
  private static ClassLoader cl;
  private static Class<?> itemEnum;
  private static Class<?> presentationEnum;

  private static volatile Object menuAction;
  private static volatile Object invalidItem;
  private static volatile boolean usingInvalid;
  private static final Map<String, Object> presentationCache = new ConcurrentHashMap<>();

  // Kept so the debug menu can show what this hook actually did on the device
  private static final StringBuilder setupNotes = new StringBuilder();
  private static volatile String hookState = "not hooked";
  private static volatile String lastDecision = "no menu built yet";
  private static volatile String lastLoggedDecision = "";
  private static volatile String lastMessage = "no menu built yet";
  private static volatile String lastMsgId;
  private static volatile int menuBuilds;
  private static volatile long lastBuildAt;
  private static boolean msgIdNoteLogged;

  @Override
  public void hook(KnotConfig config, LoadParam lpparam) throws Throwable {
    LineVersion.Config version = LineVersion.get();
    if (version == null) {
      hookState = "LINE version not mapped";
      return;
    }
    cfg = version.messageEditHistory;
    if (cfg.menuListBuilderClass.isEmpty()
        || cfg.menuPresentationEnumClass.isEmpty()
        || cfg.menuItemEnumClass.isEmpty()) {
      hookState =
          "mapping empty (builder='"
              + cfg.menuListBuilderClass
              + "', item enum='"
              + cfg.menuItemEnumClass
              + "', presentation='"
              + cfg.menuPresentationEnumClass
              + "')";
      Knot.log("Knot: read users menu skipped, " + hookState);
      return;
    }
    cl = lpparam.classLoader;
    try {
      itemEnum = Reflect.findClass(cfg.menuItemEnumClass, cl);
      presentationEnum = Reflect.findClass(cfg.menuPresentationEnumClass, cl);
    } catch (Throwable t) {
      hookState = "classes unavailable: " + t;
      Knot.log("Knot: read users menu classes unavailable: " + t);
      return;
    }

    hookState = "hooking";
    hookMenuList();
    hookLabel();
    hookIcon();
    hookAction();
    hookDrawable();

    hookState = setupNotes.length() == 0 ? "installed" : "installed (" + setupNotes + ")";
    Knot.log(
        "Knot: read users menu "
            + hookState
            + " on "
            + cfg.menuListBuilderClass
            + "#"
            + cfg.menuListMethod
            + ", item enum "
            + cfg.menuItemEnumClass
            + " ("
            + itemEnum.getEnumConstants().length
            + " constants), presentation "
            + cfg.menuPresentationEnumClass
            + " ("
            + presentationEnum.getEnumConstants().length
            + " constants)");
  }

  // Logged only when it changes: one long-press builds the menu more than once
  private static void decide(String decision) {
    lastDecision = decision;
    if (!decision.equals(lastLoggedDecision)) {
      lastLoggedDecision = decision;
      Knot.log("Knot: read users menu -> " + decision);
    }
  }

  private static void note(String text) {
    if (setupNotes.length() > 0) setupNotes.append("; ");
    setupNotes.append(text);
  }

  private static void hookMenuList() {
    try {
      Knot.hookAll(
          Reflect.findClass(cfg.menuListBuilderClass, cl),
          cfg.menuListMethod,
          ReadUsersMenuHook::onMenuList);
    } catch (Throwable t) {
      note("list hook failed: " + t);
      Knot.log("Knot: read users menu list hook failed: " + t);
    }
  }

  private static Object onMenuList(XposedInterface.Chain chain) throws Throwable {
    // Cleared first so a stale borrow can never speak for a real item of this menu
    borrowedItem = null;
    borrowedAt = 0L;
    activeName = null;
    activeMenuNames = Collections.emptySet();
    Object result = chain.proceed();
    if (!(result instanceof List)) return result;

    List<?> items = (List<?>) result;
    Set<String> names = new HashSet<>();
    for (Object item : items) {
      if (item instanceof Enum) {
        String name = ((Enum<?>) item).name();
        seenRealItems.add(name);
        names.add(name);
      }
    }
    // The names this menu already owns; a same-named constant must keep its own rendering
    activeMenuNames = names;
    if (!SettingsStore.get("record_read_history", false)) return result;
    // Only the 3-argument builder is the message menu; another overload is left alone
    if (chain.getArgs().size() != 3) return result;

    Object data = messageData(chain);
    String msgId = messageId(data);
    lastMsgId = msgId;
    menuBuilds++;
    lastBuildAt = System.currentTimeMillis();
    lastMessage = msgId == null ? "no message id" : "message " + msgId;
    if (msgId == null && !msgIdNoteLogged) {
      msgIdNoteLogged = true;
      Knot.log(
          "Knot: read users menu could not read the message id ("
              + cfg.menuMessageIdField
              + "); the entry is still added"
              + (data == null ? ", message data null" : ""));
    }

    List<Object> copy = new ArrayList<>(items.size() + 1);
    for (Object item : items) copy.add(item);

    Object item = pickItem(copy, data);
    if (item == null) return result;
    // At the front: the popup's grid is full with LINE's own items, and the tail gets cut
    copy.add(0, item);
    return copy;
  }

  private static Object pickItem(List<Object> current, Object data) {
    boolean editHistoryOn = Main.options.showEditHistory.enabled;

    // With edit history off its INVALID slot is free, and INVALID is the one constant whose menu
    // rendering Knot already relies on. With edit history on, EditHistoryHook answers for INVALID
    // (by name, for every menu), so this entry must take a real item instead.
    Object invalid = invalidItem();
    if (!editHistoryOn && invalid != null && !current.contains(invalid)) {
      usingInvalid = true;
      activeName = EDIT_HISTORY_PLACEHOLDER;
      borrowedAt = System.currentTimeMillis();
      decide("INVALID (edit history off)");
      return invalid;
    }

    usingInvalid = false;
    Object borrowed = borrowItem(current);
    if (borrowed == null) {
      decide(
          "no free item (edit history on="
              + editHistoryOn
              + ", INVALID in menu="
              + (invalid != null && current.contains(invalid))
              + ")");
      return null;
    }
    borrowedItem = borrowed;
    borrowedAt = System.currentTimeMillis();
    activeName = nameOf(borrowed);
    decide(
        "borrowed "
            + nameOf(borrowed)
            + (editHistoryOn ? " (edit history holds INVALID)" : " (INVALID in this menu)")
            + (isEdited(data) ? ", message edited" : ""));
    return borrowed;
  }

  // A constant this menu does not use, so it can stand in for "Read by" for this menu only
  private static Object borrowItem(List<Object> current) {
    synchronized (ReadUsersMenuHook.class) {
      // Keep the same stand-in across rebuilds of one menu so label, icon and action agree
      Object stable = enumConstant(itemEnum, lastBorrowedName);
      if (stable != null && !current.contains(stable) && usableAsStandIn(stable)) return stable;

      // An item LINE has shown before is the surer bet: its rendering is proven. One it has never
      // shown is only a guess about how LINE maps the constant onto the popup.
      Object shownBefore = null;
      Object neverShown = null;
      try {
        for (Object candidate : itemEnum.getEnumConstants()) {
          if (!(candidate instanceof Enum)) continue;
          String name = ((Enum<?>) candidate).name();
          if (EDIT_HISTORY_PLACEHOLDER.equals(name)) continue;
          if (current.contains(candidate)) continue;
          if (!usableAsStandIn(candidate)) continue;
          if (seenRealItems.contains(name)) {
            if (shownBefore == null) shownBefore = candidate;
          } else if (neverShown == null) {
            neverShown = candidate;
          }
        }
      } catch (Throwable t) {
        Knot.log("Knot: read users menu item borrow failed: " + t);
      }
      Object borrowed = shownBefore != null ? shownBefore : neverShown;
      if (borrowed instanceof Enum) lastBorrowedName = ((Enum<?>) borrowed).name();
      return borrowed;
    }
  }

  private static boolean usableAsStandIn(Object candidate) {
    if (!(candidate instanceof Enum)) return false;
    Object presentation = presentationConstant(((Enum<?>) candidate).name());
    if (presentation == null) return false;
    // Only a normal, renderable item can be borrowed; a no-action one is our own spare anyway
    return actionOf(presentation) != null;
  }

  private static void hookLabel() {
    try {
      Method label = Reflect.findMethodExact(presentationEnum, cfg.methodMenuLabel, Context.class);
      Knot.module
          .hook(label)
          .intercept(
              chain ->
                  respondsTo(chain)
                      ? ModuleResources.get(R.string.read_users_title)
                      : chain.proceed());
    } catch (Throwable t) {
      note("label hook failed: " + t);
      Knot.log("Knot: read users menu label hook failed: " + t);
    }
  }

  private static void hookIcon() {
    try {
      Method icon = Reflect.findMethodExact(presentationEnum, cfg.methodMenuIcon);
      Knot.module
          .hook(icon)
          .intercept(chain -> respondsTo(chain) ? (Object) ICON_ID : chain.proceed());
    } catch (Throwable t) {
      note("icon hook failed: " + t);
      Knot.log("Knot: read users menu icon hook failed: " + t);
    }
  }

  private static void hookAction() {
    try {
      Method accessor = Reflect.findMethodExact(presentationEnum, cfg.methodMenuActionAccessor);
      Knot.module
          .hook(accessor)
          .intercept(
              chain -> {
                if (!respondsTo(chain)) return chain.proceed();
                Object action = action();
                return action != null ? action : chain.proceed();
              });
    } catch (Throwable t) {
      note("action hook failed: " + t);
      Knot.log("Knot: read users menu action hook failed: " + t);
    }
  }

  // Module drawables are unresolvable in LINE's Resources, so the icon is served under a fake id
  private static void hookDrawable() {
    hookDrawable(APPCOMPAT_IMAGE_VIEW);
    // Plain ImageView as well, so the fake id can never reach LINE's Resources
    hookDrawable("android.widget.ImageView");
  }

  private static void hookDrawable(String className) {
    try {
      Knot.module
          .hook(Reflect.findMethodExact(className, cl, "setImageResource", int.class))
          .intercept(
              chain -> {
                if ((int) chain.getArg(0) != ICON_ID) return chain.proceed();
                Drawable icon = ModuleResources.drawable(ICON_DRAWABLE);
                if (icon == null) return chain.proceed();
                ((ImageView) chain.getThisObject()).setImageDrawable(icon);
                return null;
              });
    } catch (Throwable t) {
      note("drawable hook failed (" + className + "): " + t);
      Knot.log("Knot: read users menu icon drawable hook failed (" + className + "): " + t);
    }
  }

  private static boolean respondsTo(XposedInterface.Chain chain) {
    Object self = chain.getThisObject();
    if (!(self instanceof Enum)) return false;
    if (!SettingsStore.get("record_read_history", false)) return false;

    // Compared by name, never by identity: `self` is a presentation-enum constant while the
    // constant this menu borrowed is an item-enum constant of the same name, so `self == borrowed`
    // was always false and the borrowed cell kept drawing its own label ("Shop") and icon.
    String active = activeName;
    if (active == null) return false;
    if (System.currentTimeMillis() - borrowedAt >= ACTIVE_WINDOW_MS) return false;
    String selfName = ((Enum<?>) self).name();
    if (!active.equals(selfName)) return false;
    // A real item of this menu carrying the same name keeps its own label, icon and action
    return !activeMenuNames.contains(selfName);
  }

  private static Object action() {
    if (menuAction == null) {
      try {
        menuAction = buildAction();
      } catch (Throwable t) {
        note("action build failed: " + t);
        Knot.log("Knot: read users menu action unavailable: " + t);
      }
    }
    return menuAction;
  }

  private static Object buildAction() throws Throwable {
    Class<?> lambdaClass = Reflect.findClass(cfg.menuActionLambdaClass, cl);
    Object callback =
        Proxy.newProxyInstance(
            cl,
            new Class<?>[] {lambdaClass.getDeclaredConstructors()[0].getParameterTypes()[0]},
            (proxy, method, args) -> {
              try {
                openViewer(args);
              } catch (Throwable t) {
                Knot.log("Knot: read users menu click failed: " + t);
              }
              return kotlinUnit();
            });
    return Reflect.newInstance(lambdaClass, callback);
  }

  private static void openViewer(Object[] args) {
    Context context = null;
    String msgId = null;
    if (args != null) {
      for (Object arg : args) {
        if (arg instanceof Context) {
          if (context == null) context = (Context) arg;
        } else if (msgId == null) {
          msgId = messageId(messageData(arg));
        }
      }
    }
    // The menu context the click is handed need not be the one the list was built from
    if (msgId == null) msgId = lastMsgId;
    if (context == null) context = Knot.currentApplication();
    if (context == null) {
      Knot.log("Knot: read users menu click without a context");
      return;
    }
    if (msgId == null) {
      Knot.log("Knot: read users menu click without a message id: " + describe(args));
      String text = ModuleResources.get(R.string.read_users_no_message);
      if (text == null || text.isEmpty()) text = "Could not identify this message.";
      try {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
      } catch (Throwable ignored) {
      }
      return;
    }
    String chatId = LineDBUtils.resolveChatIdByServerId(msgId);
    Knot.log("Knot: read users menu opened for " + msgId + " (chat " + chatId + ")");
    ReadHistoryViewer.showForMessage(context, chatId, msgId);
  }

  private static String describe(Object[] args) {
    if (args == null) return "none";
    StringBuilder sb = new StringBuilder();
    for (Object arg : args) {
      if (sb.length() > 0) sb.append(", ");
      sb.append(arg == null ? "null" : arg.getClass().getName());
    }
    return sb.toString();
  }

  private static Object messageData(XposedInterface.Chain chain) {
    try {
      List<Object> args = chain.getArgs();
      if (args.size() != 3) return null;
      return messageData(args.get(2));
    } catch (Throwable t) {
      return null;
    }
  }

  private static Object messageData(Object menuContext) {
    if (menuContext == null) return null;
    try {
      Object holder = Reflect.getObjectField(menuContext, cfg.menuContextMessageField);
      return holder == null ? null : Reflect.getObjectField(holder, cfg.menuMessageDataField);
    } catch (Throwable t) {
      return null;
    }
  }

  private static boolean isEdited(Object data) {
    if (data == null) return false;
    try {
      return Boolean.TRUE.equals(Reflect.getObjectField(data, cfg.menuEditedFlagField));
    } catch (Throwable t) {
      return false;
    }
  }

  private static String messageId(Object data) {
    if (data == null) return null;
    try {
      Object id = Reflect.getObjectField(data, cfg.menuMessageIdField);
      String value = id == null ? "" : String.valueOf(id);
      return value.isEmpty() || "0".equals(value) || "null".equals(value) ? null : value;
    } catch (Throwable t) {
      return null;
    }
  }

  private static Object invalidItem() {
    if (invalidItem == null) {
      try {
        invalidItem = enumConstant(itemEnum, EDIT_HISTORY_PLACEHOLDER);
      } catch (Throwable ignored) {
      }
    }
    return invalidItem;
  }

  private static Object presentationConstant(String name) {
    if (name == null) return null;
    Map<String, Object> cache = presentationCache;
    Object cached = cache.get(name);
    if (cached != null) return cached == NONE ? null : cached;
    Object constant = null;
    try {
      constant = enumConstant(presentationEnum, name);
    } catch (Throwable ignored) {
    }
    cache.put(name, constant == null ? NONE : constant);
    return constant;
  }

  private static Object actionOf(Object presentation) {
    try {
      return Reflect.callMethod(presentation, cfg.methodMenuActionAccessor);
    } catch (Throwable t) {
      return null;
    }
  }

  private static Object kotlinUnit() {
    try {
      LineVersion.Config.Kotlin kotlin = LineVersion.get().kotlin;
      return Reflect.findClass(kotlin.unitClass, cl).getField(kotlin.fieldUnitInstance).get(null);
    } catch (Throwable t) {
      return null;
    }
  }

  private static Object enumConstant(Class<?> enumClass, String name) {
    if (enumClass == null || name == null) return null;
    for (Object constant : enumClass.getEnumConstants()) {
      if (constant instanceof Enum && ((Enum<?>) constant).name().equals(name)) return constant;
    }
    return null;
  }

  private static String nameOf(Object constant) {
    return constant instanceof Enum ? ((Enum<?>) constant).name() : String.valueOf(constant);
  }

  /** One line for the debug info panel. */
  public static String hookSummary() {
    return hookState + ", " + lastDecision;
  }

  /** What this hook did on this device, for the debug menu and the logs. */
  public static String diagnostics() {
    StringBuilder sb = new StringBuilder();
    sb.append("state: ").append(hookState).append('\n');
    LineVersion.Config.MessageEditHistory config = cfg;
    if (config == null) {
      sb.append("mapping: not read\n");
    } else {
      sb.append("builder: ").append(text(config.menuListBuilderClass)).append('\n');
      sb.append("method: ").append(text(config.menuListMethod)).append('\n');
      sb.append("item enum: ").append(text(config.menuItemEnumClass)).append('\n');
      sb.append("presentation: ").append(text(config.menuPresentationEnumClass)).append('\n');
      sb.append("label/icon/action: ")
          .append(text(config.methodMenuLabel))
          .append(" / ")
          .append(text(config.methodMenuIcon))
          .append(" / ")
          .append(text(config.methodMenuActionAccessor))
          .append('\n');
      sb.append("message id field: ").append(text(config.menuMessageIdField)).append('\n');
    }
    sb.append("record_read_history: ")
        .append(SettingsStore.get("record_read_history", false))
        .append('\n');
    sb.append("edit history on: ").append(Main.options.showEditHistory.enabled).append('\n');
    sb.append("menus built: ").append(menuBuilds);
    if (lastBuildAt > 0) {
      sb.append(" (last ")
          .append((System.currentTimeMillis() - lastBuildAt) / 1000)
          .append("s ago)");
    }
    sb.append('\n');
    sb.append("last decision: ").append(lastDecision).append('\n');
    sb.append("last message: ").append(lastMessage).append('\n');
    sb.append("borrow: ").append(lastBorrowedName == null ? "none" : lastBorrowedName).append('\n');
    sb.append("active: ").append(activeName == null ? "none" : activeName).append('\n');
    if (itemEnum != null) {
      sb.append("item constants: ").append(itemNames(itemEnum)).append('\n');
    }
    if (presentationEnum != null) {
      sb.append("presentation constants: ").append(itemNames(presentationEnum));
    }
    return sb.toString().trim();
  }

  private static String text(String value) {
    return value == null || value.isEmpty() ? "(empty)" : value;
  }

  private static String itemNames(Class<?> enumClass) {
    try {
      StringBuilder sb = new StringBuilder();
      for (Object constant : enumClass.getEnumConstants()) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(nameOf(constant));
      }
      return sb.toString();
    } catch (Throwable t) {
      return "unavailable";
    }
  }
}
