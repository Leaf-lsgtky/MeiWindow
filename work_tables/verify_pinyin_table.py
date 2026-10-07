# Verifies PinyinTables.kt is a byte-exact transcription of decompiled C2282f
import re

p = r'E:\workspace\flyme\out\SystemUITools_src\sources\com\flyme\systemuitools\common\utils\C2282f.java'
src = open(p, encoding='utf-8').read()

def arr(name):
    m = re.search(name + r'\s*=\s*\{([^}]*)\}', src)
    return re.findall(r'\d+', m.group(1))

def rows():
    m = re.search(r'f7594c\s*=\s*\{(.*?)\};', src, re.S)
    return [re.findall(r'\d+', r) for r in re.findall(r'new byte\[\]\{([^}]*)\}', m.group(1))]

b = arr('f7593b')
py = rows()
print('orig sizes', len(b), len(py), 'same?', len(b) == len(py))

tp = r'E:\workspace\flyme\bubbledrawer\app\src\main\java\com\repl\bubbledrawer\pinyin\PinyinTables.kt'
t = open(tp, encoding='utf-8').read()
mine_b = re.findall(r"^\s*'(.)'(,?)$", t, re.M)
mine_b = [c for c, _ in mine_b]
mine_p = re.findall(r'^\s*"([A-Z]*)"(,?)$', t, re.M)
mine_p = [s for s, _ in mine_p]
print('mine sizes', len(mine_b), len(mine_p))

bad_b = [i for i in range(min(len(b), len(mine_b))) if chr(int(b[i])) != mine_b[i]]
bad_p = []
for i in range(min(len(py), len(mine_p))):
    s = ''.join(chr(int(x)) for x in py[i] if int(x) != 0)
    if s != mine_p[i]:
        bad_p.append((i, s, mine_p[i]))
print('boundary mismatches:', len(bad_b), bad_b[:5])
print('pinyin mismatches:', len(bad_p), bad_p[:5])
print('OK' if not bad_b and not bad_p and len(b) == len(mine_b) == len(py) == len(mine_p) else 'MISMATCH')
