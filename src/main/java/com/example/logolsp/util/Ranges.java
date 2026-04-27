package com.example.logolsp.util;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * LSP {@link Range} helpers.
 *
 * <p>LSP range bounds are half-open: {@code [start, end)}. A position whose character
 * equals {@link Range#getEnd()}.{@code character} on the end line is <em>after</em> the
 * range, not inside it. The Eclipse LSP spec is explicit about this; honouring it is
 * what makes click-at-end-of-token resolve to the next token rather than the previous
 * one.
 */
public final class Ranges {

    private Ranges() {}

    /** Returns true iff {@code pos} lies in the half-open range {@code [start, end)}. */
    public static boolean contains(Range range, Position pos) {
        if (range == null || pos == null) return false;
        int pl = pos.getLine();
        int pc = pos.getCharacter();
        Position s = range.getStart();
        Position e = range.getEnd();
        if (pl < s.getLine() || pl > e.getLine()) return false;
        if (pl == s.getLine() && pc < s.getCharacter()) return false;
        if (pl == e.getLine() && pc >= e.getCharacter()) return false;
        return true;
    }
}
