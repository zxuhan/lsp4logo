package com.example.logolsp.builtins;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogoBuiltinsTest {

    @Test
    void loadDefault_populates_the_known_primitives() {
        LogoBuiltins b = LogoBuiltins.loadDefault();
        assertThat(b.all()).isNotEmpty();
        assertThat(b.lookup("FORWARD")).isPresent();
        assertThat(b.lookup("FD")).isPresent();
        assertThat(b.lookup("REPEAT")).isPresent();
    }

    @Test
    void lookup_is_case_insensitive() {
        LogoBuiltins b = LogoBuiltins.loadDefault();
        assertThat(b.lookup("fd")).isEqualTo(b.lookup("FD"));
        assertThat(b.lookup("Fd")).isEqualTo(b.lookup("FORWARD"));
    }

    @Test
    void alias_and_canonical_resolve_to_the_same_builtin() {
        LogoBuiltins b = LogoBuiltins.loadDefault();
        Optional<LogoBuiltins.Builtin> fd = b.lookup("FD");
        Optional<LogoBuiltins.Builtin> forward = b.lookup("FORWARD");
        assertThat(fd).isPresent();
        assertThat(forward).isPresent();
        assertThat(fd.get()).isSameAs(forward.get());
        assertThat(fd.get().canonicalName()).isEqualTo("FORWARD");
        assertThat(fd.get().aliases()).containsExactly("FD");
        assertThat(fd.get().arity()).isEqualTo(1);
    }

    @Test
    void unknown_name_returns_empty() {
        LogoBuiltins b = LogoBuiltins.loadDefault();
        assertThat(b.lookup("NOTAREALNAME")).isEmpty();
        assertThat(b.lookup(null)).isEmpty();
    }

    @Test
    void all_returns_distinct_builtins() {
        LogoBuiltins b = LogoBuiltins.loadDefault();
        long distinctCanonicals = b.all().stream()
                .map(LogoBuiltins.Builtin::canonicalName)
                .distinct()
                .count();
        assertThat(distinctCanonicals).isEqualTo(b.all().size());
    }

    @Test
    void loadFromJson_rejects_negative_arity() {
        String bad = "[{\"name\":\"BAD\",\"arity\":-1,\"doc\":\"oops\"}]";
        assertThatThrownBy(() -> LogoBuiltins.loadFromJson(bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadFromJson_rejects_duplicate_names() {
        String dup = "["
                + "{\"name\":\"DUP\",\"arity\":0,\"doc\":\"a\"},"
                + "{\"name\":\"DUP\",\"arity\":0,\"doc\":\"b\"}"
                + "]";
        assertThatThrownBy(() -> LogoBuiltins.loadFromJson(dup))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }
}
