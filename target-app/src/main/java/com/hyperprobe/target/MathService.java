package com.hyperprobe.target;

import org.springframework.stereotype.Service;

@Service
public class MathService {

    private final AdditionEngine addition;
    private final SubtractionEngine subtraction;
    private final MultiplicationEngine multiplication;
    private final DivisionEngine division;

    public MathService(AdditionEngine addition,
                       SubtractionEngine subtraction,
                       MultiplicationEngine multiplication,
                       DivisionEngine division) {
        this.addition = addition;
        this.subtraction = subtraction;
        this.multiplication = multiplication;
        this.division = division;
    }

    public double compute(String op, double a, double b) {
        double result;
        switch (op) {
            case "add" -> result = addition.add(a, b);
            case "sub" -> result = subtraction.subtract(a, b);
            case "mul" -> result = multiplication.multiply(a, b);
            case "div" -> result = division.divide(a, b);
            default -> throw new IllegalArgumentException("Unknown op: " + op);
        }
        return result;
    }
}
