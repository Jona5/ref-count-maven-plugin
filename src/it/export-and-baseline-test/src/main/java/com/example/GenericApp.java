package com.example;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

public class GenericApp {
    // Only referenced via generic type argument (erased in descriptor, visible in signature!)
    private List<StringUtils> helpers;

    public List<StringUtils> getHelpers() {
        return helpers;
    }
}
