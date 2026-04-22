package com.example.logolsp.parser;

import com.example.logolsp.builtins.LogoBuiltins;
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
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Hand-written recursive-descent parser for LOGO.
 *
 * <h2>Two-pass strategy</h2>
 * <ol>
 *   <li><b>Pass 1</b> ({@link #collectUserArities}) walks the token list and records
 *       every {@code TO <name> :<param>*} header into an arity table, so forward
 *       references to user-defined procedures resolve correctly (ADR-008).</li>
 *   <li><b>Pass 2</b> ({@link #parseProgram}) assembles the AST, consulting builtins +
 *       the arity table to decide how many arguments each command or function call
 *       consumes.</li>
 * </ol>
 *
 * <h2>Error recovery</h2>
 * The parser is total: it never throws. On an unexpected token it emits an LSP
 * {@link Diagnostic} and synchronises on the nearest {@code NEWLINE}, {@code END}, or
 * statement boundary, so downstream features keep working on a best-effort AST.
 *
 * <h2>Keyword recognition</h2>
 * {@code TO} and {@code END} are recognised case-insensitively (ADR-009). All other
 * identifiers pass through as {@link com.example.logolsp.parser.ast.Ast.WordRef}s or
 * as command/function heads, with arity determined by the builtins registry + user
 * arity table.
 */
public final class LogoParser {

    private static final String DIAGNOSTIC_SOURCE = "logo-lsp";

    private final List<Token> tokens;
    private final LogoBuiltins builtins;
    private final Map<String, Integer> userArities = new HashMap<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int pos;

    public LogoParser(List<Token> tokens, LogoBuiltins builtins) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.builtins = Objects.requireNonNull(builtins, "builtins");
    }

    /**
     * Runs the two-pass parse.
     *
     * <p>Call this <strong>once</strong> per {@code LogoParser} instance — the parser
     * is stateful (it mutates an internal position pointer and diagnostics list).
     * Create a fresh instance for each new token stream.
     *
     * @return the program AST and any syntactic diagnostics collected during the parse
     */
    public ParseResult parse() {
        collectUserArities();
        Program program = parseProgram();
        return new ParseResult(program, diagnostics);
    }

    // --- pass 1 ------------------------------------------------------------------

    private void collectUserArities() {
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.type() != TokenType.WORD || !"TO".equalsIgnoreCase(t.lexeme())) continue;
            int j = skipInertAfter(i);
            if (j >= tokens.size() || tokens.get(j).type() != TokenType.WORD) continue;
            String name = tokens.get(j).lexeme().toUpperCase(Locale.ROOT);
            int arity = 0;
            j++;
            while (j < tokens.size()) {
                TokenType tt = tokens.get(j).type();
                if (tt == TokenType.COMMENT) { j++; continue; }
                if (tt != TokenType.COLON_VAR) break;
                arity++;
                j++;
            }
            userArities.putIfAbsent(name, arity);
        }
    }

    private int skipInertAfter(int from) {
        int j = from + 1;
        while (j < tokens.size() && tokens.get(j).type() == TokenType.COMMENT) j++;
        return j;
    }

    // --- pass 2: top-level -------------------------------------------------------

    private Program parseProgram() {
        List<TopLevel> items = new ArrayList<>();
        skipNewlines();
        while (!isAtEnd()) {
            TopLevel item = parseTopLevel();
            if (item != null) items.add(item);
            skipNewlines();
        }
        return new Program(items, programRange(items));
    }

    private Range programRange(List<TopLevel> items) {
        if (items.isEmpty()) {
            Position zero = new Position(0, 0);
            return new Range(zero, zero);
        }
        return new Range(items.get(0).range().getStart(),
                items.get(items.size() - 1).range().getEnd());
    }

    private TopLevel parseTopLevel() {
        Token t = peek();
        if (t.type() == TokenType.WORD && "TO".equalsIgnoreCase(t.lexeme())) {
            return parseProcedureDef();
        }
        return parseStatement();
    }

    private ProcedureDef parseProcedureDef() {
        Token toKw = advance();
        Token nameTok;
        if (peek().type() != TokenType.WORD) {
            emitError(peek().range(), "expected procedure name after TO");
            Position at = toKw.range().getEnd();
            nameTok = new Token(TokenType.WORD, "", new Range(at, at));
        } else {
            nameTok = advance();
        }
        List<Token> params = new ArrayList<>();
        while (peek().type() == TokenType.COLON_VAR) {
            params.add(advance());
        }
        // Tolerate trailing junk on the TO header line.
        while (!isAtEnd() && peek().type() != TokenType.NEWLINE && !isEndKeyword(peek())) {
            emitError(peek().range(), "unexpected token in TO header: " + peek().lexeme());
            advance();
        }
        skipNewlines();
        List<Statement> body = new ArrayList<>();
        while (!isAtEnd() && !isEndKeyword(peek())) {
            Statement s = parseStatement();
            if (s != null) body.add(s);
            skipNewlines();
        }
        Token endTok = null;
        if (isEndKeyword(peek())) {
            endTok = advance();
        } else {
            emitError(peek().range(), "expected END to close TO " + nameTok.lexeme());
        }
        Position start = toKw.range().getStart();
        Position end = endTok != null
                ? endTok.range().getEnd()
                : (body.isEmpty() ? nameTok.range().getEnd() : body.get(body.size() - 1).range().getEnd());
        return new ProcedureDef(toKw, nameTok, params, body, endTok, new Range(start, end));
    }

    private Statement parseStatement() {
        skipNewlines();
        if (isAtEnd()) return null;
        Token t = peek();
        if (isEndKeyword(t)) return null;
        if (t.type() == TokenType.WORD && "TO".equalsIgnoreCase(t.lexeme())) {
            emitError(t.range(), "nested TO is not allowed");
            advance();
            synchronizeToLineBreakOrEnd();
            return null;
        }
        return parseCommand();
    }

    private Command parseCommand() {
        Token head = peek();
        if (head.type() != TokenType.WORD) {
            emitError(head.range(), "expected command, got " + head.type());
            synchronizeToLineBreakOrEnd();
            return null;
        }
        advance();
        int arity = lookupArity(head.lexeme());
        List<Expression> args = new ArrayList<>();
        Range lastRange = head.range();
        if (arity < 0) {
            while (!isAtEnd() && !isStatementBoundary(peek())) {
                Expression e = parseExpression();
                if (e == null) break;
                args.add(e);
                lastRange = e.range();
            }
        } else {
            for (int i = 0; i < arity; i++) {
                if (isAtEnd() || isStatementBoundary(peek())) {
                    emitError(head.range(),
                            "too few arguments for " + head.lexeme()
                                    + " (expected " + arity + ", got " + i + ")");
                    break;
                }
                Expression e = parseExpression();
                if (e == null) break;
                args.add(e);
                lastRange = e.range();
            }
        }
        return new Command(head, args, new Range(head.range().getStart(), lastRange.getEnd()));
    }

    // --- expressions ---------------------------------------------------------------

    private Expression parseExpression() {
        return parseComparison();
    }

    private Expression parseComparison() {
        Expression left = parseAdditive();
        if (left == null) return null;
        while (isComparisonOp(peek().type())) {
            Token op = advance();
            Expression right = parseAdditive();
            if (right == null) {
                emitError(op.range(), "expected expression after " + op.lexeme());
                return left;
            }
            left = new BinaryOp(left, op, right, span(left.range(), right.range()));
        }
        return left;
    }

    private Expression parseAdditive() {
        Expression left = parseMultiplicative();
        if (left == null) return null;
        while (peek().type() == TokenType.PLUS || peek().type() == TokenType.MINUS) {
            Token op = advance();
            Expression right = parseMultiplicative();
            if (right == null) {
                emitError(op.range(), "expected expression after " + op.lexeme());
                return left;
            }
            left = new BinaryOp(left, op, right, span(left.range(), right.range()));
        }
        return left;
    }

    private Expression parseMultiplicative() {
        Expression left = parseUnary();
        if (left == null) return null;
        while (peek().type() == TokenType.STAR || peek().type() == TokenType.SLASH) {
            Token op = advance();
            Expression right = parseUnary();
            if (right == null) {
                emitError(op.range(), "expected expression after " + op.lexeme());
                return left;
            }
            left = new BinaryOp(left, op, right, span(left.range(), right.range()));
        }
        return left;
    }

    private Expression parseUnary() {
        if (peek().type() == TokenType.MINUS) {
            Token op = advance();
            Expression operand = parseUnary();
            if (operand == null) {
                emitError(op.range(), "expected expression after unary -");
                return null;
            }
            return new UnaryOp(op, operand, span(op.range(), operand.range()));
        }
        return parseAtom();
    }

    private Expression parseAtom() {
        Token t = peek();
        return switch (t.type()) {
            case NUMBER -> { advance(); yield new NumberLit(t); }
            case COLON_VAR -> { advance(); yield new ColonVar(t); }
            case QUOTE_WORD -> { advance(); yield new QuoteWord(t); }
            case WORD -> parseWordAtom();
            case LPAREN -> parseParenExpr();
            case LBRACKET -> parseListLit();
            default -> {
                emitError(t.range(), "expected expression, got " + t.type());
                yield null;
            }
        };
    }

    private Expression parseWordAtom() {
        Token word = advance();
        int arity = lookupArity(word.lexeme());
        if (arity <= 0) {
            return new WordRef(word);
        }
        List<Expression> args = new ArrayList<>();
        Range lastRange = word.range();
        for (int i = 0; i < arity; i++) {
            if (isAtEnd() || isStatementBoundary(peek())) {
                emitError(word.range(),
                        "too few arguments for " + word.lexeme()
                                + " (expected " + arity + ", got " + i + ")");
                break;
            }
            Expression e = parseExpression();
            if (e == null) break;
            args.add(e);
            lastRange = e.range();
        }
        return new FunctionCall(word, args, span(word.range(), lastRange));
    }

    private Expression parseParenExpr() {
        Token open = advance();
        Expression inner = parseExpression();
        if (inner == null) {
            emitError(open.range(), "expected expression inside parentheses");
            // best-effort recover: consume until matching )
            while (!isAtEnd() && peek().type() != TokenType.RPAREN
                    && peek().type() != TokenType.NEWLINE) {
                advance();
            }
        }
        Token close = null;
        if (peek().type() == TokenType.RPAREN) {
            close = advance();
        } else {
            emitError(peek().range(), "expected )");
        }
        Position end = close != null
                ? close.range().getEnd()
                : (inner != null ? inner.range().getEnd() : open.range().getEnd());
        return new ParenExpr(open, inner != null ? inner : new WordRef(open), close,
                new Range(open.range().getStart(), end));
    }

    private Expression parseListLit() {
        Token open = advance();
        List<Expression> elements = new ArrayList<>();
        while (!isAtEnd() && peek().type() != TokenType.RBRACKET) {
            if (peek().type() == TokenType.NEWLINE) {
                advance();
                continue;
            }
            int markerPos = pos;
            Expression e = parseExpression();
            if (e == null) {
                if (pos == markerPos && !isAtEnd()) advance(); // avoid infinite loop
                continue;
            }
            elements.add(e);
        }
        Token close = null;
        if (peek().type() == TokenType.RBRACKET) {
            close = advance();
        } else {
            emitError(open.range(), "expected ]");
        }
        Position end = close != null
                ? close.range().getEnd()
                : (elements.isEmpty() ? open.range().getEnd() : elements.get(elements.size() - 1).range().getEnd());
        return new ListLit(open, elements, close, new Range(open.range().getStart(), end));
    }

    // --- helpers -----------------------------------------------------------------

    private int lookupArity(String name) {
        var builtin = builtins.lookup(name);
        if (builtin.isPresent()) return builtin.get().arity();
        Integer a = userArities.get(name.toUpperCase(Locale.ROOT));
        return a != null ? a : -1;
    }

    private boolean isComparisonOp(TokenType t) {
        return t == TokenType.EQ || t == TokenType.LT || t == TokenType.GT
                || t == TokenType.LE || t == TokenType.GE || t == TokenType.NEQ;
    }

    private boolean isEndKeyword(Token t) {
        return t.type() == TokenType.WORD && "END".equalsIgnoreCase(t.lexeme());
    }

    private boolean isStatementBoundary(Token t) {
        return t.type() == TokenType.NEWLINE || t.type() == TokenType.RBRACKET
                || t.type() == TokenType.RPAREN || t.type() == TokenType.EOF
                || isEndKeyword(t);
    }

    private void skipNewlines() {
        while (peek().type() == TokenType.NEWLINE) {
            advance();
        }
    }

    private void synchronizeToLineBreakOrEnd() {
        while (!isAtEnd() && peek().type() != TokenType.NEWLINE && !isEndKeyword(peek())) {
            advance();
        }
    }

    private Token peek() {
        skipComments();
        if (pos >= tokens.size()) return lastToken();
        return tokens.get(pos);
    }

    private Token advance() {
        skipComments();
        if (pos >= tokens.size()) return lastToken();
        Token t = tokens.get(pos);
        if (t.type() != TokenType.EOF) pos++;
        return t;
    }

    private void skipComments() {
        while (pos < tokens.size() && tokens.get(pos).type() == TokenType.COMMENT) {
            pos++;
        }
    }

    private boolean isAtEnd() {
        return peek().type() == TokenType.EOF;
    }

    private Token lastToken() {
        if (!tokens.isEmpty()) return tokens.get(tokens.size() - 1);
        Position zero = new Position(0, 0);
        return new Token(TokenType.EOF, "", new Range(zero, zero));
    }

    private static Range span(Range first, Range last) {
        return new Range(first.getStart(), last.getEnd());
    }

    private void emitError(Range range, String message) {
        Diagnostic d = new Diagnostic(range, message);
        d.setSeverity(DiagnosticSeverity.Error);
        d.setSource(DIAGNOSTIC_SOURCE);
        diagnostics.add(d);
    }
}
