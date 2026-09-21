import com.template.app.game.parking.engine.ParkingConfig;
import com.template.app.game.parking.engine.ParkingEngine;
import com.template.app.game.parking.engine.ParkingLevelGenerator;
import com.template.app.game.parking.model.Level;
import com.template.app.game.parking.model.Vehicle;

public final class Diag {
    public static void main(String[] args) {
        ParkingLevelGenerator g = new ParkingLevelGenerator(20260921L);
        System.out.println("关卡 | 平均生成ms | 最坏ms | 平均车数 | 平均乘客 | 失败回退数");
        for (int lvl = 1; lvl <= 8; lvl++) {
            int n = 25;
            long sum = 0, worst = 0;
            int veh = 0, pas = 0, fallback = 0;
            for (int i = 0; i < n; i++) {
                long t0 = System.nanoTime();
                Level level = g.generate(lvl);
                long dt = (System.nanoTime() - t0) / 1_000_000;
                sum += dt; if (dt > worst) worst = dt;
                if (level.vehicles.size() <= 2) fallback++;
                veh += level.vehicles.size();
                pas += level.totalPassengers;
            }
            System.out.println("L" + lvl + "   | " + (sum / n)
                + "        | " + worst + "   | " + (veh / n)
                + "      | " + (pas / n) + "     | " + fallback);
        }
    }
}
