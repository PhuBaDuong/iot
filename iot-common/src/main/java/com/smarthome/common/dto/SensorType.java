package com.smarthome.common.dto;

/**
 * Types of sensors supported in our IoT system.
 */
public enum SensorType {
    TEMPERATURE("temperature", "°C", -40.0, 85.0),
    HUMIDITY("humidity", "%", 0.0, 100.0),
    MOTION("motion", "boolean", 0.0, 1.0),
    LIGHT("light", "lux", 0.0, 100000.0),
    PRESSURE("pressure", "hPa", 300.0, 1100.0);

    private final String name;
    private final String unit;
    private final double minValue;
    private final double maxValue;

    SensorType(String name, String unit, double minValue, double maxValue) {
        this.name = name;
        this.unit = unit;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    public String getName() {
        return name;
    }

    public String getUnit() {
        return unit;
    }

    public double getMinValue() {
        return minValue;
    }

    public double getMaxValue() {
        return maxValue;
    }

    /**
     * Check if a value is within the valid range for this sensor type.
     */
    public boolean isValidValue(double value) {
        return value >= minValue && value <= maxValue;
    }
}

