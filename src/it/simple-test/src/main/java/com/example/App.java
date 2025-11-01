package com.example;

import org.apache.commons.lang3.StringUtils;

public class App {
    public static void main(String[] args) {
        // This call should be detected by the ref-count plugin
        StringUtils.isEmpty("test");
    }
}
