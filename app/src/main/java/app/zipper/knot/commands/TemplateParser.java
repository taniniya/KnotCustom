package app.zipper.knot.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the blocks of a template body.
 *
 * <p>A body is plain text with two kinds of block in it:
 *
 * <ul>
 *   <li>{@code {入力:金額}} — free input with a label, filled in when the entry is used.
 *   <li>{@code {選択:表|裏}} — one of the listed choices; {@code {選択+:...}} also allows typing
 *       something else.
 * </ul>
 *
 * <p>Anything that does not look like a block stays literal text, so a body without blocks is just
 * the text to insert. This class holds no Android dependency so the parsing can be tested on its
 * own.
 */
public final class TemplateParser {

  public enum Kind {
    FREE_INPUT,
    CHOICE,
    CHOICE_OR_INPUT
  }

  public static final class Block {
    public final Kind kind;

    /** Label of a free-input block; empty for a choice block. */
    public final String label;

    public final List<String> options;

    /** The body text this block was written as, kept for a block left unfilled. */
    public final String token;

    Block(Kind kind, String label, List<String> options, String token) {
      this.kind = kind;
      this.label = label;
      this.options = options;
      this.token = token;
    }

    public boolean allowsFreeText() {
      return kind == Kind.CHOICE_OR_INPUT;
    }

    /** Short name for the block, used in a summary of what an entry asks for. */
    public String summary() {
      if (kind == Kind.FREE_INPUT) return label;
      return String.join("/", options);
    }
  }

  public static final class Token {
    /** Literal text, or null for a block. */
    public final String text;

    public final Block block;

    private Token(String text, Block block) {
      this.text = text;
      this.block = block;
    }

    public boolean isBlock() {
      return block != null;
    }
  }

  private TemplateParser() {}

  public static List<Token> tokenize(String body) {
    List<Token> tokens = new ArrayList<>();
    if (body == null || body.isEmpty()) return tokens;

    StringBuilder literal = new StringBuilder();
    int index = 0;
    while (index < body.length()) {
      int open = body.indexOf('{', index);
      int close = open < 0 ? -1 : body.indexOf('}', open + 1);
      if (open < 0 || close < 0) {
        literal.append(body, index, body.length());
        break;
      }

      Block block = parseBlock(body.substring(open + 1, close));
      if (block == null) {
        // Not one of ours: the brace stays part of the text
        literal.append(body, index, open + 1);
        index = open + 1;
        continue;
      }

      literal.append(body, index, open);
      if (literal.length() > 0) {
        tokens.add(new Token(literal.toString(), null));
        literal.setLength(0);
      }
      tokens.add(new Token(null, block));
      index = close + 1;
    }

    if (literal.length() > 0) tokens.add(new Token(literal.toString(), null));
    return tokens;
  }

  public static List<Block> blocks(String body) {
    List<Block> blocks = new ArrayList<>();
    for (Token token : tokenize(body)) {
      if (token.isBlock()) blocks.add(token.block);
    }
    return blocks;
  }

  public static boolean hasBlocks(String body) {
    for (Token token : tokenize(body)) {
      if (token.isBlock()) return true;
    }
    return false;
  }

  /**
   * Fills the blocks in order with the given values. A block left empty keeps its token, so an
   * unfinished entry is visible instead of turning into a malformed line.
   */
  public static String render(String body, List<String> values) {
    StringBuilder out = new StringBuilder();
    int index = 0;
    for (Token token : tokenize(body)) {
      if (!token.isBlock()) {
        out.append(token.text);
        continue;
      }
      String value = values != null && index < values.size() ? values.get(index) : null;
      index++;
      out.append(value == null || value.trim().isEmpty() ? token.block.token : value);
    }
    return out.toString();
  }

  /**
   * Prefixes the text with a dot when the switch is on. Text that already starts with a dot is left
   * alone, and so is empty text.
   */
  public static String applyDotPrefix(String text, boolean on) {
    if (text == null) return "";
    if (!on || text.trim().isEmpty()) return text;
    int index = 0;
    while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
    if (index < text.length() && text.charAt(index) == '.') return text;
    return text.substring(0, index) + "." + text.substring(index);
  }

  /** The body with each block written as its label, for a one-line preview. */
  public static String describe(String body) {
    StringBuilder out = new StringBuilder();
    for (Token token : tokenize(body)) {
      if (!token.isBlock()) {
        out.append(token.text);
        continue;
      }
      Block block = token.block;
      out.append('[').append(block.summary()).append(']');
    }
    return out.toString();
  }

  private static Block parseBlock(String inner) {
    if (inner == null) return null;
    String text = inner.trim();
    if (text.startsWith("入力:")) {
      String label = text.substring("入力:".length()).trim();
      if (label.isEmpty()) return null;
      return new Block(Kind.FREE_INPUT, label, Collections.emptyList(), "{" + inner + "}");
    }
    if (text.startsWith("選択+:")) {
      List<String> options = options(text.substring("選択+:".length()));
      return options.isEmpty()
          ? null
          : new Block(Kind.CHOICE_OR_INPUT, "", options, "{" + inner + "}");
    }
    if (text.startsWith("選択:")) {
      List<String> options = options(text.substring("選択:".length()));
      return options.isEmpty() ? null : new Block(Kind.CHOICE, "", options, "{" + inner + "}");
    }
    return null;
  }

  private static List<String> options(String text) {
    List<String> options = new ArrayList<>();
    if (text == null) return options;
    for (String part : text.split("[|｜]")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) options.add(trimmed);
    }
    return options;
  }
}
