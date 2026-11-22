package app.zipper.knot.ui;

import java.util.List;

/**
 * Where the "commands" and "templates" buttons go inside LINE's {@code +} panel.
 *
 * <p>The panel is LINE's own grass of icon cells, so its geometry is read from the cells that are
 * already on screen: the lowest row decides whether the extra row fits under it, and otherwise the
 * panel's last rows have to be given room to scroll clear of it. Only the arithmetic lives here so
 * it can be checked without a device.
 */
public final class PanelPlacement {

  /** A position for the strip, in the same coordinates as the cells. */
  public static final class Spot {
    public final int x;
    public final int y;

    /** Pixels to add below the panel's list so the strip never sits on top of a cell. */
    public final int bottomPadding;

    Spot(int x, int y, int bottomPadding) {
      this.x = x;
      this.y = y;
      this.bottomPadding = bottomPadding;
    }
  }

  private PanelPlacement() {}

  /** Top edge of the lowest row of cells (an icon and its caption share one row). */
  public static int lastRowTop(List<int[]> cells, int tolerance) {
    int top = Integer.MIN_VALUE;
    for (int[] cell : cells) {
      if (cell == null || cell.length < 4) continue;
      if (cell[1] > top) top = cell[1];
    }
    return top == Integer.MIN_VALUE ? 0 : top;
  }

  /** Lowest edge of the lowest row of cells. */
  public static int lastRowBottom(List<int[]> cells, int tolerance) {
    int top = lastRowTop(cells, tolerance);
    int bottom = 0;
    for (int[] cell : cells) {
      if (cell == null || cell.length < 4) continue;
      if (cell[1] >= top - tolerance && cell[3] > bottom) bottom = cell[3];
    }
    return bottom;
  }

  /** Left edge of the leftmost cell of the lowest row. */
  public static int lastRowLeft(List<int[]> cells, int tolerance) {
    int top = lastRowTop(cells, tolerance);
    int left = Integer.MAX_VALUE;
    for (int[] cell : cells) {
      if (cell == null || cell.length < 4) continue;
      if (cell[1] >= top - tolerance && cell[0] < left) left = cell[0];
    }
    return left == Integer.MAX_VALUE ? 0 : left;
  }

  /**
   * Works out the strip's place: right under the lowest row when that space is free, otherwise on
   * the panel's bottom edge with enough padding below the list that the last row can be scrolled
   * out from under it.
   *
   * @param panelLeft left edge of the panel
   * @param panelBottom bottom edge of the panel
   * @param cells the panel's item cells, each {left, top, right, bottom}
   * @param stripHeight height of the strip that is being placed
   * @param gap space kept between the strip and what is around it
   * @param tolerance rows are grouped by their top edge within this many pixels
   */
  public static Spot place(
      int panelLeft, int panelBottom, List<int[]> cells, int stripHeight, int gap, int tolerance) {
    if (cells == null || cells.isEmpty()) {
      return new Spot(panelLeft + gap, panelBottom - stripHeight - gap / 2, 0);
    }
    int lastBottom = lastRowBottom(cells, tolerance);
    int left = lastRowLeft(cells, tolerance);
    int free = panelBottom - lastBottom;
    int x = (left > 0 ? left : panelLeft) + gap;
    if (free >= stripHeight + gap) return new Spot(x, lastBottom + gap, 0);
    int padding = stripHeight + gap - Math.max(free, 0);
    return new Spot(x, panelBottom - stripHeight - gap / 2, padding);
  }
}
