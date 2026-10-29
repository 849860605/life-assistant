package com.lifeassistant.core.weather;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 按经纬度计算日出日落（日出方程，NOAA 简化算法），误差在 1–2 分钟内。
 * 天气接口没给日出日落时（高德）用它补上；不考虑海拔，山顶实际日出会稍早一点。
 */
public final class SunCalculator {

    /** J2000.0 历元的儒略日 */
    private static final double J2000 = 2451545.0;
    /** 1970-01-01 0 点的儒略日 */
    private static final double UNIX_EPOCH_JD = 2440587.5;
    /** 2000-01-01 的 epochDay */
    private static final long EPOCH_DAY_2000 = 10957;

    private SunCalculator() {
    }

    /** 日出和日落的本地时间。 */
    public record SunTimes(LocalTime sunrise, LocalTime sunset) {
    }

    /** 计算某天某地的日出日落；极昼极夜时返回空。lon 东经为正，lat 北纬为正。 */
    public static Optional<SunTimes> of(LocalDate date, double lat, double lon, ZoneId zone) {
        double n = date.toEpochDay() - EPOCH_DAY_2000;
        double meanSolarNoon = n - lon / 360.0;
        double m = normalize(357.5291 + 0.98560028 * meanSolarNoon);
        double mRad = Math.toRadians(m);
        double center = 1.9148 * Math.sin(mRad) + 0.0200 * Math.sin(2 * mRad) + 0.0003 * Math.sin(3 * mRad);
        double lambda = Math.toRadians(normalize(m + center + 180 + 102.9372));
        double transit = J2000 + meanSolarNoon + 0.0053 * Math.sin(mRad) - 0.0069 * Math.sin(2 * lambda);
        double sinDecl = Math.sin(lambda) * Math.sin(Math.toRadians(23.4397));
        double cosDecl = Math.cos(Math.asin(sinDecl));
        double latRad = Math.toRadians(lat);
        // -0.833° 是太阳视半径加大气折射，太阳上沿刚碰到地平线的高度
        double cosHourAngle = (Math.sin(Math.toRadians(-0.833)) - Math.sin(latRad) * sinDecl)
                / (Math.cos(latRad) * cosDecl);
        if (cosHourAngle < -1 || cosHourAngle > 1) {
            return Optional.empty();
        }
        double hourAngle = Math.toDegrees(Math.acos(cosHourAngle));
        return Optional.of(new SunTimes(
                toLocalTime(transit - hourAngle / 360.0, zone),
                toLocalTime(transit + hourAngle / 360.0, zone)));
    }

    /** 儒略日转成指定时区的本地时间，精确到分钟（四舍五入）。 */
    private static LocalTime toLocalTime(double julianDay, ZoneId zone) {
        long epochSecond = Math.round((julianDay - UNIX_EPOCH_JD) * 86400 / 60.0) * 60;
        return Instant.ofEpochSecond(epochSecond).atZone(zone).toLocalTime();
    }

    /** 角度归一到 [0, 360)。 */
    private static double normalize(double degrees) {
        double d = degrees % 360;
        return d < 0 ? d + 360 : d;
    }
}
