package com.lifeassistant.core.weather;

import java.util.List;

/**
 * 天气数据源。和风天气为主，没配和风时用高德兜底，选择逻辑在 {@link WeatherService}。
 */
public interface WeatherProvider {

    /** 数据源名称和局限，返回给模型，让它知道哪些数据没有 */
    String name();

    /** 是否已配置好、可以调用 */
    boolean available();

    /** 把地名（如"泰山""杭州西湖"）或 "经度,纬度" 解析成位置 */
    WeatherLocation resolve(String query);

    /** 逐日预报，包含日出日落 */
    List<DailyForecast> daily(WeatherLocation location, int days);

    /** 未来 24 小时逐小时预报 */
    List<HourlyForecast> hourly(WeatherLocation location);

    /** 当前生效的天气预警 */
    List<WeatherWarning> warnings(WeatherLocation location);

    /** 解析后的位置：id 用于后续查询（和风的 LocationID，坐标输入时是 "经度,纬度"），adm 是一级行政区。 */
    record WeatherLocation(String id, String name, String adm, double lon, double lat) {
    }

    /** 一天的预报；precipMm 是降水量（毫米），sunrise/sunset 为 HH:mm。 */
    record DailyForecast(String date, String textDay, String textNight, int tempMin, int tempMax,
                         String windScaleDay, double precipMm, String uvIndex, String sunrise, String sunset) {
    }

    /** 一小时的预报；pop 是降水概率（%）。 */
    record HourlyForecast(String time, String text, int temp, String windScale, int pop, double precipMm) {
    }

    /** 一条天气预警；level 是预警颜色或等级。 */
    record WeatherWarning(String title, String level, String type, String text) {
    }
}
