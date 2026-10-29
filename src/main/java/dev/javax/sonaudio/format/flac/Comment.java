package dev.javax.sonaudio.format.flac;

import java.util.List;

public record Comment(String vendor, List<Field> fields) {}
