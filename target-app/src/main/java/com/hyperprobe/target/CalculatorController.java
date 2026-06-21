package com.hyperprobe.target;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class CalculatorController {

    private final MathService mathService;

    public CalculatorController(MathService mathService) {
        this.mathService = mathService;
    }

    @GetMapping("/calculate")
    public Map<String, Object> calculate(@RequestParam String op,
                                         @RequestParam double a,
                                         @RequestParam double b) {
        double result = mathService.compute(op, a, b);
        return Map.of("op", op, "a", a, "b", b, "result", result);
    }
}
