package com.hyperprobe.target;

import org.springframework.stereotype.Component;

@Component
public class AdditionEngine {

    public double add(double a, double b) {
        double result = a + b;
        return result;
    }
}
