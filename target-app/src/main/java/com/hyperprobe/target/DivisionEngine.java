package com.hyperprobe.target;

import org.springframework.stereotype.Component;

@Component
public class DivisionEngine {

    public double divide(double a, double b) {
        double result = a / b;
        return result;
    }
}
