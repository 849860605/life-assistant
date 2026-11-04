package com.lifeassistant.core.weather;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;

/** 选天气数据源：配了和风用和风，否则用高德。 */
@Service
public class WeatherService {

    private final List<WeatherProvider> providers;

    public WeatherService(QWeatherProvider qweather, AmapWeatherProvider amap) {
        this.providers = List.of(qweather, amap);
    }

    /** 第一个可用的数据源；都没配置时抛 BizException。 */
    public WeatherProvider current() {
        return providers.stream().filter(WeatherProvider::available).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.EXTERNAL_SERVICE,
                        "天气没有可用的来源：在后台\"第三方平台\"里配置或启用和风天气、高德"));
    }
}
