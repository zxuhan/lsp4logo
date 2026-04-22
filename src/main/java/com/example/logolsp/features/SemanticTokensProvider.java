package com.example.logolsp.features;

import com.example.logolsp.analysis.Scope;
import com.example.logolsp.analysis.Symbol;
import com.example.logolsp.analysis.SymbolTable;
import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.document.ParsedDocument;
import com.example.logolsp.lexer.Token;
import com.example.logolsp.lexer.TokenType;
import com.example.logolsp.parser.ast.Ast.BinaryOp;
import com.example.logolsp.parser.ast.Ast.ColonVar;
import com.example.logolsp.parser.ast.Ast.Command;
import com.example.logolsp.parser.ast.Ast.Expression;
import com.example.logolsp.parser.ast.Ast.FunctionCall;
import com.example.logolsp.parser.ast.Ast.ListLit;
import com.example.logolsp.parser.ast.Ast.NumberLit;
import com.example.logolsp.parser.ast.Ast.ParenExpr;
import com.example.logolsp.parser.ast.Ast.ProcedureDef;
import com.example.logolsp.parser.ast.Ast.Program;
import com.example.logolsp.parser.ast.Ast.QuoteWord;
import com.example.logolsp.parser.ast.Ast.Statement;
import com.example.logolsp.parser.ast.Ast.TopLevel;
import com.example.logolsp.parser.ast.Ast.UnaryOp;
import com.example.logolsp.parser.ast.Ast.WordRef;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Computes LSP semantic tokens for syntax highlighting.
 *
 * <p>Classification is driven by the AST + symbol table (not raw regex) — a
 * {@link TokenType#WORD} might be a keyword, a built-in call, a user procedure call,
 * or a recursion into the enclosing procedure, and only semantic analysis can tell
 * the difference.
 *
 * <p>ADR-011 records the decision to use LSP semantic tokens rather than a TextMate
 * grammar as the primary highlighting mechanism.
 *
 * <h2>Legend</h2>
 * Token types: {@code keyword, function, parameter, variable, string, number, comment,
 * operator}.<br>
 * Modifiers: {@code declaration, defaultLibrary}.
 */
public final class SemanticTokensProvider {

    /** Language keywords (case-insensitive). */
    private static final Set<String> KEYWORDS = Set.of(
            "TO", "END", "IF", "IFELSE", "REPEAT", "MAKE", "LOCAL", "OUTPUT", "STOP");

    public static final List<String> TOKEN_TYPES = List.of(
            "keyword",    // 0
            "function",   // 1
            "parameter",  // 2
            "variable",   // 3
            "string",     // 4
            "number",     // 5
            "comment",    // 6
            "operator");  // 7

    public static final List<String> TOKEN_MODIFIERS = List.of(
            "declaration",      // bit 0
            "defaultLibrary");  // bit 1

    private static final int T_KEYWORD   = 0;
    private static final int T_FUNCTION  = 1;
    private static final int T_PARAMETER = 2;
    private static final int T_VARIABLE  = 3;
    private static final int T_STRING    = 4;
    private static final int T_NUMBER    = 5;
    private static final int T_COMMENT   = 6;
    private static final int T_OPERATOR  = 7;

    private static final int MOD_DECLARATION     = 1;
    private static final int MOD_DEFAULT_LIBRARY = 2;

    private SemanticTokensProvider() {}

    /** Legend of token types and modifiers this provider emits. */
    public static SemanticTokensLegend legend() {
        return new SemanticTokensLegend(TOKEN_TYPES, TOKEN_MODIFIERS);
    }

    /** Computes the semantic tokens for the given document. */
    public static SemanticTokens compute(ParsedDocument doc, LogoBuiltins builtins) {
        List<SemToken> collected = new ArrayList<>();
        collectComments(doc.tokens(), collected);
        collectFromAst(doc.program(), doc.symbolTable(), builtins, collected);
        collected.sort(Comparator
                .comparingInt(SemToken::line)
                .thenComparingInt(SemToken::startChar));
        return new SemanticTokens(deltaEncode(collected));
    }

    // --- collection --------------------------------------------------------------

    private static void collectComments(List<Token> tokens, List<SemToken> out) {
        for (Token t : tokens) {
            if (t.type() == TokenType.COMMENT) {
                SemToken st = fromToken(t, T_COMMENT, 0);
                if (st != null) out.add(st);
            }
        }
    }

    private static void collectFromAst(Program program, SymbolTable table,
                                       LogoBuiltins builtins, List<SemToken> out) {
        for (TopLevel item : program.items()) {
            if (item instanceof ProcedureDef def) {
                addToken(out, def.toKeyword(), T_KEYWORD, 0);
                if (!def.nameToken().lexeme().isEmpty()) {
                    addToken(out, def.nameToken(), T_FUNCTION, MOD_DECLARATION);
                }
                for (Token p : def.parameterTokens()) {
                    addToken(out, p, T_PARAMETER, MOD_DECLARATION);
                }
                for (Statement s : def.body()) {
                    collectStatement(s, def, table, builtins, out);
                }
                if (def.endKeyword() != null) {
                    addToken(out, def.endKeyword(), T_KEYWORD, 0);
                }
            } else if (item instanceof Statement stmt) {
                collectStatement(stmt, null, table, builtins, out);
            }
        }
    }

    private static void collectStatement(Statement s, ProcedureDef enclosing,
                                         SymbolTable table, LogoBuiltins builtins,
                                         List<SemToken> out) {
        if (!(s instanceof Command cmd)) return;
        classifyCallHead(cmd.head(), builtins, out);
        for (Expression arg : cmd.arguments()) {
            collectExpression(arg, enclosing, table, builtins, out);
        }
    }

    private static void collectExpression(Expression e, ProcedureDef enclosing,
                                          SymbolTable table, LogoBuiltins builtins,
                                          List<SemToken> out) {
        if (e instanceof NumberLit n) {
            addToken(out, n.token(), T_NUMBER, 0);
        } else if (e instanceof ColonVar cv) {
            int typeIdx = T_VARIABLE;
            Scope scope = enclosing != null ? table.scopeOf(enclosing) : table.global();
            String name = stripSigil(cv.token().lexeme());
            var sym = scope.resolve(name);
            if (sym.isPresent() && sym.get().kind() == Symbol.Kind.PARAMETER) {
                typeIdx = T_PARAMETER;
            }
            addToken(out, cv.token(), typeIdx, 0);
        } else if (e instanceof QuoteWord qw) {
            addToken(out, qw.token(), T_STRING, 0);
        } else if (e instanceof WordRef wr) {
            classifyCallHead(wr.token(), builtins, out);
        } else if (e instanceof FunctionCall fc) {
            classifyCallHead(fc.head(), builtins, out);
            for (Expression a : fc.arguments()) {
                collectExpression(a, enclosing, table, builtins, out);
            }
        } else if (e instanceof BinaryOp bo) {
            collectExpression(bo.left(), enclosing, table, builtins, out);
            addToken(out, bo.operator(), T_OPERATOR, 0);
            collectExpression(bo.right(), enclosing, table, builtins, out);
        } else if (e instanceof UnaryOp uo) {
            addToken(out, uo.operator(), T_OPERATOR, 0);
            collectExpression(uo.operand(), enclosing, table, builtins, out);
        } else if (e instanceof ParenExpr pe) {
            collectExpression(pe.inner(), enclosing, table, builtins, out);
        } else if (e instanceof ListLit list) {
            for (Expression el : list.elements()) {
                collectExpression(el, enclosing, table, builtins, out);
            }
        }
        // No classification for structural tokens ([ ], ( )) — clients colour them
        // as punctuation.
    }

    private static void classifyCallHead(Token head, LogoBuiltins builtins, List<SemToken> out) {
        if (head.lexeme().isEmpty()) return;
        String upper = head.lexeme().toUpperCase(Locale.ROOT);
        if (KEYWORDS.contains(upper)) {
            addToken(out, head, T_KEYWORD, 0);
        } else if (builtins.lookup(head.lexeme()).isPresent()) {
            addToken(out, head, T_FUNCTION, MOD_DEFAULT_LIBRARY);
        } else {
            addToken(out, head, T_FUNCTION, 0);
        }
    }

    // --- encoding ----------------------------------------------------------------

    private static List<Integer> deltaEncode(List<SemToken> sorted) {
        List<Integer> data = new ArrayList<>(sorted.size() * 5);
        int prevLine = 0, prevChar = 0;
        for (SemToken t : sorted) {
            int deltaLine = t.line() - prevLine;
            int deltaChar = (deltaLine == 0) ? t.startChar() - prevChar : t.startChar();
            data.add(deltaLine);
            data.add(deltaChar);
            data.add(t.length());
            data.add(t.typeIdx());
            data.add(t.modifiers());
            prevLine = t.line();
            prevChar = t.startChar();
        }
        return data;
    }

    // --- helpers -----------------------------------------------------------------

    private static void addToken(List<SemToken> out, Token t, int typeIdx, int modifiers) {
        SemToken st = fromToken(t, typeIdx, modifiers);
        if (st != null) out.add(st);
    }

    /** Returns {@code null} if the token spans multiple lines (LSP disallows that). */
    private static SemToken fromToken(Token token, int typeIdx, int modifiers) {
        Range r = token.range();
        Position s = r.getStart();
        Position e = r.getEnd();
        if (s.getLine() != e.getLine()) return null;
        int length = e.getCharacter() - s.getCharacter();
        if (length <= 0) return null;
        return new SemToken(s.getLine(), s.getCharacter(), length, typeIdx, modifiers);
    }

    private static String stripSigil(String lexeme) {
        if (lexeme.isEmpty()) return lexeme;
        char c = lexeme.charAt(0);
        return (c == ':' || c == '"') ? lexeme.substring(1) : lexeme;
    }

    private record SemToken(int line, int startChar, int length, int typeIdx, int modifiers) {}
}
