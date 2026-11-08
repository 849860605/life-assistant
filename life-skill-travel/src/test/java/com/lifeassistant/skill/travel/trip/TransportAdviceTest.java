package com.lifeassistant.skill.travel.trip;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 两地直线距离的算法（推荐交通方式按它分档）。 */
class TransportAdviceTest {

    @Test
    void distanceBetweenCities() {
        double[] shenzhen = {114.06, 22.54};
        double[] hongkong = {114.17, 22.32};
        double[] changsha = {112.94, 28.23};
        double[] beijing = {116.40, 39.90};
        assertThat(TransportAdvice.distanceKm(shenzhen, hongkong)).isBetween(20.0, 35.0);
        assertThat(TransportAdvice.distanceKm(shenzhen, changsha)).isBetween(600.0, 660.0);
        assertThat(TransportAdvice.distanceKm(shenzhen, beijing)).isGreaterThan(1900.0);
    }
}
