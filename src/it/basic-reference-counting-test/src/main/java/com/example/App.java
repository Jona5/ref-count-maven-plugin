package com.example;

import org.apache.commons.lang3.StringUtils;

import java.util.function.Predicate;

public class App {
    public static void main(String[] args) {
        // Direct static method call (1 reference)
        StringUtils.isEmpty("test");
        // Method reference via invokedynamic (1 reference)
        Predicate<String> blankCheck = StringUtils::isBlank;
        blankCheck.test(" ");
    }
}
