// ============================================================================
// Copyright (C) 2006-2016 Talend Inc. - www.talend.com
//
// This source code is available under agreement available at
// https://github.com/Talend/data-prep/blob/master/LICENSE
//
// You should have received a copy of the agreement
// along with this program; if not, write to Talend SA
// 9 rue Pages 92150 Suresnes, France
//
// ============================================================================

package org.talend.tql.bean;

import static java.lang.Double.parseDouble;
import static java.lang.String.valueOf;
import static java.util.Collections.singleton;
import static java.util.Optional.of;
import static java.util.stream.Stream.concat;
import static org.apache.commons.lang.StringUtils.equalsIgnoreCase;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.talend.daikon.pattern.character.CharPatternToRegex;
import org.talend.daikon.pattern.word.WordPatternToRegex;
import org.talend.tql.model.AllFields;
import org.talend.tql.model.AndExpression;
import org.talend.tql.model.ComparisonExpression;
import org.talend.tql.model.ComparisonOperator;
import org.talend.tql.model.Expression;
import org.talend.tql.model.FieldBetweenExpression;
import org.talend.tql.model.FieldCompliesPattern;
import org.talend.tql.model.FieldContainsExpression;
import org.talend.tql.model.FieldInExpression;
import org.talend.tql.model.FieldIsEmptyExpression;
import org.talend.tql.model.FieldIsInvalidExpression;
import org.talend.tql.model.FieldIsValidExpression;
import org.talend.tql.model.FieldMatchesRegex;
import org.talend.tql.model.FieldReference;
import org.talend.tql.model.FieldWordCompliesPattern;
import org.talend.tql.model.LiteralValue;
import org.talend.tql.model.NotExpression;
import org.talend.tql.model.OrExpression;
import org.talend.tql.model.TqlElement;
import org.talend.tql.visitor.IASTVisitor;

/**
 * A {@link IASTVisitor} implementation that generates a {@link Predicate predicate} that allows matching on a
 * <code>T</code> instance.
 * 
 * @param <T> The bean class.
 */
public class BeanPredicateVisitor<T> implements IASTVisitor<Predicate<T>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(BeanPredicateVisitor.class);

    private final Class<T> targetClass;

    private final Deque<String> literals = new ArrayDeque<>();

    private final Deque<Method[]> currentMethods = new ArrayDeque<>();

    public BeanPredicateVisitor(Class<T> targetClass) {
        this.targetClass = targetClass;
    }

    private static Object invoke(Object o, Method[] methods) {
        try {
            Object currentObject = o;
            for (Method method : methods) {
                currentObject = method.invoke(currentObject);
            }
            return currentObject;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to invoke methods on '" + o + "'.", e);
        }
    }

    /**
     * Test a string value against a pattern returned during value analysis.
     *
     * @param value A string value. May be null.
     * @param pattern A pattern as returned in value analysis.
     * @return <code>true</code> if value complies, <code>false</code> otherwise.
     */
    private static boolean complies(String value, String pattern) {
        return value != null && pattern != null && value.matches(CharPatternToRegex.toRegex(pattern));
    }

    private static boolean wordComplies(String value, String pattern) {
        return value != null && pattern != null && value.matches(WordPatternToRegex.toRegex(pattern, true));
    }

    private static <T> Predicate<T> unchecked(Predicate<T> predicate) {
        return of(predicate).map(Unchecked::new).orElseGet(() -> new Unchecked<>(o -> false));
    }

    @Override
    public Predicate<T> visit(TqlElement tqlElement) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Predicate<T> visit(ComparisonOperator comparisonOperator) {
        // No need to implement this (handled in ComparisonExpression).
        throw new UnsupportedOperationException();
    }

    @Override
    public Predicate<T> visit(LiteralValue literalValue) {
        literals.push(literalValue.getValue());
        return null;
    }

    @Override
    public Predicate<T> visit(FieldReference fieldReference) {
        currentMethods.push(getMethods(fieldReference));
        return null;
    }

    private Method[] getMethods(FieldReference fieldReference) {
        return getMethods(fieldReference.getPath());
    }

    private Method[] getMethods(String field) {
        StringTokenizer tokenizer = new StringTokenizer(field, ".");
        List<String> methodNames = new ArrayList<>();
        while (tokenizer.hasMoreTokens()) {
            methodNames.add(tokenizer.nextToken());
        }

        Class currentClass = targetClass;
        LinkedList<Method> methods = new LinkedList<>();
        for (String methodName : methodNames) {
            if ("_class".equals(methodName)) {
                try {
                    methods.add(Class.class.getMethod("getClass"));
                    methods.add(Class.class.getMethod("getName"));
                } catch (NoSuchMethodException e) {
                    throw new IllegalArgumentException("Unable to get methods for class' name.", e);
                }
            } else {
                String[] getterCandidates = new String[] { "get" + WordUtils.capitalize(methodName), //
                        methodName, //
                        "is" + WordUtils.capitalize(methodName) };

                final int beforeFind = methods.size();
                for (String getterCandidate : getterCandidates) {
                    try {
                        methods.add(currentClass.getMethod(getterCandidate));
                        break;
                    } catch (Exception e) {
                        LOGGER.debug("Can't find getter '{}'.", field, e);
                    }
                }
                if (beforeFind == methods.size()) {
                    throw new UnsupportedOperationException("Can't find getter '" + field + "'.");
                } else {
                    currentClass = methods.getLast().getReturnType();
                }
            }
        }
        return methods.toArray(new Method[0]);
    }

    @Override
    public Predicate<T> visit(Expression expression) {
        // Very generic method: prefer an unsupported exception iso. erratic behavior.
        throw new UnsupportedOperationException();
    }

    @Override
    public Predicate<T> visit(AndExpression andExpression) {
        final Expression[] expressions = andExpression.getExpressions();
        if (expressions.length > 0) {
            Predicate<T> predicate = expressions[0].accept(this);
            for (int i = 1; i < expressions.length; i++) {
                predicate = predicate.and(expressions[i].accept(this));
            }
            return predicate;
        } else {
            return m -> true;
        }
    }

    @Override
    public Predicate<T> visit(OrExpression orExpression) {
        final Expression[] expressions = orExpression.getExpressions();
        if (expressions.length > 0) {
            Predicate<T> predicate = expressions[0].accept(this);
            for (int i = 1; i < expressions.length; i++) {
                predicate = predicate.or(expressions[i].accept(this));
            }
            return predicate;
        } else {
            return m -> true;
        }
    }

    @Override
    public Predicate<T> visit(ComparisonExpression comparisonExpression) {
        comparisonExpression.getValueOrField().accept(this);
        final Object value = literals.pop();

        comparisonExpression.getField().accept(this);
        if (!currentMethods.isEmpty()) {
            Predicate<T> predicate = getComparisonPredicate(currentMethods.pop(), comparisonExpression, value);
            while (!currentMethods.isEmpty()) {
                predicate = predicate.or(getComparisonPredicate(currentMethods.pop(), comparisonExpression, value));
            }
            return predicate;
        } else {
            return o -> true;
        }
    }

    private Predicate<T> getComparisonPredicate(Method[] getters, ComparisonExpression comparisonExpression, Object value) {
        // Standard methods
        final ComparisonOperator operator = comparisonExpression.getOperator();
        switch (operator.getOperator()) {
        case EQ:
            return eq(value, getters);
        case LT:
            return lt(value, getters);
        case GT:
            return gt(value, getters);
        case NEQ:
            return neq(value, getters);
        case LET:
            return lte(value, getters);
        case GET:
            return gte(value, getters);
        default:
            throw new UnsupportedOperationException();
        }
    }

    private Predicate<T> neq(Object value, Method[] getters) {
        return unchecked( //
                o -> !ObjectUtils.equals(invoke(o, getters), value) //
        );
    }

    private Predicate<T> gt(Object value, Method[] getters) {
        return unchecked( //
                o -> parseDouble(valueOf(invoke(o, getters))) > parseDouble(valueOf(value)) //
        );
    }

    private Predicate<T> gte(Object value, Method[] getters) {
        return unchecked( //
                o -> parseDouble(valueOf(invoke(o, getters))) >= parseDouble(valueOf(value)) //
        );
    }

    private Predicate<T> lt(Object value, Method[] getters) {
        return unchecked( //
                o -> parseDouble(valueOf(invoke(o, getters))) < parseDouble(valueOf(value)) //
        );
    }

    private Predicate<T> lte(Object value, Method[] getters) {
        return unchecked( //
                o -> parseDouble(valueOf(invoke(o, getters))) <= parseDouble(valueOf(value)) //
        );
    }

    private Predicate<T> eq(Object value, Method[] getters) {
        return unchecked( //
                o -> equalsIgnoreCase(valueOf(invoke(o, getters)), valueOf(value)) //
        );
    }

    @Override
    public Predicate<T> visit(FieldInExpression fieldInExpression) {
        fieldInExpression.getField().accept(this);
        final Method[] methods = currentMethods.pop();

        final LiteralValue[] values = fieldInExpression.getValues();
        if (values.length > 0) {
            Predicate<T> predicate = eq(values[0].accept(this), methods);
            for (LiteralValue value : values) {
                value.accept(this);
                predicate = predicate.or(eq(literals.pop(), methods));
            }
            return predicate;
        } else {
            return m -> true;
        }
    }

    @Override
    public Predicate<T> visit(FieldIsEmptyExpression fieldIsEmptyExpression) {
        fieldIsEmptyExpression.getField().accept(this);
        final Method[] methods = currentMethods.pop();
        return unchecked(o -> StringUtils.isEmpty(valueOf(invoke(o, methods))));
    }

    @Override
    public Predicate<T> visit(FieldIsValidExpression fieldIsValidExpression) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Predicate<T> visit(FieldIsInvalidExpression fieldIsInvalidExpression) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Predicate<T> visit(FieldMatchesRegex fieldMatchesRegex) {
        fieldMatchesRegex.getField().accept(this);
        final Method[] methods = currentMethods.pop();

        final Pattern pattern = Pattern.compile(fieldMatchesRegex.getRegex());
        return unchecked(o -> pattern.matcher(valueOf(invoke(o, methods))).matches());
    }

    @Override
    public Predicate<T> visit(FieldCompliesPattern fieldCompliesPattern) {
        fieldCompliesPattern.getField().accept(this);
        final Method[] methods = currentMethods.pop();

        final String pattern = fieldCompliesPattern.getPattern();
        return unchecked(o -> complies(valueOf(invoke(o, methods)), pattern));
    }

    @Override
    public Predicate<T> visit(FieldWordCompliesPattern fieldWordCompliesPattern) {
        fieldWordCompliesPattern.getField().accept(this);
        final Method[] methods = currentMethods.pop();

        final String pattern = fieldWordCompliesPattern.getPattern();
        return unchecked(o -> wordComplies(valueOf(invoke(o, methods)), pattern));
    }

    @Override
    public Predicate<T> visit(FieldBetweenExpression fieldBetweenExpression) {
        fieldBetweenExpression.getField().accept(this);
        final Method[] methods = currentMethods.pop();

        fieldBetweenExpression.getLeft().accept(this);
        fieldBetweenExpression.getRight().accept(this);
        final String right = literals.pop();
        final String left = literals.pop();

        Predicate<T> predicate;
        if (fieldBetweenExpression.isLowerOpen()) {
            predicate = gt(left, methods);
        } else {
            predicate = gte(left, methods);
        }
        if (fieldBetweenExpression.isUpperOpen()) {
            predicate = predicate.and(lt(right, methods));
        } else {
            predicate = predicate.and(lte(right, methods));
        }
        return predicate;
    }

    @Override
    public Predicate<T> visit(NotExpression notExpression) {
        final Predicate<T> accept = notExpression.getExpression().accept(this);
        return accept.negate();
    }

    @Override
    public Predicate<T> visit(FieldContainsExpression fieldContainsExpression) {
        fieldContainsExpression.getField().accept(this);
        final Method[] methods = currentMethods.pop();

        return unchecked(o -> {
            String invokeResultString = valueOf(invoke(o, methods));
            String expressionValue = fieldContainsExpression.getValue();
            return fieldContainsExpression.isCaseSensitive() ? StringUtils.contains(invokeResultString, expressionValue)
                    : StringUtils.containsIgnoreCase(invokeResultString, expressionValue);
        });
    }

    @Override
    public Predicate<T> visit(AllFields allFields) {
        final Set<Class> initialClasses = new HashSet<>(singleton(targetClass));
        visitClassMethods(targetClass, initialClasses);

        return null;
    }

    private void visitClassMethods(Class targetClass, Set<Class> visitedClasses, Method... previous) {
        List<Method> previousMethods = Arrays.asList(previous);
        for (Method method : targetClass.getMethods()) {
            if (method.getName().startsWith("get") || method.getName().startsWith("is")) {
                final Method[] path = concat(previousMethods.stream(), Stream.of(method)).collect(Collectors.toList())
                        .toArray(new Method[0]);
                currentMethods.push(path);

                // Recursively get methods to nested classes (and prevent infinite recursions).
                final Class<?> returnType = method.getReturnType();
                if (!returnType.isPrimitive() && visitedClasses.add(returnType)) {
                    visitClassMethods(returnType, visitedClasses, path);
                }
            }
        }
    }

    private static class Unchecked<T> implements Predicate<T> {

        private Predicate<T> delegate;

        private Unchecked(Predicate<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean test(T t) {
            try {
                return delegate.test(t);
            } catch (Exception e) {
                LOGGER.error("Unable to evaluate.", e);
                return false;
            }
        }

        @Override
        public Predicate<T> and(Predicate<? super T> other) {
            return new Unchecked<>(delegate.and(other));
        }

        @Override
        public Predicate<T> negate() {
            return new Unchecked<>(delegate.negate());
        }

        @Override
        public Predicate<T> or(Predicate<? super T> other) {
            return new Unchecked<>(delegate.or(other));
        }
    }
}
