# DOMATAR JAVA CODE STYLE GUIDE

For use by LLMs generating or modifying Java code.

This document is the source of truth for Java in this tree. Follow every
rule here; do not invent alternatives. Naming and comment conventions are
those of the existing codebase.

## PART 1 — FILES

1.1  Encoding: UTF-8.
1.2  Line endings: CRLF (Windows, \r\n).
1.3  Every file ends with a single newline.
1.4  The first non-blank line of every source file (after a copyright header, if
     present) is the package declaration, then a blank line, then imports.
1.5  Copyright header when one already exists in the file being edited:
       /*
        * Copyright (c) 2024 Domatar
        */
     Do not add a header to files that do not already have one.

## PART 2 — INDENTATION

2.1  Indent with 2 SPACES. Never use tabs.
2.2  Tab size is also 2 spaces (relevant to IDE alignment only; use spaces in
     the file).
2.3  Continuation lines (wrapped expressions) are indented to align with the
     opening delimiter or the first non-whitespace token of the logical line,
     as described in the multi-line alignment rules (PART 6). Never use a flat
     double-indent for continuations.

## PART 3 — BRACES  (Allman / "next line" style throughout)

3.1  Opening braces go on the NEXT LINE for all constructs: class bodies,
     method/constructor bodies, if/else/for/while/do/try/catch/finally blocks,
     enum bodies, switch bodies, lambdas (unless the body is a single expression
     with no braces; see 3.5).

     Correct:
       public void foo(int x)
       {
         if (x > 0)
         {
           doSomething();
         }
       }

     Wrong:
       public void foo(int x) {
         if (x > 0) {
           doSomething();
       }   }

3.2  Closing braces go on their own line, at the same indentation level as the
     opening statement.

3.3  else, catch, and finally each begin on a NEW LINE after the closing brace
     of the preceding block:

       if (cond)
       {
         a();
       }
       else
       {
         b();
       }

       try
       {
         risky();
       }
       catch (DomatarException e)
       {
         handle(e);
       }
       finally
       {
         cleanup();
       }

3.4  do-while:
       do
       {
         step();
       }
       while (condition);

3.5  Single-statement bodies: braces are optional when a control statement
     (if, for, while) has exactly one statement in its body. In that case,
     put the statement on the NEXT LINE, indented, with NO braces:

       if (x == null)
         return null;

       for (int i = 0; i < n; i++)
         sum += i;

     Never put the statement on the same line as the control keyword.
     As soon as the body grows to two or more statements, add Allman braces.

3.6  Lambdas with a single expression body need no braces and may stay inline:
       Runnable r = () -> doIt();
     When the lambda body needs braces, apply the Allman rule:
       Comparator<String> c = (a, b) ->
       {
         return a.compareTo(b);
       };

## PART 4 — LINE LENGTH

4.1  Hard right margin: 240 characters. No line may exceed this.
4.2  Soft margins: 80 and 140 characters. These are visual guides. Aim to keep
     code within 140; avoid exceeding 80 only in strings and identifiers where
     breaking would harm readability.
4.3  When a line would exceed the soft margin, break it according to the
     alignment rules in PART 6.

## PART 5 — BLANK LINES

5.1  One blank line between class-level members (fields, constructors, methods,
     inner classes, static blocks).
5.2  No blank line between a field declaration and the next field declaration
     unless the fields are in logically distinct groups; in that case use one
     blank line.
5.3  No blank line immediately after an opening brace or before a closing brace,
     except: one blank line after the opening brace of a class body is acceptable
     when there are field declarations.
5.4  Use blank lines INSIDE a method body to separate logical steps. One blank
     line is enough; never two consecutive blank lines anywhere.
5.5  No trailing whitespace on any line.

## PART 6 — MULTI-LINE ALIGNMENT

6.1  METHOD DECLARATION PARAMETERS. The opening parenthesis goes on the next
     line when the parameter list causes the declaration to exceed the margin.
     All parameters then align under the first parameter:

       public static void install
           (String actId,
            String usrId,
            String usrName,
            String prvId,
            String domain,
            DomatarMsgClient msgClient) throws DomatarException
       {

     When the parameter list is short and the declaration fits on one line,
     keep it on one line.

6.2  METHOD CALL ARGUMENTS. When a call must wrap, align all arguments under
     the first argument:

       ObjDb.addObjIfMissing(forSaleId, "bookstore", "forsale",
                             "For Sale", "Books you are selling");

6.3  CHAINED METHOD CALLS. Align the dots under each other:

       result.setA("x")
             .setB("y")
             .setC("z");

6.4  ASSIGNMENT. When the right-hand side wraps, align continuation lines with
     the token after the '=':

       String resolvedJson =
           ClsResolver.resolve(clsDomId, reqAppId, reqClsId);

6.5  TERNARY. Align '?' and ':' together on the continuation line:

       String value = condition
                      ? trueValue
                      : falseValue;

6.6  THROWS LIST. 'throws' keyword aligns with the method name's indentation;
     exception types align under each other when wrapped:

       public void doAction(HttpServletRequest req, HttpServletResponse res)
           throws ServletException,
                  IOException

6.7  EXTENDS / IMPLEMENTS lists wrap the same way — align under the first type.

6.8  String concatenation that forms JSON or long text: prefer continuation
     aligned under the opening '"':

       return "{"
           + "\"ClsAppId\":\"domatar\","
           + "\"ClsId\":\"cls\","
           + "}";

## PART 7 — FINAL LOCALS AND PARAMETERS

7.1  Declare local variables and parameters as FINAL wherever they are not
     reassigned. Follow this habit in new code.

       public static String resolve(final DomId clsDescDomId,
                                    final String clsAppId,
                                    final String clsId)
       {
         final String cached = ClsMap.get(clsAppId, clsId);
         if (cached != null)
           return cached;
         ...
       }

7.2  Do NOT declare a variable final when it is assigned in one branch of an
     if/try or inside a loop that updates it.

## PART 8 — NAMING

8.1  PACKAGES: all lowercase, no underscores. Match the Maven groupId convention:
       com.domatar.core, com.bookstore.install, com.aiagent.agent

8.2  CLASSES / INTERFACES / ENUMS: UpperCamelCase.
       ClsInstall, SrvImpl, JsonMsg, DomId, ObjImpl

8.3  METHODS: lowerCamelCase, verb-first where applicable.
       handleMsg, addClsObj, ensureSrvsContainer, getOperation

8.4  LOCAL VARIABLES: lowerCamelCase.
       clsAppId, srvDomId, resolvedJson, maxLnks

8.5  INSTANCE FIELDS: lowerCamelCase. Public fields are used on value objects
     (Obj, Lnk, DomId) — follow that pattern; do not add getters/setters for
     structural fields that are already public.

8.6  STATIC FINAL CONSTANTS: UPPER_SNAKE_CASE.
       private static final String CAT_HST_ID = "bookstore";
       private static final long serialVersionUID = -1048792698141910023L;

     Exception: a static final Logger uses lowerCamelCase:
       private static final Logger LOG = Logger.getLogger(...);

8.7  BOOLEAN variables and methods: prefix with is/has/can when naming a state.
       isDirectoryDispatch, hasMore, isVerified, hasRights

8.8  Abbreviations: use the abbreviation consistently, not a mix.
     Established abbreviations in this codebase (do not expand them):
       Id (not Identifier), Hst (not Host), Prv (not Provider),
       Act (not Account), Cls (not Class), Srv (not Service),
       Msg (not Message), Lnk (not Link), Sov (not Domatar),
       Attr / Attrs (not Attribute/s), Opr (not Operation),
       App (not Application), Obj (not Object), Wui (not WebUI),
       cp (contextPath), crp (contextRealPath)

## PART 9 — IMPORTS

9.1  No wildcard imports (e.g. import java.util.*). Import each class explicitly.
9.2  Group order (one blank line between groups):
       1. java.* and javax.*
       2. jakarta.*
       3. Third-party libraries (org.*, com.fasterxml.*, etc.)
       4. com.domatar.* (platform core)
       5. The current app's own package (com.bookstore.*, com.aiagent.*, etc.)
     Within each group, order alphabetically.
9.3  Do not import a class that is never used.
9.4  Static imports: allowed for constants (e.g. AgentDefaults.*) but only when
     the constant's origin is unambiguous without the class qualifier.

## PART 10 — COMMENTS

10.1  Comments explain WHY, not WHAT. Never write a comment that just paraphrases
      the next line of code:
        Wrong: // Increment the counter
               count++;
        OK:    count++;   // must stay in sync with the retry budget (MAX_RETRIES)

10.2  Do NOT narrate a change in comments ("// Added for service migration",
      "// New method"). Code must stand on its own.

10.3  Javadoc (/** ... */) for:
        - Public / package-private classes.
        - Public / protected methods whose contract is not obvious from the
          signature and name alone.
        - Any method whose side effects or thread-safety constraints need recording.

10.4  Single-line Javadoc is fine:
        /** Return the full class descriptor. */

10.5  Block comments (/* ... */) inside method bodies: use sparingly, only for
      multi-paragraph explanations. Use inline // comments for brief context.

10.6  Block comment formatting: a space after /* and before */:
        /* This is a block comment. */
      Multi-line block comment:
        /*
         * First line.
         * Second line.
         */

10.7  Do NOT leave commented-out code in committed files.

10.8  Section dividers inside long methods are acceptable with a standard form:
        // -----------------------------------------------------------------------

## PART 11 — CONTROL FLOW

11.1  Omit braces for single-statement if/for/while/else bodies; put the
      statement on the next line, indented (see rule 3.5). Never place the body
      on the same line as the control keyword.

        if (cached != null)
          return cached;

        for (String ref : implements_)
          flattenService(ref, acc);

      Add braces the moment a second statement is needed.

11.2  Prefer a guard-clause / early-return style over deep nesting:
        if (obj == null)
          return errorMsg("Obj not found");
        // ... rest of method at top indent level ...

11.3  switch statements: each case ends with break or return; no fall-through
      except intentional documented fall-through.

11.4  Avoid catching Exception or Throwable unless at a genuine boundary (e.g.
      AppLoader.loadApp); prefer specific exception types.

## PART 12 — CLASSES AND MEMBERS

12.1  Member order within a class:
        1. Static final constants.
        2. Static non-final fields (rare; document why).
        3. Instance fields.
        4. Constructors.
        5. Static factory methods / static helpers called at construction.
        6. Public methods (interface of the class).
        7. Protected / package-private methods.
        8. Private methods, in call-site-first order (callers above callees).
        9. Inner classes / enums / interfaces.

12.2  One top-level class per file. Inner classes go at the bottom of the
      containing class.

12.3  Access modifiers: use the most restrictive level that is correct.
      Prefer private; use package-private (no modifier) only when a sibling
      class in the same package genuinely needs access.

12.4  Static utility classes: private constructor that throws, or use a pure
      static final class (no instances). Example: ClsMap, MsgsSchemaBuilder.

12.5  Value objects (Obj, Lnk, DomId, Act): public fields are fine; no
      boilerplate getters/setters. New value objects follow the same pattern.

12.6  Do not use Optional<T> in this codebase; return null to signal absence.

## PART 13 — STRINGS AND JSON

13.1  String constants for JSON keys / protocol names are plain literals, not
      named constants, because they are self-documenting and their scope is narrow.

13.2  JSON documents built by string concatenation follow the alignment rule in
      6.8. Keep the entire JSON string builder in one method or in one named
      helper method (e.g. private static String bookSrvJson()) to aid readability.

13.3  Multi-line string building: use "+" concatenation at the start of each
      continuation line, NOT at the end:
        String s = "{"
            + "\"key\":\"value\""
            + "}";

## PART 14 — LOGGING

14.1  Use java.util.logging.Logger.
14.2  Logger field: private static final Logger LOG = Logger.getLogger(<Class>.class.getName());
14.3  Log levels: SEVERE for fatal errors that prevent startup or corrupt state;
      WARNING for recoverable errors; INFO for lifecycle events (startup, install,
      registration); FINE for debug-level dispatch and cache events.
14.4  Never log a password, token, or session secret.

## PART 15 — QUICK REFERENCE (print this when generating new code)

  Indent:       2 spaces, no tabs
  Braces:       Allman (next line) for everything; else/catch/finally on new line;
                single-statement bodies go on next line, no braces, never inline
  Line limit:   240 hard; aim for 140; note 80 as a guide
  Finals:       Declare local vars and params final when not reassigned
  Naming:       UpperCamelCase classes; lowerCamelCase methods/vars; UPPER_SNAKE constants
  Imports:      Explicit (no wildcards); java/jakarta/3rd-party/domatar/app order
  Comments:     WHY only; Javadoc for public API; no change-narration comments
  Control flow: Guard clauses early; Allman braces always for multi-line bodies
  Null:         Return null for absence; no Optional
  JSON strings: "+" at line start; helper method per class; align by 6.8

# END OF DOCUMENT
