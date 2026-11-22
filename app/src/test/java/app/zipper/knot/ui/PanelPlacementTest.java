package app.zipper.knot.ui;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class PanelPlacementTest {

  private static int[] cell(int left, int top, int right, int bottom) {
    return new int[] {left, top, right, bottom};
  }

  // A 4-column panel: three rows of tiles (100/250/400, each 150 tall), the last row missing
  // its fourth tile — the layout of the screenshot this was built from
  private static List<int[]> elevenTiles() {
    List<int[]> cells = new ArrayList<>();
    for (int row = 0; row < 3; row++) {
      int columns = row == 2 ? 3 : 4;
      for (int column = 0; column < columns; column++) {
        int left = column * 270;
        int top = 100 + row * 150;
        cells.add(cell(left, top, left + 270, top + 150));
      }
    }
    return cells;
  }

  @Test
  public void lastRowIsTheLowestOne() {
    assertEquals(400, PanelPlacement.lastRowTop(elevenTiles(), 10));
    assertEquals(550, PanelPlacement.lastRowBottom(elevenTiles(), 10));
    assertEquals(0, PanelPlacement.lastRowLeft(elevenTiles(), 10));
  }

  @Test
  public void rowsAreGroupedByTopEdgeWithinTolerance() {
    List<int[]> cells = new ArrayList<>();
    cells.add(cell(0, 100, 100, 150));
    cells.add(cell(100, 106, 200, 158));
    assertEquals(158, PanelPlacement.lastRowBottom(cells, 10));
    assertEquals(0, PanelPlacement.lastRowLeft(cells, 10));
  }

  @Test
  public void stripGoesUnderTheLastRowWhenThereIsRoom() {
    // 150px of slack below the last row is enough for a 140px strip plus the gap
    PanelPlacement.Spot spot = PanelPlacement.place(0, 700, elevenTiles(), 140, 6, 10);
    assertEquals(556, spot.y);
    assertEquals(0, spot.bottomPadding);
    assertEquals(6, spot.x);
  }

  @Test
  public void stripMakesRoomWhenTheLastRowReachesThePanelBottom() {
    // 30px of slack is not enough: the list gets strip + gap - slack below it
    PanelPlacement.Spot spot = PanelPlacement.place(0, 580, elevenTiles(), 140, 6, 10);
    assertEquals(116, spot.bottomPadding);
    assertEquals(580 - 140 - 3, spot.y);
  }

  @Test
  public void exactFitStillGetsTheGapBetweenRowAndStrip() {
    // free space of exactly strip + gap leaves no padding to add
    PanelPlacement.Spot spot = PanelPlacement.place(0, 696, elevenTiles(), 140, 6, 10);
    assertEquals(0, spot.bottomPadding);
    assertEquals(556, spot.y);
  }

  @Test
  public void emptyPanelPutsTheStripAtItsBottom() {
    PanelPlacement.Spot spot = PanelPlacement.place(40, 400, new ArrayList<>(), 120, 6, 10);
    assertEquals(0, spot.bottomPadding);
    assertEquals(40 + 6, spot.x);
    assertEquals(400 - 120 - 3, spot.y);
  }
}
