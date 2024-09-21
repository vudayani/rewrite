/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.yaml.format;

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.Tree;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.style.IndentsStyle;
import org.openrewrite.yaml.tree.Yaml;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;

public class IndentsVisitor<P> extends YamlIsoVisitor<P> {
    private final IndentsStyle style;

    @Nullable
    private final Tree stopAfter;

    public IndentsVisitor(IndentsStyle style, @Nullable Tree stopAfter) {
        this.style = style;
        this.stopAfter = stopAfter;
    }

    @Override
    public @Nullable Yaml visit(@Nullable Tree tree, P p, Cursor parent) {
        setCursor(parent);
        for (Cursor c = parent; c != null && c.getValue() instanceof Yaml; c = c.getParent()) {
            Yaml y = c.getValue();
            String prefix = y.getPrefix();

            if (prefix.contains("\n")) {
                int indent = findIndent(prefix);
                if (indent != 0) {
                    c.putMessage("lastIndent", indent);
                }
            }
        }
        Iterator<Object> path = parent.getPath(Yaml.class::isInstance);
        if (path.hasNext()) {
            preVisit((Yaml) path.next(), p);
        }
        return visit(tree, p);
    }

    @Override
    public @Nullable Yaml preVisit(Yaml tree, P p) {
        if (getCursor().getNearestMessage("stop", false)) {
            return tree;
        }

        Yaml y = tree;
        int indent = getCursor().getNearestMessage("lastIndent", 0);
        if (y.getPrefix().contains("\n") && !isUnindentedTopLevel()) {
            if (y instanceof Yaml.Sequence.Entry) {
                indent = getCursor().getParentOrThrow().getMessage("sequenceEntryIndent", indent);

                y = y.withPrefix(indentTo(y.getPrefix(), indent + style.getIndentSize()));

                getCursor().getParentOrThrow().putMessage("sequenceEntryIndent", indent);
                // the +1 is for the '-' character
                getCursor().getParentOrThrow().putMessage("lastIndent",
                        indent + firstIndent(((Yaml.Sequence.Entry) y).getBlock()).length() + 1);
            } else if (y instanceof Yaml.Mapping.Entry) {
                y = y.withPrefix(indentTo(y.getPrefix(), indent + style.getIndentSize()));
                getCursor().putMessage("lastIndent", indent + style.getIndentSize());
            } else if (y instanceof Yaml.Document) {
                y = y.withPrefix(indentComments(y.getPrefix(), 0));
            }
        } else if (y instanceof Yaml.Mapping.Entry) {
            if (getCursor().getParentOrThrow(2).getValue() instanceof Yaml.Sequence.Entry) {
                // this is a mapping entry that begins a sequence entry and anything below it should be indented further to the right now, e.g.:
                //
                // - key:
                //     value
                getCursor().putMessage("lastIndent", indent + style.getIndentSize());
            } else {
                y = y.withPrefix(indentComments(y.getPrefix(), indent));
            }
        }
        return y;
    }

    private boolean isUnindentedTopLevel() {
        return getCursor().getParentOrThrow().getValue() instanceof Yaml.Document ||
                getCursor().getParentOrThrow(2).getValue() instanceof Yaml.Document;
    }

    @Override
    public @Nullable Yaml postVisit(Yaml tree, P p) {
        if (stopAfter != null && stopAfter.isScope(tree)) {
            getCursor().putMessageOnFirstEnclosing(Yaml.Documents.class, "stop", true);
        }
        return super.postVisit(tree, p);
    }

    @Override
    public @Nullable Yaml visit(@Nullable Tree tree, P p) {
        if (getCursor().getNearestMessage("stop") != null) {
            return (Yaml) tree;
        }
        return super.visit(tree, p);
    }

    private String indentTo(String prefix, int column) {
        if (!prefix.contains("\n")) {
            return prefix;
        }

        int indent = findIndent(prefix);
        prefix = indentComments(prefix, indent);
        if (indent != column) {
            int shift = column - indent;
            prefix = indent(prefix, shift);
        }

        return prefix;
    }

    private String indentComments(String prefix, int indent) {
        // If the prefix contains a newline followed by a comment ensure the comment begins at the indentation column
        if (prefix.contains("#")) {
            String reindentedComments = prefix.replaceAll("\n\\s*#", "\n" + StringUtils.repeat(" ", indent) + "#");
            // If a document begins with a comment it might not have a newline before it
            if (getCursor().getValue() instanceof Yaml.Document) {
                reindentedComments = prefix.replaceFirst("^\\s*#", "#");
            }
            if (!reindentedComments.equals(prefix)) {
                prefix = reindentedComments;
            }
        }
        return prefix;
    }

    private String indent(String whitespace, int shift) {
        StringBuilder newWhitespace = new StringBuilder(whitespace);
        shift(newWhitespace, shift);
        return newWhitespace.toString();
    }

    private void shift(StringBuilder text, int shift) {
        if (shift > 0) {
            for (int i = 0; i < shift; i++) {
                text.append(' ');
            }
        } else {
            text.delete(text.length() + shift, text.length());
        }
    }

    private int findIndent(String prefix) {
        int size = 0;
        for (char c : prefix.toCharArray()) {
            size++;
            if (c == '\n' || c == '\r') {
                size = 0;
            }
        }
        return size;
    }

    private String firstIndent(Yaml yaml) {
        AtomicReference<@Nullable String> indent = new AtomicReference<>();

        new YamlIsoVisitor<AtomicReference<String>>() {
            @Override
            public @Nullable Yaml visit(@Nullable Tree tree, AtomicReference<String> indent) {
                Yaml y = (Yaml) tree;
                //noinspection ConstantValue
                if (indent.get() != null) {
                    return y;
                }

                if (y != null && y != yaml && !(y instanceof Yaml.Mapping) && !(y instanceof Yaml.Sequence.Entry)) {
                    indent.set(y.getPrefix());
                    return y;
                }
                return super.visit(tree, indent);
            }
        }.visit(yaml, indent);

        String indentStr = indent.get();
        return indentStr == null ? "" : indentStr;
    }
}
