package com.hyperprobe.target;

import org.springframework.stereotype.Component;

@Component
public class MultiplicationEngine {

    public double multiply(double a, double b) {
        double result = a * b;
        return result;
    }
}
