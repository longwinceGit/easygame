package com.template.app.game.parking.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个关卡的不可变描述。
 * <p>
 * <b>为什么必须不可变</b>（ADR-007）：关卡在<b>后台线程</b>生成，在主线程被套进引擎。
 * 可变对象会让这条边界失去保护。
 * <p>
 * {@link #vehicles} 里的是<b>原型</b>：引擎会 {@link Vehicle#copy()} 一份来用，
 * 因此同一个 Level 可以被重复实例化（「刷新」就是重新套用新 Level）。
 */
public final class Level {

    /** 关卡号，从 1 开始。 */
    public final int levelIndex;

    /** 车辆原型，下标即 {@link Vehicle#id}。 */
    public final List<Vehicle> vehicles;

    /** 乘客队列，index 0 是队首。 */
    public final List<PassengerGroup> queue;

    /** 本关乘客总数 = 所有客用车容量之和（守恒量，GDD 机制 5）。 */
    public final int totalPassengers;

    public Level(int levelIndex, List<Vehicle> vehicles, List<PassengerGroup> queue) {
        this.levelIndex = levelIndex;
        List<Vehicle> vehicleCopy = new ArrayList<>(vehicles.size());
        for (Vehicle v : vehicles) {
            vehicleCopy.add(v.copy());
        }
        this.vehicles = Collections.unmodifiableList(vehicleCopy);

        List<PassengerGroup> queueCopy = new ArrayList<>(queue.size());
        int total = 0;
        for (PassengerGroup group : queue) {
            PassengerGroup copy = group.copy();
            total += copy.count;
            queueCopy.add(copy);
        }
        this.queue = Collections.unmodifiableList(queueCopy);
        this.totalPassengers = total;
    }
}
