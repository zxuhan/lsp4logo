package com.example.logolsp.parser.ast;

import com.example.logolsp.lexer.Token;
import org.eclipse.lsp4j.Range;

import java.util.List;
import java.util.Objects;

/**
 * LOGO abstract syntax tree.
 *
 * <p>All node types are grouped in this single file because the sealed hierarchy is
 * small, tightly coupled, and usually read top-to-bottom. The outer class is uninstantiable
 * and acts purely as a namespace — consumers typically do {@code import ...Ast.*}.
 *
 * <h2>Shape</h2>
 * <pre>
 * AstNode ────┬── Program
 *             ├── TopLevel ──┬── ProcedureDef
 *             │              └── Statement ─── Command
 *             └── Expression ┬── NumberLit
 *                            ├── ColonVar
 *                            ├── QuoteWord
 *                            ├── WordRef
 *                            ├── FunctionCall
 *                            ├── BinaryOp
 *                            ├── UnaryOp
 *                            ├── ListLit
 *                            └── ParenExpr
 * </pre>
 *
 * <p>Every node exposes a {@link Range} via {@link AstNode#range()}. Nodes wrapping a
 * single {@link Token} derive the range from that token; compound nodes carry it
 * explicitly.
 */
public final class Ast {

    private Ast() {}

    public sealed interface AstNode permits Program, TopLevel, Expression {
        Range range();
    }

    public record Program(List<TopLevel> items, Range range) implements AstNode {
        public Program {
            Objects.requireNonNull(items, "items");
            Objects.requireNonNull(range, "range");
            items = List.copyOf(items);
        }
    }

    public sealed interface TopLevel extends AstNode permits ProcedureDef, Statement {}

    /**
     * {@code TO <name> [:param]* ... END} procedure definition. {@code endKeyword} may be
     * {@code null} if parsing recovered from a missing {@code END} — callers interested
     * in range information should prefer {@link #range()}.
     */
    public record ProcedureDef(
            Token toKeyword,
            Token nameToken,
            List<Token> parameterTokens,
            List<Statement> body,
            Token endKeyword,
            Range range) implements TopLevel {
        public ProcedureDef {
            Objects.requireNonNull(toKeyword, "toKeyword");
            Objects.requireNonNull(nameToken, "nameToken");
            Objects.requireNonNull(parameterTokens, "parameterTokens");
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(range, "range");
            parameterTokens = List.copyOf(parameterTokens);
            body = List.copyOf(body);
        }
    }

    public sealed interface Statement extends TopLevel permits Command {}

    /** A procedure invocation at statement position: {@code head arg1 arg2 …}. */
    public record Command(Token head, List<Expression> arguments, Range range) implements Statement {
        public Command {
            Objects.requireNonNull(head, "head");
            Objects.requireNonNull(arguments, "arguments");
            Objects.requireNonNull(range, "range");
            arguments = List.copyOf(arguments);
        }
    }

    public sealed interface Expression extends AstNode
            permits NumberLit, ColonVar, QuoteWord, WordRef, FunctionCall,
                    BinaryOp, UnaryOp, ListLit, ParenExpr {}

    public record NumberLit(Token token) implements Expression {
        public NumberLit {
            Objects.requireNonNull(token, "token");
        }
        @Override public Range range() { return token.range(); }
    }

    public record ColonVar(Token token) implements Expression {
        public ColonVar {
            Objects.requireNonNull(token, "token");
        }
        @Override public Range range() { return token.range(); }
    }

    public record QuoteWord(Token token) implements Expression {
        public QuoteWord {
            Objects.requireNonNull(token, "token");
        }
        @Override public Range range() { return token.range(); }
    }

    /** A bare word used in an expression position with no resolvable arity. */
    public record WordRef(Token token) implements Expression {
        public WordRef {
            Objects.requireNonNull(token, "token");
        }
        @Override public Range range() { return token.range(); }
    }

    /** A procedure invocation at expression position: returns a value. */
    public record FunctionCall(Token head, List<Expression> arguments, Range range) implements Expression {
        public FunctionCall {
            Objects.requireNonNull(head, "head");
            Objects.requireNonNull(arguments, "arguments");
            Objects.requireNonNull(range, "range");
            arguments = List.copyOf(arguments);
        }
    }

    public record BinaryOp(Expression left, Token operator, Expression right, Range range) implements Expression {
        public BinaryOp {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(right, "right");
            Objects.requireNonNull(range, "range");
        }
    }

    public record UnaryOp(Token operator, Expression operand, Range range) implements Expression {
        public UnaryOp {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operand, "operand");
            Objects.requireNonNull(range, "range");
        }
    }

    /**
     * {@code [ elements… ]}. {@code closeBracket} is {@code null} when the closing
     * bracket was missing and parsing recovered.
     */
    public record ListLit(Token openBracket, List<Expression> elements, Token closeBracket, Range range) implements Expression {
        public ListLit {
            Objects.requireNonNull(openBracket, "openBracket");
            Objects.requireNonNull(elements, "elements");
            Objects.requireNonNull(range, "range");
            elements = List.copyOf(elements);
        }
    }

    /**
     * {@code ( inner )}. {@code closeParen} is {@code null} when the closing paren was
     * missing and parsing recovered.
     */
    public record ParenExpr(Token openParen, Expression inner, Token closeParen, Range range) implements Expression {
        public ParenExpr {
            Objects.requireNonNull(openParen, "openParen");
            Objects.requireNonNull(inner, "inner");
            Objects.requireNonNull(range, "range");
        }
    }
}
