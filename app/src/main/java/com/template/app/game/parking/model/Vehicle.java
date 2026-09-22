package com.template.app.game.parking.model;

/**
 * 一辆车（可变实体）。
 * <p>
 * 本作<b>没有灰色障碍车</b>：停车场里每一辆彩色的车都要接走自己的乘客
 * （对应参考截图——满场都是彩色的车，箭头四个方向都有）。
 * 一辆车的静态属性在关卡生成时确定，运行期只有 {@link #row}、{@link #col}、{@link #place} 会变。
 */
public final class Vehicle {

    /** 停在停车场内。 */
    public static final int PLACE_LOT = 0;

    /** 已驶入接客区等客（占着一个车位）。 */
    public static final int PLACE_PICKUP = 1;

    /** 已驶离：接客成功，或被「移除」道具清走。 */
    public static final int PLACE_GONE = 2;

    public final int id;

    /** 车身占用的格数：2 = 小汽车，3 = 巴士。 */
    public final int length;

    /** true = 横向（占一行的连续多列）。 */
    public final boolean horizontal;

    /** 唯一的行驶方向，决定它从哪条边界驶出。 */
    public final Direction direction;

    /** 颜色下标，与乘客队列的颜色同域。 */
    public final int colorIndex;

    /** 车身最上（横向时）或最左（竖直时）所在格的行号。 */
    public int row;

    /** 车身最左所在格的列号。 */
    public int col;

    /** 当前所在区域：{@link #PLACE_LOT} / {@link #PLACE_PICKUP} / {@link #PLACE_GONE}。 */
    public int place = PLACE_LOT;

    /**
     * 已上车的乘客数。
     * <p>
     * 规则：车辆<b>必须满载</b>（{@code loaded == }{@link #capacity()}）才能驶离接客区；
     * 没装满就必须停在接客区继续等客。
     */
    public int loaded;

    public Vehicle(int id, int length, boolean horizontal, Direction direction,
                   int colorIndex, int row, int col) {
        this.id = id;
        this.length = length;
        this.horizontal = horizontal;
        this.direction = direction;
        this.colorIndex = colorIndex;
        this.row = row;
        this.col = col;
    }

    private Vehicle(Vehicle source) {
        this.id = source.id;
        this.length = source.length;
        this.horizontal = source.horizontal;
        this.direction = source.direction;
        this.colorIndex = source.colorIndex;
        this.row = source.row;
        this.col = source.col;
        this.place = source.place;
        this.loaded = source.loaded;
    }

    /** 载客量 = 车身长度：小汽车 2 座，巴士 3 座。 */
    public int capacity() {
        return length;
    }

    /** 还能上几位乘客。 */
    public int remainingCapacity() {
        return capacity() - loaded;
    }

    /** 是否已满载——只有满载的车才允许驶离接客区。 */
    public boolean isFull() {
        return loaded >= capacity();
    }

    /** 是否仍停在停车场内。 */
    public boolean inLot() {
        return place == PLACE_LOT;
    }

    /** 是否停在接客区等客。 */
    public boolean inPickup() {
        return place == PLACE_PICKUP;
    }

    /** (row, col) 是否被本车占用。 */
    public boolean covers(int row, int col) {
        if (horizontal) {
            return this.row == row && col >= this.col && col < this.col + length;
        }
        return this.col == col && row >= this.row && row < this.row + length;
    }

    /** 值拷贝。撤销快照与关卡实例化都依赖它是深拷贝而非共享引用。 */
    public Vehicle copy() {
        return new Vehicle(this);
    }
}
