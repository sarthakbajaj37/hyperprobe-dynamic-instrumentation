package com.hyperprobe.target;

import org.springframework.stereotype.Component;

@Component
public class SubtractionEngine {

    public double subtract(double a, double b) {
        double result = a - b;
        return result;
    }
}
