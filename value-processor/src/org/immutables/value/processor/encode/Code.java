package org.immutables.value.processor.encode;

import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

final class Code {
  private Code() {}

  private static final CharMatcher DELIMITER = CharMatcher.anyOf("!\"#$%&'()*+,-./:;<=>?@[]^_{|}~");
  private static final CharMatcher WHITESPACE = CharMatcher.WHITESPACE;
  private static final CharMatcher LETTER_OR_DIGIT = CharMatcher.javaLetterOrDigit().or(CharMatcher.anyOf("$_"));
  private static final CharMatcher IDENTIFIER_START = CharMatcher.javaLetter().or(CharMatcher.anyOf("$_"));

  static class Interpolator implements Function<List<Term>, List<Term>> {
    private final Function<String, String> members;
    private final Function<String, String> tops;
    private final String name;

    Interpolator(String name, Function<String, String> members, Function<String, String> tops) {
      this.name = name;
      this.members = members;
      this.tops = tops;
    }

    @Override
    public List<Term> apply(List<Term> terms) {
      List<Term> result = new ArrayList<>(terms.size());
      for (Term t : terms) {
        if (t.isBinding()) {
          result.add(dereference((Binding) t));
        } else if (t.isString()) {
          result.add(((Other) t).replace("<name>", name));
        } else {
          result.add(t);
        }
      }
      return result;
    }

    Term dereference(Binding binding) {
      String identifier = binding.identifier();
      String value = binding.isTop()
          ? tops.apply(identifier)
          : members.apply(identifier);
      if (value == null) {
        // if no substitution, falling back to the identifier itself
        value = identifier;
      }
      return new WordOrNumber(value);
    }
  }

  static class Binder {
    private final Map<String, String> imports;
    private final Set<String> members;
    private final Set<String> tops;

    Binder(Map<String, String> imports, Set<String> members, Set<String> tops) {
      this.imports = imports;
      this.members = members;
      this.tops = tops;
    }

    List<Term> apply(List<Term> terms) {
      List<Term> result = new ArrayList<>();
      try {
        resolve(terms.iterator(), result, false);
      } catch (Exception ex) {
        throw new RuntimeException("Cannot bind: " + Code.join(terms), ex);
      }
      return result;
    }

    private void resolve(Iterator<Term> it, List<Term> result, boolean untilGenericsClose) {
      State state = State.NONE;
      while (it.hasNext()) {
        Term t = it.next();
        // On matching close we return to
        if (untilGenericsClose && t.is(">")) {
          result.add(t);
          return;
        }
        // this is to handle generics when invoking methods like
        // ImmutableList.<String>builder()
        // we don't want 'builder' to be considered to identifier
        if ((state == State.DOT || untilGenericsClose) && t.is("<")) {
          result.add(t);
          resolve(it, result, true);
          continue;
        }
        state = state.next(t);
        if (state.isTopIdent()) {
          String identifier = t.toString();
          if (state == State.TOP_IDENT && tops.contains(identifier)) {
            result.add(Binding.newTop(identifier));
          } else if (members.contains(identifier)) {
            result.add(Binding.newMember(identifier));
          } else if (imports.containsKey(identifier)) {
            String qualifiedName = imports.get(identifier);
            result.addAll(termsFrom(qualifiedName));
          } else {
            result.add(t);
          }
        } else {
          result.add(t);
        }
      }
    }

    List<Term> parameterAsThis(List<Term> result, String param) {
      Term thisSubstitute = Binding.newTop("this");
      WordOrNumber thisToken = new WordOrNumber("this");

      ListIterator<Term> it = result.listIterator();
      while (it.hasNext()) {
        Term t = it.next();
        if (t.is("this")) {
          it.set(thisSubstitute);
        } else if (t.isBinding()) {
          Binding b = (Binding) t;
          if (b.isTop() && b.identifier().equals(param)) {
            it.set(thisToken);
          }
        }
      }

      // rebind using new this
      result = apply(result);

      // put "this" back
      it = result.listIterator();
      while (it.hasNext()) {
        Term t = it.next();
        if (t == thisToken) {
          it.set(Binding.newTop(param));
        } else if (t == thisSubstitute) {
          it.set(thisToken);
        }
      }
      return result;
    }

    enum State {
      NONE,
      DOT,
      THIS,
      THIS_DOT,
      TOP_IDENT,
      THIS_TOP_IDENT;

      boolean isTopIdent() {
        return this == TOP_IDENT
            || this == THIS_TOP_IDENT;
      }

      State next(Term t) {
        boolean isDot = t.isDelimiter() && t.is(".");
        boolean isThis = t.isWordOrNumber() && t.is("this");

        boolean isIdent = t.isWordOrNumber()
            && !isThis
            && IDENTIFIER_START.matches(t.toString().charAt(0));

        if (t.isIgnorable()) {
          return this;
        } else if (isDot) {
          switch (this) {
          case THIS:
            return THIS_DOT;
          default:
            return DOT;
          }
        } else if (isThis) {
          return THIS;
        } else if (isIdent) {
          switch (this) {
          case THIS_DOT:
            return THIS_TOP_IDENT;
          case DOT:
            return NONE;
          default:
            return TOP_IDENT;
          }
        } else {
          return NONE;
        }
      }
    }
  }

  static String join(List<Term> terms) {
    return Joiner.on("").join(terms);
  }

  static List<Term> termsFrom(String input) {
    Scanner s = new Scanner(input);
    s.scan();
    return s.terms;
  }

  static class Scanner {
    final List<Term> terms = new ArrayList<>();
    private final String input;

    Scanner(String input) {
      this.input = input;
    }

    void scan() {
      int p = 0;
      char c;
      while ((c = get(p)) != '\0') {
        if (c == '/' && get(p + 1) == '/') {
          int end = slashSlashComment(p + 2);
          terms.add(new Other(get(p, p = end)));
        } else if (c == '/' && get(p + 1) == '*') {
          int end = slashStarComment(p + 2);
          terms.add(new Other(get(p, p = end)));
        } else if (c == '\'' || c == '"') {
          int end = quotedLiteral(p + 1, c);
          terms.add(new Other(get(p, p = end)));
        } else if (c == '@' && Binding.chars(c, get(p + 1))) {
          int end = whileMatches(p + 2, LETTER_OR_DIGIT);
          terms.add(new Binding(get(p, p = end)));
        } else if (DELIMITER.matches(c)) {
          terms.add(new Delimiter(get(p, ++p)));
        } else if (LETTER_OR_DIGIT.matches(c)) {
          int end = whileMatches(p + 1, LETTER_OR_DIGIT);
          terms.add(new WordOrNumber(get(p, p = end)));
        } else if (WHITESPACE.matches(c)) {
          int end = whileMatches(p + 1, WHITESPACE);
          terms.add(new Whitespace(get(p, p = end)));
        } else {
          terms.add(new Other(get(p, ++p)));
        }
      }
    }

    private int whileMatches(int p, CharMatcher matcher) {
      for (;; p++) {
        if (!matcher.matches(get(p))) {
          return p;
        }
      }
    }

    private int quotedLiteral(int p, char quote) {
      for (;;) {
        char c = get(p++);
        if (c == '\\') {
          // skip anything escaped, we are worry only to not meet \' or \",
          // so \u0000 are no concerns to use
          p++;
          continue;
        }
        if (c == quote) {
          return p++;
        }
      }
    }

    private int slashSlashComment(int p) {
      for (;;) {
        char c = get(p++);
        if (c == '\0' || c == '\n') {
          return p - 1;
        }
      }
    }

    private int slashStarComment(int p) {
      for (;;) {
        char c = get(p++);
        if (c == '\0') {
          return p;
        }
        if (c == '*' && get(p) == '/') {
          return p + 1;
        }
      }
    }

    private String get(int from, int to) {
      return input.substring(Math.max(0, from), Math.min(to, input.length()));
    }

    private char get(int i) {
      if (i < 0 || i >= input.length()) {
        return '\0';
      }
      return input.charAt(i);
    }
  }

  static List<Term> trimLeadingIndent(List<Term> code) {
    ArrayList<Term> result = new ArrayList<>(code);
    ListIterator<Term> it = result.listIterator();
    while (it.hasNext()) {
      Term t = it.next();
      if (t.isWhitespace()) {
        String whitespace = t.toString();
        int indexOf = whitespace.indexOf('\n');
        if (indexOf >= 0) {
          it.set(new Whitespace(whitespace.substring(0, indexOf + 1)));
        }
      }
    }
    return result;
  }

  static List<Term> replaceReturn(List<Term> code, String replacement) {
    ArrayList<Term> result = new ArrayList<>(code);
    ListIterator<Term> it = result.listIterator();
    boolean wasReturn = false;
    while (it.hasNext()) {
      Term t = it.next();
      if (t.isWordOrNumber() && t.is("return")) {
        it.set(new Other(replacement));
        wasReturn = true;
      }
    }
    if (!wasReturn) {
      ListIterator<Term> revIt = Lists.reverse(result).listIterator();
      if (nextNonBlankIs(revIt, "}")) {
        nextNonBlankIs(revIt, ";");
        revIt.previous();
        revIt.add(new Delimiter(";"));
        revIt.add(new Other(replacement));
        revIt.add(new Whitespace("\n"));
      }
    }
    return result;
  }

  static List<Term> oneLiner(List<Term> code) {
    if (!code.isEmpty()) {
      // TODO improve visual quality
      Iterator<Term> it = code.iterator();
      if (nextNonBlankIs(it, "{") && nextNonBlankIs(it, "return")) {
        List<Term> line = new ArrayList<>();
        while (it.hasNext()) {
          Term t = it.next();
          if (line.isEmpty() && t.isIgnorable()) {
            // skip leading whitespace
            continue;
          }
          line.add(t);
        }
        if (line.size() > 2) {
          // just to not bother with something awkward
          ListIterator<Term> revIt = Lists.reverse(line).listIterator();
          if (nextNonBlankIs(revIt, "}") && nextNonBlankIs(revIt, ";")) {
            // skip traling whitespace
            while (revIt.hasNext()) {
              Term t = revIt.next();
              if (t.isIgnorable()) {
                revIt.remove();
              } else {
                break;
              }
            }
            // step back
            if (revIt.hasPrevious()) {
              revIt.previous();
            }
            // remove everything after
            while (revIt.hasPrevious()) {
              revIt.previous();
              revIt.remove();
            }
            return ImmutableList.copyOf(line);
          }
        }
      }
    }
    return ImmutableList.of();
  }

  private static boolean nextNonBlankIs(Iterator<Term> terms, String string) {
    while (terms.hasNext()) {
      Term t = terms.next();
      if (!t.isIgnorable()) {
        return t.is(string);
      }
    }
    return false;
  }

  // TODO maybe change to CharSequence reference with start/end?
  static abstract class Term extends Eq<Term> implements CharSequence {
    private final String string;

    Term(String string) {
      super(string);
      this.string = string;
    }

    char charOf() {
      if (string.length() == 1) {
        return string.charAt(0);
      }
      throw new IllegalStateException("'" + this + "' term is not a single character");
    }

    boolean isWordOrNumber() {
      return false;
    }

    boolean isDelimiter() {
      return false;
    }

    boolean isIgnorable() {
      return isWhitespace() || isComment();
    }

    boolean isWhitespace() {
      return false;
    }

    boolean isBinding() {
      return false;
    }

    boolean isComment() {
      return false;
    }

    boolean isString() {
      return false;
    }

    boolean is(char c) {
      return length() == 1 && charAt(0) == c;
    }

    boolean is(String string) {
      return this.string.equals(string);
    }

    @Override
    public String toString() {
      return string;
    }

    @Override
    protected boolean eq(Term other) {
      return string.equals(other.string);
    }

    @Override
    public int length() {
      return string.length();
    }

    @Override
    public char charAt(int index) {
      return string.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return string.subSequence(start, end);
    }
  }

  static final class WordOrNumber extends Term {
    WordOrNumber(String string) {
      super(string);
    }

    @Override
    boolean isWordOrNumber() {
      return true;
    }
  }

  private static final class Delimiter extends Term {
    Delimiter(String value) {
      super(value);
    }

    @Override
    boolean isDelimiter() {
      return true;
    }
  }

  static final class Whitespace extends Term {
    Whitespace(String value) {
      super(value);
    }

    @Override
    boolean isWhitespace() {
      return true;
    }
  }

  private static final class Other extends Term {
    Other(String value) {
      super(value);
    }

    Other replace(String search, String replacement) {
      StringBuilder builder = new StringBuilder(this);
      int index = builder.indexOf(search);
      if (index < 0) {
        return this;
      }
      builder.replace(index, index + search.length(), replacement);
      return new Other(builder.toString());
    }

    @Override
    boolean isComment() {
      return charAt(0) == '/';
    }

    @Override
    boolean isString() {
      return charAt(0) == '"';
    }
  }

  static final class Binding extends Term {
    Binding(String string) {
      super(string);
    }

    String identifier() {
      return toString().substring(2);
    }

    boolean isTop() {
      return charAt(1) == '^';
    }

    @Override
    boolean isBinding() {
      return true;
    }

    static Binding newMember(String member) {
      return new Binding("@@" + member);
    }

    static Binding newTop(String top) {
      return new Binding("@^" + top);
    }

    static boolean chars(char c0, char c1) {
      return c0 == '@' && (c1 == '@' || c1 == '^');
    }
  }
}
