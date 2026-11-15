package com.portfolio.deployops.domain;

public enum BuildToolType {
    NONE,
    MAVEN,
    GRADLE,
    NPM,
    STATIC,
    /** A single-file C program built with gcc inside the CI image. */
    C
}
