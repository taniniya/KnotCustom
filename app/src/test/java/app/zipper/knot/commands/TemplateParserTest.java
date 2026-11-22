package app.zipper.knot.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class TemplateParserTest {

  @Test
  public void plainTextHasNoBlocks() {
    String body = "/mycoin";
    assertFalse(TemplateParser.hasBlocks(body));
    assertTrue(TemplateParser.blocks(body).isEmpty());
    assertEquals("/mycoin", TemplateParser.render(body, null));
  }

  @Test
  public void freeInputBlockIsParsed() {
    List<TemplateParser.Block> blocks = TemplateParser.blocks("/ai {入力:内容}");
    assertEquals(1, blocks.size());
    assertEquals(TemplateParser.Kind.FREE_INPUT, blocks.get(0).kind);
    assertEquals("内容", blocks.get(0).label);
    assertFalse(blocks.get(0).allowsFreeText());
  }

  @Test
  public void choiceBlockIsParsed() {
    List<TemplateParser.Block> blocks = TemplateParser.blocks("/coints {選択:表|裏}");
    assertEquals(1, blocks.size());
    assertEquals(TemplateParser.Kind.CHOICE, blocks.get(0).kind);
    assertEquals(Arrays.asList("表", "裏"), blocks.get(0).options);
    assertFalse(blocks.get(0).allowsFreeText());
  }

  @Test
  public void choiceBlockWithFreeTextIsParsed() {
    List<TemplateParser.Block> blocks = TemplateParser.blocks("/slot {選択+:100|500|1000|all}");
    assertEquals(1, blocks.size());
    assertEquals(TemplateParser.Kind.CHOICE_OR_INPUT, blocks.get(0).kind);
    assertEquals(Arrays.asList("100", "500", "1000", "all"), blocks.get(0).options);
    assertTrue(blocks.get(0).allowsFreeText());
  }

  @Test
  public void severalBlocksKeepTheirOrder() {
    List<TemplateParser.Block> blocks = TemplateParser.blocks("/give coin {入力:数} {入力:@メンション}");
    assertEquals(2, blocks.size());
    assertEquals("数", blocks.get(0).label);
    assertEquals("@メンション", blocks.get(1).label);
  }

  @Test
  public void renderFillsBlocksInOrder() {
    String body = "/bigrob {入力:金額} {入力:@ユーザー}";
    assertEquals(
        "/bigrob 100 @tanishi", TemplateParser.render(body, Arrays.asList("100", "@tanishi")));
  }

  @Test
  public void renderLeavesAnEmptyBlockAsItsToken() {
    String body = "/pay {入力:金額} {入力:@メンション}";
    assertEquals("/pay 500 {入力:@メンション}", TemplateParser.render(body, Arrays.asList("500", "")));
  }

  @Test
  public void renderKeepsAnUnknownBraceLiteral() {
    String body = "/echo {hello}";
    assertFalse(TemplateParser.hasBlocks(body));
    assertEquals("/echo {hello}", TemplateParser.render(body, null));
  }

  @Test
  public void anUnclosedBlockIsLiteral() {
    String body = "/ai {入力:内容";
    assertFalse(TemplateParser.hasBlocks(body));
    assertEquals(body, TemplateParser.render(body, null));
  }

  @Test
  public void describeShowsLabels() {
    assertEquals(
        "/coints [表/裏] [100/500/1000/all]",
        TemplateParser.describe("/coints {選択:表|裏} {選択+:100|500|1000|all}"));
  }

  @Test
  public void dotPrefixIsAddedWhenOn() {
    assertEquals(".こんにちは", TemplateParser.applyDotPrefix("こんにちは", true));
    assertEquals("こんにちは", TemplateParser.applyDotPrefix("こんにちは", false));
  }

  @Test
  public void dotPrefixIsNotDoubled() {
    assertEquals(".tenki", TemplateParser.applyDotPrefix(".tenki", true));
    assertEquals("  .tenki", TemplateParser.applyDotPrefix("  .tenki", true));
  }

  @Test
  public void dotPrefixSkipsEmptyText() {
    assertEquals("", TemplateParser.applyDotPrefix("", true));
    assertEquals("   ", TemplateParser.applyDotPrefix("   ", true));
    assertEquals("", TemplateParser.applyDotPrefix(null, true));
  }

  @Test
  public void dotPrefixKeepsLeadingSpaceBeforeTheDot() {
    assertEquals("  .hello", TemplateParser.applyDotPrefix("  hello", true));
  }

  @Test
  public void aTemplateBodyParsesIntoAUsableEntry() {
    List<String> bodies =
        Arrays.asList(
            "おつかれさまです",
            "{入力:名前}さん、こんにちは",
            "{選択:表|裏} でいきます",
            "{選択+:100|500|1000|all} 円でお願いします",
            "{入力:件名}\n{入力:本文}",
            "{選択:了解|確認します} {選択+:今日|明日}");
    for (String body : bodies) {
      QuickEntry entry = QuickEntry.of(body);
      assertFalse(entry.body.isEmpty());
      assertFalse(entry.displayTitle().isEmpty());
      assertEquals(body, TemplateParser.render(body, emptyValues(entry.blocks().size())));
    }
  }

  @Test
  public void aTitledEntryShowsItsTitleAndKeepsItsBody() {
    QuickEntry entry = new QuickEntry("あいさつ", "{入力:名前}さん、こんにちは");
    assertEquals("あいさつ", entry.displayTitle());
    assertEquals(1, entry.blocks().size());
    assertEquals("[名前]さん、こんにちは", entry.describe());
  }

  @Test
  public void anUntitledEntryFallsBackToItsFirstLine() {
    QuickEntry entry = QuickEntry.of("1行目\n2行目");
    assertEquals("1行目", entry.displayTitle());
  }

  private static List<String> emptyValues(int count) {
    String[] values = new String[count];
    Arrays.fill(values, "");
    return Arrays.asList(values);
  }
}
