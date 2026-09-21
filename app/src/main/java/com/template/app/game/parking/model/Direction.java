package com.template.app.game.parking.model;

/**
 * 车辆的行驶方向。
 * <p>
 * 一辆车<b>只能</b>沿这个方向直线行驶；反方向的位移只允许由玩家拖动产生
 * （GDD 机制 3：反向拖动合法，是华容道必需的自由度）。
 */
public enum Direction {

    UP(-1, 0),
    DOWN(1, 0),
    LEFT(0, -1),
    RIGHT(0, 1);

    /** 每前进一格时行号的变化。 */
    public final int dRow;

    /** 每前进一格时列号的变化。 */
    public final int dCol;

    Direction(int dRow, int dCol) {
        this.dRow = dRow;
        this.dCol = dCol;
    }

    /** 该方向是否为竖直方向（决定车身轴向）。 */
    public boolean isVertical() {
        return dCol == 0;
    }

    /** 沿该方向前进一格时，锚点（row * 列数 + col）的变化量。 */
    public int anchorDelta(int columns) {
        return dRow * columns + dCol;
    }

    /** 绘制箭头时相对「朝上」的顺时针旋转角度。 */
    public float arrowDegrees() {
        switch (this) {
            case UP:
                return 0f;
            case RIGHT:
                return 90f;
            case DOWN:
                return 180f;
            default:
                return 270f;
        }
    }
}
