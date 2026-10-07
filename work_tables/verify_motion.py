# Verifies every PathInterpolator constant in the decompiled originals exists
# verbatim in our MotionSpec.kt (floatArrayOf), and duration/startDelay values match.
import re, sys

ORIGS = [
    r"E:\workspace\flyme\out\SystemUITools_src\sources\com\flyme\systemuitools\windowmode\widget\GestureAppLauncher.java",
    r"E:\workspace\flyme\out\SystemUITools_src\sources\com\flyme\systemuitools\windowmode\widget\SlideGestureItemView.java",
]
SPEC = r"E:\workspace\flyme\bubbledrawer\app\src\main\java\com\repl\bubbledrawer\bubble\MotionSpec.kt"

def norm(s):
    return tuple(float(x) for x in re.findall(r"-?\d+\.?\d*", s))

curves = set()
for p in ORIGS:
    src = open(p, encoding="utf-8").read()
    for m in re.finditer(r"new PathInterpolator\(([^)]*)\)", src):
        curves.add(norm(m.group(1)))

spec = open(SPEC, encoding="utf-8").read()
spec_curves = set()
for m in re.finditer(r"floatArrayOf\(([^)]*)\)", spec):
    spec_curves.add(norm(m.group(1)))
# the itemview static field uses the same floatArrayOf form? ensure both parse styles covered
missing = sorted(c for c in curves if c not in spec_curves)
extra = sorted(c for c in spec_curves if c not in curves)

print(f"original distinct interpolators: {len(curves)}")
for c in sorted(curves):
    print("  ", c, "OK" if c in spec_curves else "MISSING")
if extra:
    print("MotionSpec curves NOT found in originals (suspicious):")
    for e in extra:
        print("  ", e)

# durations
src = open(ORIGS[0], encoding="utf-8").read()
durs = [int(x.rstrip('L')) for x in re.findall(r"setDuration\((\d+L?)\)", src)]
delays = [int(x.rstrip('L')) for x in re.findall(r"setStartDelay\((\d+L?)\)", src)]
from collections import Counter
print("expand/collapse durations:", Counter(durs))
print("setStartDelay literals   :", Counter(delays))
sys.exit(1 if (missing or extra) else 0)
