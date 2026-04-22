package com.example.logolsp.builtins;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of LOGO built-in primitives, loaded from the {@code builtins.json} resource.
 *
 * <p>The registry backs three LSP features:
 * <ul>
 *   <li><b>Parser arity.</b> {@link #lookup(String)} tells the parser how many arguments
 *       a built-in consumes, so commands and function calls split correctly.</li>
 *   <li><b>Hover.</b> Each {@link Builtin} carries a short doc string.</li>
 *   <li><b>Completion.</b> {@link #all()} enumerates primitives for completion proposals.</li>
 * </ul>
 *
 * <p>Name lookup is <em>case-insensitive</em> per ADR-009. Aliases (e.g. {@code FD} for
 * {@code FORWARD}) resolve to the same {@link Builtin} instance.
 *
 * <p>Scope is deliberately narrow: only primitives observable in the Turtle Academy
 * dialect (ADR-010). Adding a primitive means editing {@code builtins.json} only.
 */
public final class LogoBuiltins {

    /** A single built-in primitive. */
    public record Builtin(String canonicalName, List<String> aliases, int arity, String doc) {
        public Builtin {
            Objects.requireNonNull(canonicalName, "canonicalName");
            Objects.requireNonNull(aliases, "aliases");
            Objects.requireNonNull(doc, "doc");
            if (arity < 0) {
                throw new IllegalArgumentException("arity must be non-negative: " + arity);
            }
            aliases = List.copyOf(aliases);
        }

        /** Canonical name plus every alias, all in upper case. */
        public List<String> allNames() {
            List<String> all = new ArrayList<>(aliases.size() + 1);
            all.add(canonicalName);
            all.addAll(aliases);
            return Collections.unmodifiableList(all);
        }
    }

    private final Map<String, Builtin> byName;

    private LogoBuiltins(Map<String, Builtin> byName) {
        this.byName = byName;
    }

    /** Loads the default registry from {@code classpath:/builtins.json}. */
    public static LogoBuiltins loadDefault() {
        String resource = "/builtins.json";
        try (InputStream in = LogoBuiltins.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("classpath resource not found: " + resource);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return loadFromJson(json);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + resource, e);
        }
    }

    /** Parses a JSON string into a registry. Exposed for tests. */
    public static LogoBuiltins loadFromJson(String json) {
        Type dtoListType = new TypeToken<List<BuiltinDto>>() {}.getType();
        List<BuiltinDto> dtos = new Gson().fromJson(json, dtoListType);
        Map<String, Builtin> byName = new HashMap<>();
        for (BuiltinDto dto : dtos) {
            List<String> aliases = dto.aliases == null
                    ? List.of()
                    : dto.aliases.stream().map(a -> a.toUpperCase(Locale.ROOT)).toList();
            Builtin b = new Builtin(
                    dto.name.toUpperCase(Locale.ROOT),
                    aliases,
                    dto.arity,
                    dto.doc);
            for (String n : b.allNames()) {
                Builtin prev = byName.put(n, b);
                if (prev != null && prev != b) {
                    throw new IllegalStateException("duplicate builtin name: " + n);
                }
            }
        }
        return new LogoBuiltins(Map.copyOf(byName));
    }

    /**
     * Looks up a builtin by any of its names (canonical or alias), case-insensitively.
     * Returns {@link Optional#empty()} for unknown names or {@code null} input.
     */
    public Optional<Builtin> lookup(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(byName.get(name.toUpperCase(Locale.ROOT)));
    }

    /** Every distinct {@link Builtin}, order is undefined. */
    public List<Builtin> all() {
        return byName.values().stream().distinct().toList();
    }

    /** DTO used only for Gson deserialization. */
    @SuppressWarnings("unused")
    private static final class BuiltinDto {
        String name;
        List<String> aliases;
        int arity;
        String doc;
    }
}
