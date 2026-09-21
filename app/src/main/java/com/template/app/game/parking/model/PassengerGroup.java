package com.template.app.game.parking.model;

/**
 * 一组同色乘客。
 * <p>
 * {@link #count} 是<b>可变</b>的：引擎接客时会扣减队首组的剩余人数，
 * 归零后整组出队。这是刻意的设计——若做成不可变，引擎每接一次客就要重建队列。
 */
public final class PassengerGroup {

    /** 颜色下标，与 {@link Vehicle#colorIndex} 同域。 */
    public final int colorIndex;

    /** 本组剩余人数。 */
    public int count;

    public PassengerGroup(int colorIndex, int count) {
        this.colorIndex = colorIndex;
        this.count = count;
    }

    public PassengerGroup copy() {
        return new PassengerGroup(colorIndex, count);
    }
}
