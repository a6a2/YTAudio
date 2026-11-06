#!/usr/bin/env python3
"""
Play Protect bisection helper (diagnostic only, never part of a release).

Usage: python3 tools/diag_variants.py N     (N = 1, 2, 3, 4; run in this order, each builds on the previous)

Each step strips one group of features that were added after 2.2.0 (which Play Protect accepted), so that a few
APKs built from the same code can be test-installed to find which feature trips the scan:

  1  no haptics        removes the VIBRATE permission and all Vibrator code
  2  + no font         removes the bundled IBM Plex font (system font instead)
  3  + no shortcut     removes the launcher "Resume" shortcut
  4  + no device info  removes device model / Android version from problem reports

Every step fails loudly if the code it expects to change is not there, so a variant can never silently be unmodified.
"""
import pathlib, re, shutil, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main"
KT = MAIN / "java/app/ytaudio"


def read(p): return p.read_text(encoding="utf-8")
def write(p, s): p.write_text(s, encoding="utf-8")


def sub_once(path, old, new):
    s = read(path)
    if old not in s:
        sys.exit(f"diag step failed: expected text not found in {path.relative_to(ROOT)}:\n{old[:100]}")
    write(path, s.replace(old, new, 1))


def step1():
    write(KT / "Haptics.kt", '''package app.ytaudio

import android.content.Context

/** Diagnostic variant: haptics removed. */
object Haptics {
    fun click(ctx: Context) {}
    fun start(ctx: Context) {}
}
''')
    manifest = MAIN / "AndroidManifest.xml"
    s = read(manifest)
    s2 = re.sub(r'[ \t]*<!-- Light haptic ticks[^\n]*-->\n', '', s)
    s2 = re.sub(r'[ \t]*<uses-permission android:name="android\.permission\.VIBRATE" */>\n', '', s2)
    if "VIBRATE" in s2 or s2 == s:
        sys.exit("diag step 1 failed: could not remove the VIBRATE permission")
    write(manifest, s2)


def step2():
    sub_once(KT / "Ui.kt", "ctx.resources.getFont(R.font.ibm_plex_sans_arabic)", "Typeface.DEFAULT")
    themes = MAIN / "res/values/themes.xml"
    s = read(themes)
    s2 = re.sub(r'[ \t]*<item name="android:fontFamily">[^\n]*</item>\n', '', s)
    if s2 == s:
        sys.exit("diag step 2 failed: fontFamily item not found in themes.xml")
    write(themes, s2)
    shutil.rmtree(MAIN / "res/font")


def step3():
    manifest = MAIN / "AndroidManifest.xml"
    s = read(manifest)
    s2 = re.sub(r'[ \t]*<!-- Long-press the launcher icon[^\n]*-->\n', '', s)
    s2 = re.sub(r'[ \t]*<meta-data android:name="android\.app\.shortcuts"[^\n]*/>\n', '', s2)
    if "shortcuts" in s2 or s2 == s:
        sys.exit("diag step 3 failed: could not remove the shortcuts entry")
    write(manifest, s2)
    shutil.rmtree(MAIN / "res/xml")


def step4():
    p = KT / "ErrorLog.kt"
    sub_once(p, "Build.VERSION.RELEASE", '"-"')
    sub_once(p, '"${Build.MANUFACTURER} ${Build.MODEL}"', '"-"')


STEPS = {1: step1, 2: step2, 3: step3, 4: step4}

if __name__ == "__main__":
    if len(sys.argv) != 2 or not sys.argv[1].isdigit() or int(sys.argv[1]) not in STEPS:
        sys.exit(__doc__)
    STEPS[int(sys.argv[1])]()
    print(f"diag step {sys.argv[1]} applied")
