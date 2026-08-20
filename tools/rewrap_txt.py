# -*- coding: utf-8 -*-
"""
rewrap_txt.py
=============

Re-wrap the authoritative plain-text source of the Sovevos Advantage document so
that every prose paragraph and bullet flows to a consistent column width, while
leaving the document's decorative structure untouched.

    Spec-SovevosAdvantage.txt   (edited in place)

Usage:
    python tools/rewrap_txt.py

This is the companion of build_advantage_docs.py: run rewrap_txt.py first to tidy
the line breaks in the .txt, then build_advantage_docs.py to regenerate the
Markdown and .docx renderings.

------------------------------------------------------------------------------
WHAT IS PRESERVED VERBATIM (never re-wrapped):
    * Border lines  -- a run of 4+ '=' or 4+ '-' characters.
    * Heading text  -- any line whose NEXT line is a border. This covers the
                       document title, the subtitle, every "PART N" line, and
                       every "N.N" subsection heading, with their original
                       leading spaces and exact wording.
    * Blank lines    -- kept as single blank separators (runs of 2+ blanks are
                       collapsed to one).

WHAT IS RE-WRAPPED:
    * Bullets        -- a line beginning with optional indent + "* ", together
                       with its hanging-indent continuation lines. Re-emitted
                       with the original marker indent, "* " lead-in, and a
                       matching 2-space hanging indent on continuations.
    * Body paragraphs-- everything else; wrapped continuation lines are joined
                       back together and reflowed.

CONVENTIONS / PARAMETERS (the "guessed" choices, recorded so regenerations stay
consistent -- adjust here if the desired style ever changes):
    WIDTH = 78        -- measured from the original hand-wrapped document, whose
                         full lines top out at 78 characters.
    Hyphen-wrap fix   -- a fragment ending in "<alnum>-" is glued to the next
                         fragment WITHOUT a space, so a word split across a line
                         break in the source ("well-\ndefined") rejoins as
                         "well-defined" before re-wrapping.
    break_on_hyphens=False / break_long_words=False
                       -- hyphenated compounds ("self-hosting", "ad-hoc") are
                         never split across lines; over-long tokens are left
                         intact rather than chopped.
------------------------------------------------------------------------------
"""

import os
import re
import textwrap

HERE  = os.path.dirname(os.path.abspath(__file__))
BASE  = os.path.dirname(HERE)
PATH  = os.path.join(BASE, "Spec-SovevosAdvantage.txt")

WIDTH = 78

BULLET = re.compile(r"^(\s*)\*\s+")


def is_border(line):
    t = line.strip()
    return len(t) >= 4 and (set(t) == {"="} or set(t) == {"-"})


def smart_join(parts):
    """Join wrapped fragments with single spaces, but glue a fragment ending in
    '<alnum>-' to the next one with no space (rejoining a hyphen-split word)."""
    out = ""
    for p in parts:
        p = p.strip()
        if out == "":
            out = p
        elif re.search(r"[A-Za-z0-9]-$", out):
            out += p
        else:
            out += " " + p
    return out


def rewrap(lines):
    out = []
    n = len(lines)
    i = 0
    while i < n:
        line    = lines[i].rstrip("\n")
        stripped = line.strip()

        if stripped == "":                              # blank (collapse runs)
            if out and out[-1] != "":
                out.append("")
            i += 1
            continue

        if is_border(line):                             # decorative border
            out.append(line.rstrip())
            i += 1
            continue

        if i + 1 < n and is_border(lines[i + 1]):       # heading text line
            out.append(line.rstrip())
            i += 1
            continue

        m = BULLET.match(line)
        if m:                                           # bullet + continuations
            indent = m.group(1)
            parts  = [BULLET.sub("", line)]
            j = i + 1
            while j < n:
                nxt = lines[j]
                ns  = nxt.strip()
                if ns == "" or BULLET.match(nxt) or is_border(nxt):
                    break
                if j + 1 < n and is_border(lines[j + 1]):
                    break
                parts.append(ns)
                j += 1
            text = smart_join(parts)
            out.append(textwrap.fill(
                text, width=WIDTH,
                initial_indent=indent + "* ",
                subsequent_indent=indent + "  ",
                break_on_hyphens=False, break_long_words=False))
            i = j
            continue

        parts = [stripped]                              # body paragraph
        j = i + 1
        while j < n:
            nxt = lines[j]
            ns  = nxt.strip()
            if ns == "" or BULLET.match(nxt) or is_border(nxt):
                break
            if j + 1 < n and is_border(lines[j + 1]):
                break
            parts.append(ns)
            j += 1
        text = smart_join(parts)
        out.append(textwrap.fill(
            text, width=WIDTH,
            break_on_hyphens=False, break_long_words=False))
        i = j

    return "\n".join(out).rstrip("\n") + "\n"


def main():
    with open(PATH, encoding="utf-8") as f:
        lines = f.readlines()
    result = rewrap(lines)
    with open(PATH, "w", encoding="utf-8") as f:
        f.write(result)
    longest = max((len(l) for l in result.splitlines()), default=0)
    print("rewrapped:", PATH)
    print("longest line:", longest)


if __name__ == "__main__":
    main()
