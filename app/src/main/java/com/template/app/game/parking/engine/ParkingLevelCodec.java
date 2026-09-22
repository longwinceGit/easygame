package com.template.app.game.parking.engine;

import com.template.app.game.parking.model.Direction;
import com.template.app.game.parking.model.Level;
import com.template.app.game.parking.model.PassengerGroup;
import com.template.app.game.parking.model.Vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * 关卡 ⇄ 紧凑字符串的编解码器（docs/13 第 2 步）。
 * <p>
 * 关卡要落库缓存，必须能无损序列化。这里只存<b>静态布局</b>
 * ——{@code row / col / length / horizontal / direction / color} 与乘客队列；
 * 运行期的 {@link Vehicle#place} 由引擎初始化，不进字符串。
 * <p>
 * 格式（刻意用纯文本，便于离线脚本生成与人工排查）：
 * <pre>
 * layout = "row,col,len,h,dir,color|row,col,len,h,dir,color|..."
 * queue  = "color:count;color:count;..."
 * </pre>
 * <b>纯 Java、零 {@code android.*} 依赖</b>（ADR-003）：离线预生成脚本（跑在 JVM 上）
 * 与 App 运行时共用同一份编解码，保证两端格式绝不会漂。
 */
public final class ParkingLevelCodec {

    private ParkingLevelCodec() {
        // 工具类禁止实例化
    }

    /** 车辆布局 → 字符串。下标即 {@link Vehicle#id}。 */
    public static String encodeLayout(List<Vehicle> vehicles) {
        StringBuilder sb = new StringBuilder(vehicles.size() * 12);
        for (int i = 0; i < vehicles.size(); i++) {
            Vehicle v = vehicles.get(i);
            if (i > 0) {
                sb.append('|');
            }
            sb.append(v.row).append(',')
                .append(v.col).append(',')
                .append(v.length).append(',')
                .append(v.horizontal ? 1 : 0).append(',')
                .append(dirCode(v.direction)).append(',')
                .append(v.colorIndex);
        }
        return sb.toString();
    }

    /** 乘客队列 → 字符串。 */
    public static String encodeQueue(List<PassengerGroup> queue) {
        StringBuilder sb = new StringBuilder(queue.size() * 4);
        for (int i = 0; i < queue.size(); i++) {
            PassengerGroup g = queue.get(i);
            if (i > 0) {
                sb.append(';');
            }
            sb.append(g.colorIndex).append(':').append(g.count);
        }
        return sb.toString();
    }

    /**
     * 反序列化为关卡。
     * <p>
     * <b>防御性</b>：缓存是可丢弃资产，任何格式错误或取值越界都返回 null，
     * 让调用方回退到在线生成——而不是把一个坏关卡塞进引擎。
     *
     * @return 关卡；数据损坏或越界时返回 null
     */
    public static Level decode(int levelIndex, String layout, String queue) {
        List<Vehicle> vehicles = decodeLayout(layout);
        List<PassengerGroup> groups = decodeQueue(queue);
        if (vehicles == null || groups == null || vehicles.isEmpty() || groups.isEmpty()) {
            return null;
        }
        return new Level(levelIndex, vehicles, groups);
    }

    private static List<Vehicle> decodeLayout(String layout) {
        if (layout == null || layout.isEmpty()) {
            return null;
        }
        String[] tokens = layout.split("\\|");
        List<Vehicle> out = new ArrayList<>(tokens.length);
        for (int i = 0; i < tokens.length; i++) {
            String[] f = tokens[i].split(",");
            if (f.length != 6) {
                return null;
            }
            try {
                int row = Integer.parseInt(f[0]);
                int col = Integer.parseInt(f[1]);
                int length = Integer.parseInt(f[2]);
                boolean horizontal = Integer.parseInt(f[3]) != 0;
                Direction direction = dirOf(Integer.parseInt(f[4]));
                int color = Integer.parseInt(f[5]);
                if (!validPlacement(row, col, length, horizontal) || !validColor(color)) {
                    return null;
                }
                out.add(new Vehicle(i, length, horizontal, direction, color, row, col));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    private static List<PassengerGroup> decodeQueue(String queue) {
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        String[] tokens = queue.split(";");
        List<PassengerGroup> out = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            String[] f = token.split(":");
            if (f.length != 2) {
                return null;
            }
            try {
                int color = Integer.parseInt(f[0]);
                int count = Integer.parseInt(f[1]);
                if (!validColor(color) || count <= 0) {
                    return null;
                }
                out.add(new PassengerGroup(color, count));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /** 车身必须完整落在棋盘内，且与生成器 {@code placeVehicle} 的约束一致。 */
    private static boolean validPlacement(int row, int col, int length, boolean horizontal) {
        if (row < 0 || col < 0 || length < 2 || length > 3) {
            return false;
        }
        if (horizontal) {
            return row < ParkingConfig.ROWS && col + length <= ParkingConfig.COLUMNS;
        }
        return col < ParkingConfig.COLUMNS && row + length <= ParkingConfig.ROWS;
    }

    private static boolean validColor(int colorIndex) {
        return colorIndex >= 0 && colorIndex < ParkingConfig.COLOR_COUNT;
    }

    private static int dirCode(Direction direction) {
        switch (direction) {
            case UP:
                return 0;
            case DOWN:
                return 1;
            case LEFT:
                return 2;
            default:
                return 3;
        }
    }

    private static Direction dirOf(int code) {
        switch (code) {
            case 0:
                return Direction.UP;
            case 1:
                return Direction.DOWN;
            case 2:
                return Direction.LEFT;
            default:
                return Direction.RIGHT;
        }
    }
}
